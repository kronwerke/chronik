# Chronik

Who changed which block, container and entity, and when, on a modded NeoForge 1.21.1 server.
Like CoreProtect, but as a mod, and built for packs with hundreds of mods: machines, quarries,
Create contraptions, bombs and fake players are put down to the player behind them.

Server side only. Clients need nothing.

## How it knows who did it

Every block change in a loaded world goes through one place (`LevelChunk.setBlockState`). That
place does not know who caused a change, so Chronik sets an actor around the code that acts for
someone:

- a player's packets: using items and blocks, breaking, interacting, clicking in containers
- every entity tick: the player, the owner of something a player threw, lit or assembled (TNT,
  arrows, falling blocks, Create contraptions, bombs), or the mob itself (`#minecraft:creeper`)
- every tick of a block entity that has an owner: whoever placed the machine
- every explosion: whoever lit it

Owners are saved with the entity or block entity, so a drill that breaks blocks a week later is
still put down to whoever built it. A change while nobody acts is nature (water flowing, fire,
growth) and is not logged. Mods that place or break for a player later from their own tick are
caught through NeoForge's block events.

## Performance

The server thread never touches the disk. It puts small entries into a queue; a writer thread
stores them in one SQLite file next to the world (`world/chronik.db`), a few thousand per
transaction, and a second thread answers lookups. With nobody acting, the block hook costs one
field read. Rollbacks and restores run a few milliseconds per tick for as many ticks as they need.

## Commands

`/chronik`, short `/co`:

| Command | |
|---|---|
| `/co i` | inspector on or off: left click shows the block, right click the container or the spot in front |
| `/co near [radius]` | what happened around you |
| `/co l <params>` | look up |
| `/co page <n>` | next page |
| `/co rb <params>` | roll back (needs `t:` and `u:` or `r:`) |
| `/co rs <params>` | restore |
| `/co undo` | undo the last rollback or restore |
| `/co purge <days>` | delete older entries |
| `/co status` | size, written, queued, dropped |

Parameters: `u:Name,#creeper` who, `t:2h30m` how far back, `r:10` radius in blocks (all heights)
or `r:#world` / `r:#global`, `a:break,place,change,block,take,put,container,kill,drop,pickup,item,chat,command,session`
what, `b:diamond_ore` which blocks, items or entities, `w:the_nether` which world.

## Web API

With `web.port` and `web.token` set in `config/chronik.toml`, a read-only JSON API listens on
127.0.0.1 only: `GET /status`, `GET /lookup?q=u:Name t:2h&world=minecraft:overworld&x=0&y=64&z=0&limit=200`,
with `Authorization: Bearer <token>`.
