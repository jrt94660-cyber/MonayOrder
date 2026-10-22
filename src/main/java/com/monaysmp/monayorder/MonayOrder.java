package com.monaysmp.monayorder;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import java.sql.*;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class MonayOrder extends JavaPlugin {
    private Connection database;
    private Economy economy;

    @Override
    public void onEnable() {
        setupDatabase();
        RegisteredServiceProvider<Economy> provider = getServer().getServicesManager().getRegistration(Economy.class);
        if (provider != null) economy = provider.getProvider();
        if (getCommand("order") != null) getCommand("order").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This command can only be used in-game.");
                return true;
            }
            if (!player.hasPermission("monayorder.use")) {
                player.sendMessage(Component.text("You do not have permission to use Orders."));
                return true;
            }
            openMain(player);
            return true;
        });
        getLogger().info("MonayOrder enabled. Orders database initialized.");
    }

    @Override
    public void onDisable() {
        try { if (database != null && !database.isClosed()) database.close(); }
        catch (SQLException exception) { getLogger().warning("Could not close order database: " + exception.getMessage()); }
    }

    private void setupDatabase() {
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) throw new SQLException("Could not create plugin folder");
            database = DriverManager.getConnection("jdbc:sqlite:" + getDataFolder().toPath().resolve("orders.db"));
            try (Statement statement = database.createStatement()) {
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS orders (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      owner TEXT NOT NULL,
                      owner_name TEXT NOT NULL,
                      item BLOB NOT NULL,
                      item_name TEXT NOT NULL,
                      amount INTEGER NOT NULL,
                      unit_price DOUBLE NOT NULL,
                      created_at INTEGER NOT NULL
                    )
                    """);
            }
        } catch (SQLException exception) {
            getLogger().severe("Could not initialize orders database: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void openMain(Player player) {
        Dialog dialog = Dialog.create(builder -> builder.empty()
            .base(DialogBase.builder(Component.text("MonaySMP • Orders"))
                .body(List.of(
                    DialogBody.plainMessage(Component.text("Browse player orders, search for an item, or list items from your inventory.")),
                    DialogBody.plainMessage(Component.text("Orders are paid through the server economy when a purchase is completed."))
                ))
                .inputs(List.of(DialogInput.text("query", Component.text("Search item")).width(300).labelVisible(true).initial("").maxLength(80).build()))
                .build())
            .type(DialogType.multiAction(List.of(
                ActionButton.create(Component.text("Search Orders"), Component.text("Find matching player listings"), 150,
                    DialogAction.customClick((view, audience) -> {
                        if (!(audience instanceof Player p)) return;
                        String query = view.getText("query");
                        Bukkit.getScheduler().runTask(this, () -> searchOrders(p, query == null ? "" : query));
                    }, null)),
                ActionButton.create(Component.text("Sell Held Item"), Component.text("Create a listing from your main hand"), 150,
                    DialogAction.customClick((view, audience) -> {
                        if (audience instanceof Player p) Bukkit.getScheduler().runTask(this, () -> openSell(p));
                    }, null))
            ))));
        player.showDialog(dialog);
    }

    private void searchOrders(Player player, String rawQuery) {
        String query = rawQuery.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        try (PreparedStatement ps = database.prepareStatement(
                "SELECT id, owner_name, item_name, amount, unit_price FROM orders WHERE lower(item_name) LIKE ? ORDER BY created_at DESC LIMIT 20")) {
            ps.setString(1, "%" + query + "%");
            try (ResultSet rs = ps.executeQuery()) {
                player.sendMessage(Component.text("—— MonaySMP Orders ———"));
                while (rs.next()) {
                    shown++;
                    player.sendMessage(Component.text("#" + rs.getLong("id") + " • " + rs.getString("item_name")
                        + " x" + rs.getInt("amount") + " • $" + String.format(Locale.US, "%.2f", rs.getDouble("unit_price"))
                        + " each • " + rs.getString("owner_name") + " (use /order buy " + rs.getLong("id") + ")"));
                }
            }
        } catch (SQLException exception) {
            getLogger().warning("Order search failed: " + exception.getMessage());
            player.sendMessage(Component.text("Could not search orders right now."));
            return;
        }
        if (shown == 0) player.sendMessage(Component.text("No orders matched your search."));
    }

    private void openSell(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            player.sendMessage(Component.text("Hold the item you want to list in your main hand first."));
            return;
        }
        Dialog dialog = Dialog.create(builder -> builder.empty()
            .base(DialogBase.builder(Component.text("Create Order"))
                .body(List.of(
                    DialogBody.item(held.asOne()).build(),
                    DialogBody.plainMessage(Component.text("Listing: " + held.getType().name().toLowerCase(Locale.ROOT).replace('_', ' '))),
                    DialogBody.plainMessage(Component.text("Choose the quantity and total price. The item will only be removed after valid values are submitted."))
                ))
                .inputs(List.of(
                    DialogInput.numberRange("amount", Component.text("Quantity"), 1.0F, (float) held.getAmount())
                        .step(1.0F).initial((float) held.getAmount()).width(300).build(),
                    DialogInput.text("price", Component.text("Total price (number)")).width(300).labelVisible(true).initial("100").maxLength(20).build()
                ))
                .build())
            .type(DialogType.confirmation(
                ActionButton.create(Component.text("Create Listing"), Component.text("Publish this order"), 150,
                    DialogAction.customClick((view, audience) -> {
                        if (!(audience instanceof Player p)) return;
                        Float requested = view.getFloat("amount");
                        String priceText = view.getText("price");
                        Bukkit.getScheduler().runTask(this, () -> createOrder(p, held.getType(), requested == null ? 0 : requested.intValue(), priceText));
                    }, null)),
                ActionButton.create(Component.text("Cancel"), Component.text("Close without listing"), 150, null)
            )));
        player.showDialog(dialog);
    }

    private void createOrder(Player player, Material material, int amount, String priceText) {
        ItemStack current = player.getInventory().getItemInMainHand();
        double total;
        try { total = Double.parseDouble(priceText == null ? "" : priceText.trim()); }
        catch (NumberFormatException exception) {
            player.sendMessage(Component.text("Enter a valid total price."));
            return;
        }
        if (current.getType() != material || amount < 1 || amount > current.getAmount() || !Double.isFinite(total) || total <= 0) {
            player.sendMessage(Component.text("Your item or order values changed. Reopen /order and try again."));
            return;
        }
        ItemStack listing = current.clone();
        listing.setAmount(amount);
        try {
            byte[] itemBytes = serializeItem(listing);
            try (PreparedStatement ps = database.prepareStatement(
                    "INSERT INTO orders(owner, owner_name, item, item_name, amount, unit_price, created_at) VALUES(?,?,?,?,?,?,?)")) {
                ps.setString(1, player.getUniqueId().toString());
                ps.setString(2, player.getName());
                ps.setBytes(3, itemBytes);
                ps.setString(4, material.name());
                ps.setInt(5, amount);
                ps.setDouble(6, total / amount);
                ps.setLong(7, System.currentTimeMillis());
                ps.executeUpdate();
            }
            current.setAmount(current.getAmount() - amount);
            player.getInventory().setItemInMainHand(current.getAmount() <= 0 ? null : current);
            player.sendMessage(Component.text("Order listed: " + material.name() + " x" + amount + " for $" + String.format(Locale.US, "%.2f", total) + " total."));
        } catch (Exception exception) {
            getLogger().warning("Could not create order: " + exception.getMessage());
            player.sendMessage(Component.text("Could not create your order. Your item was not removed."));
        }
    }

    private byte[] serializeItem(ItemStack item) throws Exception {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            out.writeObject(item);
            return bytes.toByteArray();
        }
    }

    private ItemStack deserializeItem(byte[] bytes) throws Exception {
        try (ObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
            Object value = in.readObject();
            if (value instanceof ItemStack item) return item;
            throw new SQLException("Stored item data is invalid");
        }
    }
}
