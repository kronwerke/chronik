# Changelog

## 1.2.1

- The web API takes its port and token from `CHRONIK_WEB_PORT` and `CHRONIK_WEB_TOKEN` when both are set (the launcher sets them per server); servers that share a config folder no longer try the same port.

## 1.2.0

- A rollback compares the block, not its state: a fence, pane or wire that changed shape, a crop that grew, a furnace that lit up or a door that opened is no longer "newer work" and is rolled back. Where the entry left air, water or lava that flowed in since is replaced too. After the job every touched block and its neighbours take their shapes again, so a fence put back connects. (R2-04)
- Containers: items moved by a mod's own buttons (transfer and sort buttons, inventory sorting mods) are logged as well. Every tick the container and the player's own items are compared; what the container lost and the player gained, or the other way round, is the player's. A furnace or a pipe still changes only one side and is not put on the player. Ender chests are not logged at the block any more, since their items live in the player file. (R2-16)

## 1.1.0

- Containers: only the player's own clicks count. Before and after every click (and every recipe placed by a recipe book or JEI) the container's slots are counted; a furnace smelting or a pipe filling the chest while the menu is open is no longer put on the player. The entry sits at the block entity behind the menu, or at the block clicked in the same moment the menu opened. A menu from an item (a backpack opened by key) is not logged.
- Rollbacks and restores show what they would do first (how many changes, which world and area) and run only after `/co rb confirm` (or `/co rs confirm`) within a minute. A block is only changed while it still is what the entry left behind; newer work in the same place stays and is counted. A whole world (`r:#world`, `r:#global`) needs a player. At most `rollback.maxRows` (200,000) changes at once.
- A block entity a rollback removes (a chest someone placed) is kept in its entry, so a restore brings it back with its contents. Neighbours are updated once a job is through, so doors, beds and multiblocks come back whole.
- The daily purge deletes 5,000 rows per step and lets the writer store new entries in between; afterwards the file shrinks (incremental vacuum; a file of 1.0.0 is converted once at start while it is under 256 MB).
- `log.skipWorlds`: worlds that are not logged at all. `log.quietBlocks`: natural ground a quarry, miner or drill digs is not logged (stone, ores, dirt, gravel, sand and the like); what players break and everything else machines break still is. `log.pickupMergeSeconds` (10): pickups of one item by one player in that time are one entry.
- Direct messages (`/msg`, `/tell`, `/w`, `/r`, `/teammsg` and the like, `log.privateCommands`) keep the command and the receiver, never the text.
- Test players of Kronwerke Core's load test are not logged.
- `/chronik snapshot` (operator level 4, the launcher's backup uses it) writes a consistent copy of the log next to the live one with `VACUUM INTO`, then `chronik-snapshot.done`.
- Messages say what was left alone and why, that kills, drops and pickups cannot be rolled back, and that `/co undo` only works until the next restart.

## 1.0.0

First release.
- Logs block changes (place, break, replace, and doors, levers and the like when a player uses them), container transactions, kills, item drops and pickups, chat, commands, joins and leaves.
- Puts machine, explosion, entity and Create contraption changes down to the player behind them, with owners saved on entities and block entities.
- Inspector, lookup with CoreProtect style parameters, paging, rollback and restore with block entity contents and container items, undo, purge, status.
- Writes on its own thread to SQLite (WAL), lookups on another; rollbacks spread over ticks.
- Read-only JSON API on 127.0.0.1 for a web view.
