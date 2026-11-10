# Changelog

## 1.0.0

First release.
- Logs block changes (place, break, replace, and doors, levers and the like when a player uses them), container transactions, kills, item drops and pickups, chat, commands, joins and leaves.
- Puts machine, explosion, entity and Create contraption changes down to the player behind them, with owners saved on entities and block entities.
- Inspector, lookup with CoreProtect style parameters, paging, rollback and restore with block entity contents and container items, undo, purge, status.
- Writes on its own thread to SQLite (WAL), lookups on another; rollbacks spread over ticks.
- Read-only JSON API on 127.0.0.1 for a web view.
