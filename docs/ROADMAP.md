# Kestrel roadmap: to a fully playable vanilla server

Goal: a server a vanilla 26.2 client can join and play exactly like a vanilla
dedicated server, survival from punching the first tree to killing the dragon,
with every standard server capability an owner expects (config file, ops,
permissions, whitelist, bans, commands, console, server list ping). Same game
as vanilla, faster.

This file replaces the 13-item roadmap from 2026-09-12. Those 13
items keep their numbers. New items continue from 14.

Per-mechanic detail (file, gating check, what is approximated) lives in
`docs/CAPABILITIES.md`. This file is the build order.

## Legend

- **G** = gameplay (what a player sees), **S** = server (infrastructure,
  administration, protocol).
- **Size**: 1 = an evening, 10 = several weeks.
- **Status**: exactly one of `done`, `partial` (works but simplified or
  incomplete), `in progress` (an agent is on it right now), `not started`.
- **Done means exact.** Anything with vanilla behaviour players rely on
  (timings, update order, drops, damage, AI) is only `done` when it matches a
  vanilla oracle or gate. Admin features are `done` when they behave like the
  vanilla dedicated server (same files, same formats, same messages).

### Item table schema

Every item table in this file has the same six columns, in this order:

| column | content |
|---|---|
| `#` | the item's number, `G` or `S` prefixed |
| `Item` | a short name |
| `Size` | 1 to 10, as above |
| `Status` | one of the four Status words, and nothing else |
| `Depends on` | item ids, milestone ids (`M1` to `M3`), or `none` |
| `Scope` | what the item covers and what is left |

Rules that go with the schema:

- **`Scope` never repeats the Status word.** Status is the column; `Scope` says
  what is in and what is left, with dates, commits, gates and owners.
- **One item, one row.** A slice is described inside its item's `Scope`, and
  never becomes its own row.
- **Order.** Within one item table, items are in ascending numeric order. The `G`
  and `S` series share one number space and, for the same number, `G` comes
  before `S`. No number appears twice. Numbering starts fresh in each phase, so
  the file as a whole is not one ascending run; each phase's table is.
- **The Milestones table below is not an item table.** It is a summary of what
  the milestones need, and keeps its own three columns.

## Milestones

| Milestone | Meaning | Needs |
|---|---|---|
| **M1 Playable survival SMP** | Friends can join, gather, build, craft, fight, die and respawn in the Overworld; owner can run it like a normal server | S6, 14-42, and a first mob batch (G44-G46: farm animals and basic hostiles) |
| **M2 Full vanilla** | Everything in the game works: all mobs, Nether and End with portals, redstone, villagers, raids, bosses, enchanting, brewing | G10, G12, 43-60 |
| **M3 Launch** | Vanilla worlds load, big-server performance proven, plugin API | 61-65 |

---

## Part 1: the original 13 items

| # | Item | Size | Status | Depends on | Scope |
|---|---|---|---|---|---|
| G1 | Block placing | 2 | partial | none | The stone stub is gone (G20, 2026-10-08): a click places the block of the held item through vanilla's own use-item-on flow. What is still owed is listed on G20. |
| G2 | Real terrain (Overworld) | 8 | done | none | Exact vs vanilla, Paper speed. Structure chests, spawners and structure mobs are dropped at handoff (G47). |
| G3 | Day/night + weather sync | 4 | partial | none | Day count and moon phase exact vs vanilla oracle; thunder and lightning missing (G50). |
| S4 | Save everything | 4 | partial | none | Blocks, position, time and weather save, but in a Kestrel format; inventory, health, gamemode etc. do not exist yet. Finished by S33 (vanilla level.dat and playerdata). |
| G5 | Chat + player visibility | 3 | partial | G15 | The visibility half is exact since G15 slices 1 and 2: vanilla's tab list rows (join, leave, the hat layer) and the bodies, tracked as entities, with movement, head rotation, sneaking, sprinting, the swing, held items and armour, gated by `PlayerTrackingParityCheck` against real vanilla `ServerPlayer`s. The chat half is not vanilla: a message is broadcast as a `<name> message` system chat packet, with no chat types and no signatures, which is S41. Tab list latency updates are not sent. |
| S6 | Real light engine | 6 | partial | none | Light in generated terrain is exact. Placing or breaking a light source does not update light yet. |
| G7 | Mobs + animals | 9 | not started | S14 | Component proofs exist (AI sensors, spawn caps, collision). Split into items G44, G45, G46 (plus G48, G49, G57). G30 slice 2 names G44 as the owner of the random-tick draw gap. |
| S8 | Movement checks | 4 | done | none | Rate limits and rubber-banding. Revisit with real physics (G24). |
| S9 | Chunk smarts | 5 | done | none | Revision-based resend, async generation. |
| G10 | Nether + End | 7 | done | none | Nether and End terrain generation matches the captured vanilla trace in every dimension: 324/324 chunks each, 0 light diffs, block tier exact (merged 2026-09-26). Nobody can play there yet: the live server runs the Overworld only until G43 (travel). The last Overworld light diffs were a missing mechanism, chunk-activation post-processing, which now runs live. |
| G11 | Water + lava flow | 8 | done | none | Exact vs vanilla oracle, live. Bubble columns over worldgen magma and soul sand are raised at chunk activation (G10 round 8); columns forming or changing later, their drag, and fizz sounds remain (small follow-up). Worldgen springs are G47. |
| G12 | Redstone | 10 | partial | none | Core engine and primary components exact against vanilla (29 fixtures, live): wire, torches with burnout, repeaters, comparators, observers, levers, buttons, lamps, pistons, the block-event queue and moving pistons. The section 1.3 secondary components are NOT built: pressure plates, tripwire, target block, daylight detector, note block, bells, doors, trapdoors, fence gates, dispenser, dropper, crafter, copper bulb, sculk sensor, calibrated sculk sensor, lightning rod, TNT, rails, and hopper locking (the hopper transfer itself has a component proof). Most need entities or containers (S14, G17, G28), so the rest of this item is queued behind them. |
| S13 | Error hardening | 4 | done | none | Per-session inbound work bounded. |

---

## Part 2: new items, in build order

### Phase A: the survival core (M1)

The entity system comes first: dropped items, other players, mobs,
projectiles, arrows, boats and XP orbs are all entities, and Kestrel has none.

| # | Item | Size | Status | Depends on | Scope |
|---|---|---|---|---|---|
| S14 | Entity system | 8 | partial | none | Entity storage per chunk, entity tick phase in `TickLoop` (vanilla order), entity IDs, tracking range and spawn/move/remove packets, entity persistence in `entities/` region files, collision via `KestrelCollide`. Slice 1, the in-memory storage and indexing layer, landed 2026-09-25 and is gated by `EntityStorageCheck`. Slice 2, the tracker and the entity packet stream, landed 2026-09-26 and is gated by `EntityTrackingCheck`: it ticks from `TickLoop` in vanilla's `chunkSource` slot and mirrors `ServerEntity` and `ChunkMap.TrackedEntity`, with streams verified byte for byte against vanilla's own `ServerEntity`. Slice 3, the entity tick phase and the item entity, landed and merged 2026-09-26 and is gated by `ItemEntityTickCheck`: entities tick in vanilla's `entities` slot (after the block events, before the block entities, inside the `emptyTime < 300` guard), players and entities share one id minter, and a dropped item's tick is diffed in raw bits against a real vanilla `ItemEntity`, its pairing bundle and packet stream byte for byte. Block drops create items since G19 slice 1 (2026-10-02), and Q drops and pickup since G19 slice 3. Since G15 slice 1, players are tracked entities too (their bodies), and the moved-players pass runs off their tracked sections as in vanilla. Slices 4 to 6 (region-file persistence, the chunk visibility lifecycle, the stationary mob shell) are not started. |
| G15 | Seeing other players | 3 | partial | S14 | Slices 1 and 2 landed and merged 2026-09-28, gated by `PlayerTrackingParityCheck` (a live `NetServer` with loopback clients next to real vanilla `ServerPlayer`s, their listeners, `doTick`, `ServerEntity` and `ChunkMap.TrackedEntity`, byte for byte per observer and tick). Each Kestrel player now has one real vanilla `ServerPlayer` owning its inventory, menu, tracked body and equipment state, so `PlayerStandIn` is gone and both sides of the gate are real players. Implemented: tab list rows, pairing, movement, head rotation, sneaking, sprinting, swings, held items, armour, per-tick equipment changes, the clean hand swap as entity event 55, leave and same-profile rejoin. Hurt animation and damage: G22. Tab list latency updates, swimming, gliding, sleeping and riding: not started. |
| S16 | Chunk loading and unloading | 6 | not started | S14 | Vanilla ticket levels (entity ticking, block ticking, border), simulation distance separate from view distance, spawn chunks, unloading and saving idle chunks. Today nothing is ever unloaded. |
| G17 | Items and inventory | 6 | partial | none | Slice 1 stack codec landed 2026-09-25; slices 2 and 3 inventory storage and slot sync landed 2026-09-28 (`ItemStackCodecCheck`, `InventoryParityCheck`). Slice 4 (2026-10-02) routes the player inventory click protocol: pickup, shift click, hotbar/offhand swap, drag, double click, slot throw, outside drop and close, with hashed prediction and stale-state recovery. `InventoryClickParityCheck` drives real vanilla click/close handlers; `BlockBreakingCheck` l checks socket dispatch and tracked drops. G19 slice 3 already owns Q drops and pickup. Still owed: creative inventory, pick-block and playerdata persistence; crafting recipes are G21 and other containers are G28. |
| G18 | Block breaking done right | 5 | partial | G17 | Hardness, tool speed and harvest level, Efficiency, Haste and Mining Fatigue, underwater and airborne penalties, break progress, crack animation, creative instant break. A specification is written (111 citations checked against the vanilla source, two Kestrel seam claims corrected). It found that today `STOP_DESTROY_BLOCK` sets the block to air with no reach, height or progress check, and no break particles, tool wear, exhaustion or crack animation; slices: instant break and ack, mining speed, multi-tick progress and cracks, consequences. **2026-10-02:** the survival break state machine is in (`BlockBreaking`, a transcription of `ServerPlayerGameMode`'s break half: reach, height, spawn protection and border, instant break on START, 0.7 early finish, delayed destroy, abort, fluid left behind) with every dig acked, and the tool and progress math is vanilla's own `getDestroyProgress` on the session's vanilla player (the live generator's vanilla resource load already binds the block tags and item components; `ServedTags` is the fallback the gate uses). This fixed grass, flowers and sugar cane looking broken but staying on the server. Gate: `BlockBreakingCheck` (10 of 10 mutants killed). **2026-10-02, slice 2:** the crack animation is in (`ServerLevel.destroyBlockProgress` via `BlockDestructionBroadcast`: stage on START and on each change, -1 on abort, on a late STOP and when the block vanishes mid-dig, none when a delayed destroy finishes; sent to players in the level within 32 blocks of the block's corner, never to the breaker), byte-checked against vanilla's own packet codec (13 of 13 mutants killed). **Slice 3a (same day):** other players now hear and see a block break (level event 2001): `BlockBreaking.destroyBlock` calls vanilla's own `playerWillDestroy` and `Block.destroy`, and a player-sourced level event leaves that player out as `ServerLevel.levelEvent` does. G19 slice 1 since loads the loot tables, so those breaks now drop vanilla's items. **Slice 3 (same day):** tools wear through vanilla's own `ItemStack.mineBlock` (one point per block, none on destroy-speed-0 blocks, the wrong tool too, `ITEM_USED`, and on the last point the tool breaks with `ITEM_BROKEN` and entity event 47), the client's slot shows the new damage through the menu diff, and a START inside spawn protection gets vanilla's red overlay. Spawn protection now follows vanilla's operator rule (nothing is protected while the ops list is empty, operators never), so it is off live until S36 brings an ops list; before, Kestrel protected spawn where vanilla would not. Exhaustion and the `BLOCK_MINED` stat come from vanilla's `playerDestroy` (G19). **Slice 4 (2026-10-08):** START runs vanilla's own `EnchantmentHelper.onHitBlock` and `BlockState.attack` on the stand-in player and level, in vanilla's order, and reads the destroy progress from the state it read before them. Redstone ore lights on the hit (the lit state reaches everyone, its section starts random ticking and vanilla's own `randomTick` unlights it), a dragon egg teleports by vanilla's 1000-offset search over the level random, and a note block queues its block event; the stand-in level now reaches the loop's block-event queue. Out of reach and inside spawn protection nothing is hit. Gate: `BlockBreakingCheck` scenario m and the spawn-protection scenario (181 checks, 14 of 14 mutants killed). Not delivered: the note block's sound (G67) and the `ClientboundBlockEventPacket` for it and for pistons (G69). Still missing: creative instant break, which is G31's. |
| G19 | Block drops and item entities | 5 | partial | S14, G18 | Block loot tables (Fortune, Silk Touch), item entity physics, merging, 5-minute despawn, pickup, dropping items (Q and inventory), XP orbs from blocks. A specification is written (122 citations checked against the vanilla source, three `Stats.java` lines corrected); its Kestrel dig-handler line numbers predate the G18 fix. **Slice 1 (2026-10-02):** breaking a block drops vanilla's loot: `BlockBreaking.destroyBlock` finishes vanilla's `ServerPlayerGameMode.destroyBlock` (correct tool, pre-wear tool copy, `Block.playerDestroy`), loot rolls from the per-table random sequences on the world seed, and vanilla's `ItemEntity` is bridged into a tracked `KestrelItemEntity` at the same position, motion and pickup delay. Fortune, Silk Touch, shears and `block_drops` all come from vanilla's own tables. Gates: `BlockLootParityCheck` and `BlockBreakingCheck` scenarios h and i (6 of 6 mutants killed). XP is computed but orbs are not spawned until G26; infested blocks' silverfish wait for G44. **Slice 2 (2026-10-02, two phases):** dropped items merge as vanilla's do (every 40 ticks at rest, every 2 after moving into a new block, within 0.5 sideways, stack limits, owner, age and pickup delay inherited), and despawn after 5 minutes. Gate: `ItemEntityMergeCheck` against a real vanilla `ItemEntity` plus `BlockBreakingCheck` j on the live loop (10 of 10 mutants). **Slice 3 (2026-10-02, two phases):** Q and Ctrl+Q drop the held item through vanilla's own `ServerPlayer.drop` (position, throw direction, 40-tick pickup delay, thrower, stats), and players pick items up through vanilla's own touch loop in the player tick (pickup delay, owner target, full and partial inventories, take packet and slot update to the clients). Gate: `ItemPickupAndDropCheck` plus `BlockBreakingCheck` k on the live loop (11 of 11 mutants plus one equivalent). Inventory-screen throws and outside drops landed with G17 slice 4; tool wear landed with G18 slice 3. Still owed: XP orbs (G26). |
| G20 | Placing the held item | 4 | partial | G17 | **Slice 1 landed 2026-10-08**: `NetServer.applyPlace` is replaced by `BlockPlacing`, a transcription of vanilla's `handleUseItemOn` and the item half of `ServerPlayerGameMode.useItemOn`, run on the player's stand-in. The block is the one the item's own `getStateForPlacement` returns (stairs, logs, slabs, doors, beds, torches, hoppers, barrels, repeaters and the rest), it is consumed in survival, an item's block-entity data and components are applied, and another player or a falling block in the cell blocks it. Reach, the cursor test, build limits, spawn protection, the world border, a pending teleport and the not-yet-loaded client are vanilla's. Gated by `BlockPlacingCheck` (255 checks, 30 scenarios, 78 of 83 mutants killed, the other five accepted in the report). Still owed: the clicked block's own interaction (G21), the place sound (G67), game events (G12), and block entities beyond comparators and plain containers, whose data an item carries (G28, G47). |
| G21 | Block interactions | 6 | not started | G20 | Right-click behaviour: doors, trapdoors, fence gates, beds, signs (editing), cake, note blocks, composters, cauldrons, candles, flower pots, lecterns, bells, buckets (fill and empty). |
| G22 | Health, damage, death, respawn | 6 | partial | S14 | Slices 1 and 2 landed together 2026-10-02. Each live player has a real vanilla `ServerGamePacketListenerImpl` behind `KestrelServerPlayer`, so vanilla's own health, invulnerability window, damage packets and per-connection fault handling run (a fault disconnects one player, never the server). Void, suffocation and drowning damage reach real vanilla damage sources. Death goes through vanilla `ServerPlayer.die`: death screen and message, entity events 3 and 60, the body removed for others at deathTime 20, inventory dropped as item entities unless `keepInventory`, and respawn at the world spawn with vanilla's respawn packet stream (gates `PlayerSelfPacketsCheck`, `PlayerDeathParityCheck` 29 checks / 7 scenarios, 6 of 6 mutants killed). The dead player is reset in place instead of being replaced by a new `ServerPlayer` (`restoreFrom` fields checked one by one), because the session owns one stable player. Still owed: fall, fire, lava, cactus and berry-bush triggers, bed and respawn-anchor spawns, end credits, cross-dimension respawn, health persistence (a player who leaves while dead rejoins at full health). |
| G23 | Hunger and food | 4 | not started | G22 | Food and saturation, exhaustion from actions, eating, natural regeneration, starvation. A specification is written (118 citations checked against the vanilla source). It found that Kestrel's `KestrelServerPlayer.jumpFromGround` override skips vanilla's exhaustion tail (`ServerPlayer.java:1570-1578`), so jumping must route through vanilla when G23 lands. |
| G24 | Server-side player physics | 6 | not started | S14 | Real collision with terrain, stepping, climbing, swimming and buoyancy, slipperiness, powder snow, cobwebs, elytra. Tightens S8 without false rubber-bands. Pre-research is written (`preresearch/g24-player-physics-preresearch.md`, 2026-09-28). |
| G25 | Combat | 6 | not started | G22 | Melee attack, attack cooldown, critical hits, sweeping, knockback, armour and toughness, shields, damage enchantments. |
| G26 | Experience | 3 | not started | G19 | XP from mining, smelting, breeding and kills, levels, XP orbs merging and pickup, Mending. |
| G27 | Crafting | 5 | not started | G17 | 2x2 and 3x3 crafting, datapack recipe parsing, recipe book and unlocks, shift-click crafting, crafter block. |
| G28 | Containers | 6 | not started | G17 | Live block entities with full data: chests (single and double), barrels, shulker boxes, ender chests, hoppers (component proof exists), dispensers and droppers, open-screen and container sync packets, dropping contents on break. |
| G29 | Furnaces and workstations | 5 | not started | G28 | Furnace, blast furnace, smoker (fuel, cook time, XP), stonecutter, smithing table, grindstone, loom, cartography table. |
| G30 | Random ticks | 5 | partial | none | Slice 1 landed 2026-09-28: the random tick driver on vanilla's own simulation tracker, the level random and `randValue` in vanilla's order, and vanilla's own `randomTick` on a stand-in `ServerLevel`; crops, grass, mycelium and leaf decay gated by `RandomTickParityCheck`. Left: gating the other families (saplings growing into trees, ice and snow, cactus and sugar cane, copper oxidation, fire spread), what `tickPrecipitation` does with a hit beyond the plains cases, and the live approximations listed in the report. **Live divergence, measured 2026-09-28:** with `doMobSpawning` true, which is the vanilla default (`GameRules.java:81`) and the live server's state, vanilla consumes at least three level-random draws per spawning category per eligible chunk per tick in `NaturalSpawner.getRandomPosWithin` (`NaturalSpawner.java:343-346`) and more in `spawnCategoryForPosition` (`:173-195`), and Kestrel's driver consumes none, so every later draw in the tick lands elsewhere and crops, grass, leaves and ice diverge from vanilla whenever a player is online. Nothing said so: `RandomTicks` mentions `spawnMobs` only in a comment, so no `Unanswered` is raised. Slice 2 landed 2026-10-01 (main `e3f9011`, live since `a8234b3`): `RandomTicks.tick()` reads `SPAWN_MOBS` every tick and, when it is true, raises an `Unanswered` owned by G44 at vanilla's natural-spawn position (once per tick, only with an eligible spawning chunk) and at the custom-spawner position after block ticking, gated by `RandomTickSpawnDrawsCheck` (16 checks, 5 scenarios, 5 mutants caught). The divergence is now loud, not fixed: the draws themselves stay G44. The gate cannot cover the divergence: `RandomTickParityCheck` with `SPAWN_MOBS` true fails with an NPE at `ServerLevel.canSpawnEntitiesInChunk` (`ServerLevel.java:1847`) on the arena's null `entityManager`. |
| G31 | Game modes | 3 | not started | G17, G22 | Survival, creative, adventure, spectator: abilities (flight, instant break, invulnerability), spectator camera, persisted per player. |
| G66 | Block updates | 5 | done | S14 | Vanilla `Level.setBlock` neighbour shape updates on the live write path for every block, not just redstone: fence, wall and pane connections, torches, plants, snow and doors that lose their support, stair shapes. Then falling sand and gravel (the `FallingBlock` tick and the falling block entity). Acceptance included `PostProcessParityCheck` widened to z 31. Slice 1 landed 2026-09-27: one vanilla `Level.setBlock` transcription and one update stack for every live write, gated by `BlockUpdateParityCheck` against vanilla's real `Level.setBlock` (29 scenarios). Slices 2 and 2b landed 2026-09-27, which completes the scope: the falling block entity on the live path, with a shared `Entity.move` for items and falling blocks, vanilla `fallOn`/`stepOn`/`falling` dispatch and level-event delivery, gated by `FallingBlockParityCheck` (36 live-path scenarios, 289,240 checks, 15 mutants). |
| G67 | Sounds and entity events | 4 | not started | S14 | Server-sent sounds (`Level.playSound`, `playSeededSound` and entity sounds) as `ClientboundSoundPacket` and `ClientboundSoundEntityPacket`, with vanilla's ranges and source-player exclusion, drawing the level-random pitch values vanilla draws (skipped today, for example the piston sound, `PistonLogic.java:37-40`). `broadcastEntityEvent` as `ClientboundEntityEventPacket` (for example event 54 from `HoneyBlock.fallOn`). Today Kestrel sends no sound and no entity-event packet: pistons, doors, levers, buttons, fluids and honey are silent. Level events (`ClientboundLevelEventPacket`) are not part of this item: they landed with G66 slice 2b. |
| G68 | Entities inside blocks | 4 | not started | S14 | `Entity.applyEffectsFromBlocks` and `checkInsideBlocks` for every non-player Kestrel entity (items and falling blocks today, mobs later): `entityInside` for cobwebs, powder snow, sweet berry bushes, bubble columns, honey, fire and lava. Pressure plates and tripwire are G12, portals are G43, players are G24. Today items and falling blocks pass through all of them untouched. |
| G69 | Block event packets | 3 | not started | G12 | `ServerLevel.runBlockEvents` (`ServerLevel.java:1256-1278`) sends a `ClientboundBlockEventPacket` to every player within 64 blocks for each queued event whose `triggerEvent` returned true. Kestrel queues and drains the events (`VanillaRedstone.blockEvent` and `runBlockEvents`, gated by `RedstoneParityCheck`) but no code builds that packet, so a client never receives the event a piston or a note block raises; `BlockLogic.triggerEvent` also handles pistons only. Found while building G18 slice 4, when a hit note block queued its event and nothing could send it. Needs `triggerEvent` for the other event blocks (note block, chest lid, bell, and the rest), the packet with vanilla's 64-block range, and for the note block's sound G67. |

### Phase B: running it as a real server (M1)

Everything a vanilla dedicated server gives its owner, with the same files and
formats, so an owner can drop Kestrel in without learning anything new.

| # | Item | Size | Status | Depends on | Scope |
|---|---|---|---|---|---|
| S32 | server.properties | 4 | partial | none | The file is real and vanilla-exact: created through vanilla's own `DedicatedServerSettings` when missing (69 keys, defaults diffed against an independent vanilla write), comments and unknown keys preserved across a rewrite, gated by `ServerPropertiesCheck`. Command line arguments still override the file where Kestrel exposes them. Honoured today: port, online-mode, motd, max-players, view-distance, simulation-distance, allow-flight, spawn-protection, entity-broadcast-range-percentage, and pause-when-empty-seconds. The pause uses vanilla's default 60 seconds, zero disables it, and the paused path continues connection processing while world, clock, weather, entity, block-entity, and scheduled-tick phases stop, gated by `PauseWhenEmptyCheck`. The remaining keys parse and round-trip but are not yet honoured, so the file is exact while the behaviour behind several of its keys is not. |
| S33 | Vanilla world files | 5 | not started | none | Real `level.dat` and `playerdata/*.dat` NBT (inventory, health, food, XP, gamemode, spawn point, effects), `stats/` and `advancements/` folders, so worlds move between Kestrel and vanilla. Finishes S4. |
| S34 | Server list ping | 2 | done | S32 | Status response (MOTD, version, player count and sample, favicon, enforcesSecureChat) and legacy ping (0xFE, 0xFE 0x01, 1.6 custom payload) matching vanilla 26.2. Gated by `StatusPingCheck`. |
| S35 | Command framework | 6 | not started | none | Brigadier dispatcher, command tree packet (client-side autocomplete and red-underline errors), suggestions, argument types, selectors (`@p @a @s @e @r`), feedback messages, console as a command source. The specification is written (130 citations checked against the vanilla source), with a build order of independently gated slices; this item unblocks S36 to S42. |
| S36 | Operators and permissions | 4 | not started | S35 | `ops.json`, permission levels 0 to 4, `op-permission-level`, per-command level checks, op status sent to the client (F3 shortcuts, command blocks). Spawn protection already reads an ops set (`NetServer.ops`, empty until this lands), so filling it from `ops.json` turns spawn protection on as in vanilla. |
| S37 | Whitelist and bans | 3 | not started | S35 | `whitelist.json`, `banned-players.json`, `banned-ips.json`, kick and ban messages, enforce-whitelist kicking online players. |
| S38 | Admin commands | 5 | not started | S36 | `stop`, `save-all`, `save-on`, `save-off`, `list`, `kick`, `ban`, `ban-ip`, `pardon`, `op`, `deop`, `whitelist`, `say`, `msg`/`tell`/`w`, `me`, `tp`/`teleport`, `gamemode`, `defaultgamemode`, `difficulty`, `time`, `weather`, `give`, `clear`, `kill`, `seed`, `spawnpoint`, `setworldspawn`, `gamerule`. |
| S39 | Game rules | 4 | not started | S35, S33 | Full 26.2 game rule registry, persisted in level.dat, each rule wired to its mechanic (keepInventory, doDaylightCycle, mobGriefing, randomTickSpeed, and so on). Vanilla's `GameRules` object is already real on the live path (G30 reads `randomTickSpeed` from it and defaults `spawn_mobs` to true), but nothing persists it and no command writes it. |
| S40 | Console, logging, lifecycle | 3 | not started | S35 | Interactive console with every command, rotating log files, clean stop and restart, save on stop, autosave interval, watchdog (`max-tick-time`), crash reports. |
| S41 | Secure chat and moderation | 4 | not started | S35 | Signed chat and `enforce-secure-profile`, chat types, player reporting compatibility, chat and packet spam kicks, idle timeout. |
| S42 | RCON and query | 2 | not started | S35 | RCON remote console and the GameSpy query protocol, both off by default like vanilla. |
| S43 | Empty-level block-entity gate | 1 | done | none | Vanilla runs the block-entity pass inside the same `emptyTime < 300` guard as the entity pass (`ServerLevel:419-453`), so a level with no active tickets for 300 ticks stops ticking its block entities entirely. Kestrel moved the sleeper and moving-piston passes into the `emptyTime < 300` guard, following `entityWorld.tickEntities()` and `afterEntityTick.run()`, in exact vanilla order. Re-derived `emptyTime` counter against `ServerLevel:409-417` with `resetEmptyTime()`. Gated by `EmptyLevelBlockEntityCheck` (7 scenarios, 6,280 assertions, 5 mutants). |

### Phase C: the whole game (M2)

| # | Item | Size | Status | Depends on | Scope |
|---|---|---|---|---|---|
| G43 | Nether and End travel | 5 | not started | G10, S14 | Portal lighting and frames, 8:1 coordinate scaling, portal search and creation, End portal frames and eyes of ender, End gateways, dimension switching for players and entities. |
| G44 | Mob spawning | 6 | not started | S14, S6 | Natural spawning with vanilla caps (component proof exists), despawning, spawn rules per biome, light-level checks. Owns the level-random draws G30 slice 2 currently reports as unanswered. |
| G45 | Mob AI and pathfinding | 9 | not started | S14, G24 | Goal selector and brain systems, pathfinding and navigation, senses (component proofs exist). |
| G46 | Mobs, batch by batch | 10 | not started | G45 | Every mob's behaviour, done in batches: farm animals (breeding, food), hostile Overworld mobs, Nether mobs, water mobs, tamables, golems, then the rest. |
| G47 | Worldgen handoff completion | 5 | not started | S14, G28 | Keep structure chest loot tables, spawners, structure mobs and other block-entity data from generation (today dropped), and worldgen fluid springs. |
| G48 | Villagers | 7 | not started | G46 | Professions, workstations and POI (component proof exists), trading GUI, restocking, gossip, breeding, zombie villagers. |
| G49 | Raids | 5 | not started | G48 | Bad Omen, raid waves, captains, Hero of the Village. |
| G50 | Thunder and lightning | 3 | not started | S14 | Thunderstorms, lightning strikes, fire, charged creepers, skeleton horses, lightning rods. `ServerLevel.tickThunder` (`:541`) is the site, and G30 slice 1 already models its draw gate. |
| G51 | Status effects and potions | 5 | not started | G22 | All mob effects, beacons, conduits, brewing stands, potions, splash and lingering, tipped arrows. |
| G52 | Enchanting and anvils | 5 | not started | G17 | Enchanting table and bookshelves, enchantment effects, anvils (repair, rename, combine), grindstone disenchanting. |
| G53 | Projectiles | 5 | not started | S14, G22 | Bows, crossbows, tridents, snowballs, eggs, ender pearls, fire charges, fireworks, fishing rods. |
| G54 | Vehicles and riding | 5 | not started | S14 | Boats, minecarts and rails (powered, detector, activator), horses, pigs and striders, saddles, leads. |
| G55 | Explosions and fire | 4 | not started | S14 | TNT, creepers, beds and respawn anchors in the wrong dimension, end crystals, block destruction and drops, fire spread and burnout. |
| G56 | Decoration entities | 3 | not started | S14 | Item frames, glow item frames, armour stands, paintings, maps and map items. |
| G57 | Bosses | 7 | not started | G46 | Ender dragon fight and respawn, wither, elder guardians, the warden, trial chambers and vaults. |
| G58 | Advancements and statistics | 4 | not started | S33 | Advancement triggers, toasts, recipe unlocks, statistics screen. |
| S59 | World commands | 6 | not started | S35 | `setblock`, `fill`, `clone`, `summon`, `effect`, `enchant`, `xp`, `locate`, `worldborder`, `tick`, `execute`, `data`, `scoreboard`, `team`, `title`, `bossbar`, `function`, `schedule`, `reload`, and the rest of the vanilla command list. |
| S60 | Datapacks and resource packs | 5 | not started | S35 | Load, enable and disable datapacks (functions, loot tables, recipes, tags, worldgen), `/reload`, server resource pack prompt. |

### Phase D: launch (M3)

| # | Item | Size | Status | Depends on | Scope |
|---|---|---|---|---|---|
| S61 | Vanilla world compatibility | 5 | not started | S33 | Open a world made by vanilla 26.2 and play it (full chunk, entity and block-entity data), and the reverse. |
| S62 | Full-game performance gates | 6 | not started | M2 | Re-prove the speed promise with mobs, redstone and farms running: MSPT, memory, many players, soak tests. |
| S63 | Parallel ticking | 9 | not started | S62 | From the README engine plan. |
| S64 | Plugin API | 8 | not started | M2 | Kestrel-native, zero cost when unused (`docs/VISION.md`). |
| S65 | Distribution | 3 | partial | none | Slice 1, releases, landed 2026-09-28: `.github/workflows/release.yml` builds a server zip on every push to main that can change the binary, ignores docs-only pushes, runs the same fast check set as `gates.yml` as a precondition, and attaches the zip to a rolling release tagged `latest`, recreated each time so the tag points at the commit whose zip is attached. The zip carries Kestrel's compiled classes plus the launcher and setup scripts in `dist/`; the first run downloads the pinned Temurin 25 JDK and Mojang's pinned 26.2 server jar and verifies both against hardcoded checksums, because neither may be redistributed. A `BUILD-COMMIT.txt` stamp beside the classpath root keeps the startup banner naming its commit when there is no `.git` directory to walk to (`KestrelServer.readBuildStamp`). Still owed: server-owner documentation beyond `dist/README.txt`, a versioned release rather than a rolling one, and Modrinth, which remains a post-launch goal behind the plugin API (S64, M2). |

---

## Suggested order

1. G12's secondary components, G66 and G10 are done or queued behind their
   dependencies, so the live frontier is the player: S14 slices 4 to 6, then
   G15, G17 to G22. S14 is in; its remaining slices are region-file
   persistence, the chunk visibility lifecycle and the stationary mob shell.
2. G17 slices 4 to 6 and G18 to G22 in parallel where they do not share files.
   This is the step that turns Kestrel from "walk around a world" into
   survival: block drops and placing the held item are what make items exist.
3. Phase B can run in parallel with Phase A from the start: S32 to S42 touch
   networking and config, not the simulation. S34 is done, S32 is partly done
   with its unhonoured keys listed in its row, and S35 is the one that
   unblocks S36 to S42.
4. G24 (player physics) before an M1 playtest with real players, because the
   server does not simulate collision today.
5. Phase C in dependency order, mobs last because they are the largest, and
   G44 before the mob batches so that random ticks stop diverging from vanilla.

Last audited against the code and the gates: 2026-10-01, main `a8234b3` (G30 row only; the rest as of 2026-09-28, main `919ce0d`).

