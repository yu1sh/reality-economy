# `reality-economy` — P-07 Reality Economy v1

`reality-economy` is a server-authoritative Forge Mod for Minecraft 1.20.1
(Forge 47.4.10). It stores one non-item currency balance per player UUID in a
persistent ledger owned by the current world. The ledger and Shop state use the
overworld `SavedData`, so all dimensions in one world share the same economy
and shop state while different worlds have separate state. A new player starts
at balance `0`.

## Economy commands

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
underflow are rejected atomically, leaving the ledger unchanged. Accepted
grant/revoke mutations and administrator inspections receive a server-generated
transaction ID and write an `economy_audit` record to the server log containing
the transaction ID, actor UUID, target UUID, delta, reason, and timestamp.

## Economy GUI and command fallback

`/realityeconomy balance gui` opens the slotless Economy GUI. It is the complete
GUI entry point for the Economy command surface; a permission-level-2 operator
sees the administrator controls, while an ordinary player sees their own
balance only.

| Command path | Equivalent Economy GUI action |
| --- | --- |
| `/realityeconomy balance` | `Refresh balance` and the server-produced own-balance snapshot |
| `/realityeconomy admin grant <online-player> <nonnegative-amount> <single-word-reason>` | Select an online player, enter the same amount and reason, then `Grant` |
| `/realityeconomy admin revoke <online-player> <nonnegative-amount> <single-word-reason>` | Select an online player, enter the same amount and reason, then `Revoke` |
| `/realityeconomy admin inspect <online-player>` | Select an online player, then `Inspect` |

The GUI request contains only a request identity, the active menu ID, an
operation, an online-player name, an amount, and a reason. The server derives
the actor from the current network sender and rechecks the menu session,
permission level, online target, amount/reason bounds, request replay, and
current ledger state. It returns a server-produced snapshot containing the
current balance, permission view, target result, transaction ID, and message;
the client does not provide any of those values as authority. The existing
commands remain available as the management and automation fallback.

## Shop v1

The implemented shop has ID `default`. Its catalog is persisted per world
epoch and begins with these active entries at catalog revision `1`:

| Entry | Item | Quantity | Price |
| --- | --- | ---: | ---: |
| `bundle_a_food_bread` | `minecraft:bread` | 16 | 12 |
| `bundle_a_light_torch` | `minecraft:torch` | 32 | 8 |
| `bundle_a_build_oak_planks` | `minecraft:oak_planks` | 32 | 10 |
| `bundle_b_food_cooked_beef` | `minecraft:cooked_beef` | 8 | 14 |
| `bundle_b_light_torch` | `minecraft:torch` | 16 | 6 |
| `bundle_b_build_cobblestone` | `minecraft:cobblestone` | 64 | 18 |

Catalog rules and management are server-validated:

- At most 16 entries can be active and at most 256 entries are retained.
  Catalog pages contain at most 16 entries.
- Only an appointed clerk can add, change, stop, or resume entries, and the
  submitted catalog revision must still be current.
- Add/change accepts only the server's allowlisted, tag-free vanilla items;
  quantity must be at least `1`, no more than `64`, and no more than the item's
  stack size. Price must be between `1` and `100000`.
- Stopped entries remain retained but are hidden from ordinary catalog views.
  Clerks and permission-level-2 administrators can inspect inactive entries.
- A permission-level-2 administrator appoints or revokes another online player
  as clerk. An administrator cannot appoint or revoke themself.

Purchasing requires the current catalog revision, an active entry, enough
balance, enough inventory capacity, and no unresolved recovery for the buyer.
The server journals a purchase as `PENDING` before delivery, confirms the item
delivery, and then applies a ledger debit keyed by the server-generated
purchase ID. The debit record is persistent and cannot be applied twice on a
retry. A delivery that cannot be confirmed becomes `RECOVERY_REQUIRED` without
a debit; a confirmed delivery whose debit does not commit also becomes
`RECOVERY_REQUIRED`, with the delivered item retained.

Shop command paths are:

```text
/realityeconomy shop open
/realityeconomy shop list
/realityeconomy shop detail <entry> <revision>
/realityeconomy shop purchase <entry> <revision>
/realityeconomy shop clerk add <item> <quantity> <price> <revision>
/realityeconomy shop clerk change <entry> <item> <quantity> <price> <revision>
/realityeconomy shop clerk stop <entry> <revision>
/realityeconomy shop clerk resume <entry> <revision>
/realityeconomy shop admin appoint <online-player>
/realityeconomy shop admin revoke <online-player>
/realityeconomy shop admin recovery status
/realityeconomy shop admin recovery retry <purchase_id> <revision>
/realityeconomy shop admin recovery resolve <purchase_id> <revision>
/realityeconomy shop admin reset
```

The `shop admin` branch requires permission level 2. Shop requests and command
calls use the same server-side service. The server checks the actor, open menu,
protocol, shop ID, world epoch, catalog revision, input bounds, and the
operation-specific permission, item, balance, and inventory rules before
changing state. Shop catalog mutations, purchases, recovery actions, clerk
changes, and reset outcomes are stored in the shop's persistent audit/request
records.

## Shop GUI and command fallback

`/realityeconomy shop open` opens a slotless Shop menu. After it is open, the
GUI exposes the Shop v1 list, detail, purchase, clerk catalog, clerk assignment,
recovery, and reset actions. The corresponding `/realityeconomy shop ...`
commands remain available as the fallback path.

The client renders server-produced snapshots and sends untrusted requests. It
does not own balances, catalog entries, permissions, purchase status, or
recovery decisions; all state-changing Shop GUI actions are explicit validated
server requests. The Economy GUI above is separate from the Shop GUI and does
not change Shop contracts or ledger semantics.

An appointed clerk receives an additional server-produced item picker in the
add/change form. Its candidates are generated in fixed order from the existing
allowlisted, tag-free vanilla item policy, returned in bounded pages, and are
the only values the picker writes into the existing item field. Ordinary
players, administrators who are not clerks, and permission-mismatched views
receive no picker candidates. The final add/change request still rechecks the
actor, menu, protocol, world epoch, catalog revision, item policy, quantity,
price, request identity, audit, and persistence path. The picker supports
keyboard page navigation, item selection, and back navigation, with English and
Japanese translations.

The Shop GUI wire contract is protocol version `3`; the additive picker fields
are consumed atomically by this child’s server and client, and older version-2
Shop GUI clients are rejected by the channel handshake.

## Recovery and reset

Shop purchase journals survive a restart. Unresolved `PENDING` or
`ITEM_DELIVERED` records are moved into recovery handling on load; a matching
persistent debit can be reconciled, and a delivery-confirmed recovery can have
its debit retried. A buyer with an unresolved purchase is stopped from making
new purchases until recovery is resolved.

Permission-level-2 administrators can use recovery status, debit retry, and
uncertain-delivery resolution. A retry is allowed only when delivery is
confirmed. Resolution refuses a conflicting debit; a matching debit can mark
the purchase committed without another delivery attempt, while an unresolved
delivery with no debit can be closed as failed without retrying delivery or
debit.

`/realityeconomy shop admin reset` starts a new world epoch, clears the current
economy ledger, and seeds a fresh catalog. Previous shop purchases and audit
records remain in retained epochs, and the previous ledger data is archived.

## v1 boundary

Player-to-player transfers and NPC integration are not implemented in this
Shop v1 surface.

## Build

```sh
./gradlew :compileJava --no-daemon
```

Java 17, Gradle 8.8, and Apache-2.0 are required by the project contract.
