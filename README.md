# MonayOrder

MonaySMP's player-to-player item Orders plugin, targeting Paper 26.2 and Java 25.

## Planned user experience
- Java players: Paper native Dialog API for browsing/searching orders and creating sell orders.
- Bedrock players: inventory-based fallback UI for compatibility.
- Search orders by item name; create orders by selecting an item from your inventory and entering quantity and unit price.
- Persistent order data, safe inventory handling, expiry/cancel flows, and economy integration are required before production use.

## Build
Run `./gradlew clean build` with Java 25. The JAR will be in `build/libs/`.

## Status
This repository is being initialized. Do not use on a live economy server until the order transaction, persistence, and UI flows are implemented and tested.
