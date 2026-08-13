# `reality-economy` — P-07 Reality Economy v1

`reality-economy` is a server-authoritative Forge Mod for Minecraft 1.20.1
(Forge 47.4.10). It stores one non-item currency balance per player UUID in a
persistent ledger owned by the current world. The ledger is stored through the
overworld `SavedData`, so all dimensions in one world share balances while
different worlds have separate ledgers. A new player starts at balance `0`.

## Commands

All commands are server-side and player-only. Console, command blocks, and
other non-player sources fail without changing state. Player arguments are
single online player names only; selectors, UUIDs, and offline names are not
accepted.

- `/realityeconomy balance` shows only the caller's own balance.
- `/realityeconomy admin grant <online-player> <nonnegative-amount> <single-word-reason>`
  adds the amount.
- `/realityeconomy admin revoke <online-player> <nonnegative-amount> <single-word-reason>`
  subtracts the amount only when the resulting balance is nonnegative.
- `/realityeconomy admin inspect <online-player>` shows an online player's
  balance to a permission-level-2 administrator.

The `admin` branch requires permission level 2. Grant overflow and revoke
underflow are rejected atomically, leaving the ledger unchanged. All accepted
mutations and administrator inspections receive a server-generated transaction
ID and write an `economy_audit` record to the server log containing the
transaction ID, actor UUID, target UUID, delta, reason, and timestamp. No
client-supplied balance, amount, or transaction is trusted.

## v1 boundary

The approved v1 policy allows fixed-price server-owned shop purchases, but the
shop catalog and prices are not decided yet, so shop purchasing is deliberately
not implemented here. Player-to-player transfers, NPC integration, client UI,
network packets, tick handlers, configuration, databases, external Mod or
Foundation/core dependencies, audit-retention policy, and reset operations are
also outside this v1.

## Build

```sh
./gradlew :compileJava --no-daemon
```

Java 17, Gradle 8.8, and Apache-2.0 are required by the project contract.
