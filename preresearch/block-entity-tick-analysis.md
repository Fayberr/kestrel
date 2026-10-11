---
title: Step 5 grounding - block-entity tick sleeping
status: grounding plus legs 1-3 and ten-type generalization implemented, live-loop differential owed
source: decompiled vanilla 26.2 (Vineflower over the nested server-26.2.jar)
date: 2026-09-11
---

# Step 5 grounding: block-entity tick sleeping

Written before any implementation, per the project's standing rule. Every
claim below is read off the real 26.2 decompile; the prior-art claims are
read off Lithium's actual source, not its changelog. Hypotheses are marked
as such.

**The premise check, sixth time running: partially wrong again.** The
step was written as "vanilla has no sleeping mechanism, add one". That is
false. Vanilla has a real, complete sleeping mechanism, it is just
**block-state granular** instead of data granular, and on a server only
three types use it (see "Vanilla already sleeps" below). What is left for
step 5 is the data-granular case: block entities whose ticker is non-null
but whose tick is a provable no-op given their current contents. That is
still a real and large set, so the step survives - at a corrected size, and
with a parity constraint that the vanilla mechanism hands us for free
rather than one we had to guess.

The open question is therefore not "is there work to skip" but "can it be
skipped *exactly*" - and the answer turns entirely on tick ORDER.

## The real tick path

`Level.tickBlockEntities()` (`Level.java:534`):

> Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.

Facts that matter:

- `blockEntityTickers` is a flat `ArrayList` over the **whole level**
  (`Level.java:109`), not per-chunk. Every loaded ticking block entity in
  the dimension is visited every tick, unconditionally.
- Iteration order is list order, and list order is **insertion order**:
  `addBlockEntityTicker` appends (`Level.java:531`), to `blockEntityTickers`
  or, if we are mid-walk, to `pendingBlockEntityTickers` which is appended
  wholesale at the start of the next tick. So the order is a stable
  historical artifact of chunk-load and placement sequence.
- Removal is `iterator.remove()` on an `ArrayList`, i.e. O(n) `arraycopy`
  per removed element.
- There is **no dirty flag and no per-chunk bucketing** anywhere in this
  loop. The only skips are `isRemoved` and the `shouldTickBlocksAt` ticket
  check. Note that `isRemoved` is not only "the block entity was
  destroyed": it is also how vanilla's own sleeping mechanism retires an
  entry, see the next section.

### The per-block-entity preamble

`ticker.tick()` goes through `RebindableTickingBlockEntityWrapper` (a bare
delegate) into `BoundTickingBlockEntity.tick()`, which runs this *before*
the block entity's own logic:

1. `blockEntity.isRemoved()` - second `isRemoved` call this tick, the loop
   already did one.
2. `blockEntity.hasLevel()`
3. `blockEntity.getBlockPos()`
4. `LevelChunk.this.isTicking(pos)` - **second** ticking check this tick,
   duplicating the loop's `shouldTickBlocksAt`.
5. `Profiler.get()` + `profiler.push(this::getType)` - a bound method
   reference, per block entity per tick.
6. `LevelChunk.this.getBlockState(pos)` - a full section + palette lookup,
   per block entity per tick.
7. `blockEntity.getType().isValid(blockState)` - validity predicate.
8. finally `ticker.tick(level, pos, blockState, blockEntity)`.
9. `profiler.pop()`.

So an idle furnace costs the whole of 1-9 plus its own (short) body. **The
preamble, not the tick body, is the bulk of the idle cost** - which is why
this step is worth doing even though several individual tick bodies are
already cheap when idle.

## Vanilla already sleeps, at block-state granularity

`LevelChunk.updateBlockEntityTicker` (`LevelChunk.java:712`) is re-run on
every `setBlockState` that keeps the existing block entity
(`LevelChunk.java:347`), on registration (`:405`, `:699`), and it re-asks
the block for a ticker each time:

> Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.

And `removeBlockEntityTicker` (`LevelChunk.java:505`) drops the wrapper from
`tickersInLevel` and rebinds it to the shared `NULL_TICKER`
(`LevelChunk.java:70`) whose `isRemoved()` returns **true**. So on the very
next `tickBlockEntities` pass the loop's `if (ticker.isRemoved())` branch
fires and `iterator.remove()` takes it out of `blockEntityTickers`.

That is a complete sleep: the entry genuinely leaves the walked list. This
is strictly better than what Lithium does (Lithium keeps the entry and
no-ops it), and it is the shape step 5 wants - vanilla just only triggers
it on block-state change.

Server-side, exactly three types use it:

| Type | Sleeps when |
|---|---|
| `JukeboxBlock` | `HAS_RECORD` is false, i.e. empty jukebox |
| `CreakingHeartBlock` | `STATE == UPROOTED` |
| `DaylightDetectorBlock` | `!level.dimensionType().hasSkyLight()` (permanent per dimension) |

`CampfireBlock` looks like a fourth but is not: server-side it swaps
`cookTick` (LIT) for `cooldownTick` (unlit) and never returns null.

### Two consequences, one of them load-bearing for parity

1. **The wrapper indirection exists precisely so tickers can be swapped at
   runtime.** `RebindableTickingBlockEntityWrapper` has exactly one method
   beyond the delegate: `rebind`. Kestrel's sleeping should use this hook
   rather than inventing a parallel one - it is the mechanism vanilla
   itself sleeps through.

2. **Vanilla's own sleep/wake does NOT preserve ordinal position.** Waking
   goes through `compute` with `existingTicker == null` (the map entry was
   removed), so it constructs a *fresh* wrapper and calls
   `level.addBlockEntityTicker(result)`, which **appends**. Put a record
   into a jukebox that has been empty since chunk load and that jukebox
   moves to the back of `blockEntityTickers` permanently.

   This is not a bug to fix, it is behavior to reproduce. It also splits
   the parity rule cleanly in two:

   - For the three state-null types above, Kestrel must sleep and wake
     with vanilla's semantics, **append included**.
   - For any sleep Kestrel *adds* (the data-granular ones), vanilla never
     removed the entry, so Kestrel must restore the **original ordinal
     position** on wake. Appending there would be a divergence.

   The naive "one uniform sleep mechanism for everything" design gets this
   wrong in one direction or the other. Noting it here so the
   implementation does not have to rediscover it.

## What is tickable at all

32 block classes declare a ticker; 30 go through `createTickerHelper`:

`AbstractFurnaceBlock`, `AbstractSkullBlock`, `BaseEntityBlock`,
`BeaconBlock`, `BeehiveBlock`, `BellBlock`, `BrewingStandBlock`,
`CampfireBlock`, `ChestBlock`, `ConduitBlock`, `CreakingHeartBlock`,
`DaylightDetectorBlock`, `CalibratedSculkSensorBlock`, `EndGatewayBlock`,
`CeilingHangingSignBlock`, `EnderChestBlock`, `JukeboxBlock`,
`HopperBlock`, `CrafterBlock`, `EnchantingTableBlock`, `SculkSensorBlock`,
`SculkShriekerBlock`, `SignBlock`, `PotentSulfurBlock`,
`SculkCatalystBlock`, `ShulkerBoxBlock`, `SpawnerBlock`,
`TrialSpawnerBlock`, `VaultBlock`, `WallHangingSignBlock`.

### Server-side classification

Read off each `getTicker` body. Only the server column matters for Kestrel.

**Never ticks on a server** (`getTicker` returns null off a client check),
so entirely out of scope:

| Block | Client ticker |
|---|---|
| `ChestBlock` | `ChestBlockEntity::lidAnimateTick` |
| `EnderChestBlock` | `EnderChestBlockEntity::lidAnimateTick` |
| `EnchantingTableBlock` | `EnchantingTableBlockEntity::bookAnimationTick` |
| `AbstractSkullBlock` | `SkullBlockEntity::animation` |

This already trims the prior art: Lithium's nine sleeping types include
**chest animation**, which does not exist on a dedicated server. Lithium is
a client-and-server mod and that entry is a client win. The achievable
server-side set inherited from Lithium is eight, not nine.

**Server-only** (null on the client):

| Block | Server ticker | Note |
|---|---|---|
| `AbstractFurnaceBlock` | `AbstractFurnaceBlockEntity::serverTick` | via `createFurnaceTicker`, `level instanceof ServerLevel`; covers furnace / blast furnace / smoker |
| `HopperBlock` | `HopperBlockEntity::pushItemsTick` | already gated by step 2's `HopperState` |
| `BrewingStandBlock` | `BrewingStandBlockEntity::serverTick` | |
| `BeehiveBlock` | `BeehiveBlockEntity::serverTick` | |
| `CrafterBlock` | `CrafterBlockEntity::serverTick` | |
| `SculkCatalystBlock` | `SculkCatalystBlockEntity::serverTick` | |
| `SculkShriekerBlock` | `SculkShriekerBlockEntity` tick | |
| `SculkSensorBlock`, `CalibratedSculkSensorBlock` | `VibrationSystem.Ticker::tick` | |
| `CreakingHeartBlock` | server **and** `STATE != UPROOTED` | state-gated, see previous section |
| `DaylightDetectorBlock` | server **and** `dimensionType().hasSkyLight()` | dimension-gated, effectively permanent |

**Ticks on both sides**, so the server pays for them too:

| Block | Server behavior |
|---|---|
| `CampfireBlock` | `cookTick` when `LIT`, `cooldownTick` when not; never null |
| `SignBlock` (+ `CeilingHangingSignBlock`, `WallHangingSignBlock`) | `SignBlockEntity::tick`, no side branch at all |
| `BeaconBlock` | `BeaconBlockEntity::tick`, no side branch |
| `ShulkerBoxBlock` | `ShulkerBoxBlockEntity::tick`, no side branch |
| `JukeboxBlock` | `JukeboxBlockEntity::tick`, gated on `HAS_RECORD`, no side branch |
| `BellBlock`, `ConduitBlock`, `EndGatewayBlock`, `PotentSulfurBlock` | side-specific ticker, both non-null |
| `SpawnerBlock`, `TrialSpawnerBlock`, `VaultBlock` | branch on `level instanceof ServerLevel` but return non-null either way |

### Two candidates Lithium does not sleep, with predicates read off the body

Worth calling out because they are cheap and the second is numerically the
most common block entity on most servers.

**`SignBlockEntity.tick`** (`SignBlockEntity.java:265`) is, in full:

```java
UUID playerWhoMayEdit = signBlockEntity.getPlayerWhoMayEdit();
if (playerWhoMayEdit != null) {
   signBlockEntity.clearInvalidPlayerWhoMayEdit(signBlockEntity, level, playerWhoMayEdit);
}
```

Idle predicate: `playerWhoMayEdit == null`. That field is non-null only
between opening a sign for editing and the edit landing or the player
walking out of range. On a built-up server essentially every sign is idle
essentially always, and each one is still paying the full 1-9 preamble
every tick to read a field and compare it to null. Wake set is a single
write site (`setAllowedPlayerEditor`), which is the easiest completeness
proof in the whole list. **Hypothesis, not yet measured:** this is the
largest single win available in step 5, ahead of furnaces.

**`ShulkerBoxBlockEntity.tick`** -> `updateAnimation`. The `CLOSED` branch
is `progressOld = progress; progress = 0.0F;`. Idle predicate:
`animationStatus == CLOSED && progress == 0.0F && progressOld == 0.0F`,
which makes the tick a provable no-op write of identical values. The
predicate correctly excludes the one settling tick right after `CLOSING`
completes (that tick still has to zero `progressOld`). Wake set is
`animationStatus` transitions, driven by the container opener counter.

### Idle predicates for the rest of the server-only set

Each read off the tick body, stated as the exact condition under which the
whole body is a provable no-op. Deliberately conservative: where a broader
predicate exists but needs an expensive check (a recipe lookup) to
evaluate, the cheap sufficient condition is given instead and the gap is
noted.

**`AbstractFurnaceBlockEntity.serverTick`** (`:139`)

```
litTimeRemaining == 0 && cookingTimer == 0
                      && (items[0].isEmpty() || items[1].isEmpty())
```

Derivation: `litTimeRemaining == 0` skips the decrement and forces
`wasLit == isLit == false`, so the `setBlock(LIT)` tail cannot fire; an
empty input or fuel slot makes `isLit || (hasFuel && hasIngredient)` false;
`cookingTimer == 0` then kills the `else if` branch; `changed` stays false
so no `setChanged`. **Gap:** a furnace with both slots filled but no
matching recipe is also a no-op, but proving that costs the
`quickCheck.getRecipeFor` call this predicate exists to avoid. Not worth
claiming.

**`BrewingStandBlockEntity.serverTick`** (`:97`)

```
brewTime == 0
  && !(fuel <= 0 && items[4].is(ItemTags.BREWING_FUEL))
  && !isBrewable(potionBrewing, items)
  && Arrays.equals(getPotionBits(), lastPotionCount)
```

The last clause is the awkward one: it is the only type in this set whose
no-op condition is itself derived state rather than a field. Separate
observation, independent of sleeping: `getPotionBits()` **allocates a
`boolean[3]` every tick per brewing stand** purely to `Arrays.equals` it
against `lastPotionCount`. Packing those three bits into an `int` field is
a pure data-layout change with no observable difference, which the parity
contract explicitly permits. Worth doing whether or not step 5 lands.

**`CampfireBlockEntity`** - two tickers, two predicates:

- `cookTick` (LIT): all four `items` slots empty. The loop then never sets
  `changed`.
- `cooldownTick` (unlit): every `cookingProgress[slot] == 0`.

An empty lit campfire is the common decorative case and is fully idle.

**`BeehiveBlockEntity.serverTick`** (`:284`): `stored.isEmpty()`. See the
RNG note below, which this type is the reason for.

**`CrafterBlockEntity.serverTick`** (`:237`): `craftingTicksRemaining <= 0`.
The cleanest of the set - the entire body is a decrement guarded by that
one field, and the field has exactly one external write site
(`setCraftingTicksRemaining`). A crafter is idle except for the few ticks
of post-craft animation, which is to say approximately always.

**`SculkCatalystBlockEntity.serverTick`** (`:37`):
`catalystListener.getSculkSpreader().cursors.isEmpty()`, because
`SculkSpreader.updateCursors` (`:141`) opens with `if
(!this.cursors.isEmpty())` and does nothing otherwise.

**`SculkSensorBlock` / `CalibratedSculkSensorBlock` / `SculkShriekerBlock`**
all dispatch `VibrationSystem.Ticker.tick` (`VibrationSystem.java:279`).
Idle predicate: `data.currentVibration == null` **and** the selection
strategy holds no candidate. Candidates only enter via `addCandidate` from
the game-event listener, so no event means idle forever.

**This is the one type in the set whose wake trigger is not `setChanged()`
but the game-event listener.** It is the concrete instance of the warning
in open item 4: a `setChanged`-only wake set silently breaks sculk. Recorded
here so it cannot be lost.

### RNG discipline: sleeping must not shift the shared draw sequence

A skipped tick that would have drawn from `level.getRandom()` does not just
skip work, it renumbers every later draw in the level that tick. Under this
project's parity contract that is a total failure, not a rounding error. So
every idle predicate above also has to prove the skipped body draws
nothing. Checked, per type:

| Type | Draws from a shared RNG? |
|---|---|
| Furnace, brewing stand, campfire (both tickers), crafter | no draws at all |
| Sign, shulker box (`CLOSED` branch) | no draws at all |
| Sculk catalyst | `updateCursors` takes `level.getRandom()` but only consumes it inside the `!cursors.isEmpty()` body |
| Sculk sensor / shrieker | no draw on the idle path |
| **Beehive** | **yes** - `level.getRandom().nextDouble() < 0.005` for the ambient work sound |

The beehive is the instructive one, and it happens to be safe for the exact
reason that matters: the draw sits behind `!entity.stored.isEmpty()`, which
is the idle predicate itself. An empty hive draws nothing, so sleeping it is
RNG-neutral; a populated hive draws every tick and therefore must never be
slept. The predicate and the RNG guard being the same condition is luck, not
design, and the next type may not be so tidy. Any type added to the sleep
set later needs this column filled in before it lands.

## Prior art: what Lithium actually does

Read from `mixin/world/block_entity_ticking/sleeping/`. Lithium sleeps
nine types: brewing stand, campfire, chest animation, crafter, furnace,
hopper, sculk catalyst, sculk sensor/shrieker, shulker box.

Mechanism (`SleepingBlockEntity`): it does **not** remove the entry from
`blockEntityTickers`. It keeps the `RebindableTickingBlockEntityWrapper`
and swaps the wrapped ticker for a shared no-op
`SLEEPING_BLOCK_ENTITY_TICKER`. Wake-ups are driven off `setChanged()`
(`BlockEntityMixin` injects at its RETURN).

Two consequences worth stealing and one worth beating:

- The no-op ticker returns `getPos() == null`, purely so the loop's
  `shouldTickBlocksAt` can be short-circuited - which is why Lithium also
  needs a `LevelMixin` that null-guards that call. That is a workaround for
  not owning the loop.
- **Lithium still pays the full list walk** for every sleeping block
  entity: `iterator.next()`, `isRemoved()`, `getPos()`, the null check, and
  a virtual `tick()` into an empty method. Its win is items 1-9 of the
  preamble plus the body, not the walk.
- Kestrel owns its tick loop, so it can do what Lithium structurally
  cannot: **drop sleepers out of the walked sequence entirely.** That is
  the differentiating win available here, and it is exactly where the
  parity risk concentrates.

### Lithium already found a parity trap here, and documented it

`SleepingBlockEntity.sleepOnlyCurrentTick()` carries this comment:

> To achieve correct timings after waking up the block entity with another
> block entity, force skipping the block entity ticking for the current
> tick. This is currently used to make sure hoppers that receive an item
> and thus go into cooldown handle the cooldown correctly with all possible
> ticking orders of hoppers.

Read plainly: when block entity A's tick wakes block entity B, whether B
then ticks *this* tick or *next* tick depends on whether B sits before or
after A in the list. Vanilla's answer falls out of the list position.
A sleeping implementation that re-arms B naively will tick B this tick
when vanilla would not (or vice versa), and the divergence is visible in
hopper cooldown timing. Lithium's fix is to force-skip the remainder of
the current tick on wake.

This is the single most important inherited finding for step 5, and it
generalizes past hoppers to any wake-from-another-BE path.

## The parity crux: order is observable state

Vanilla's tick order for block entities is insertion order in a flat
per-level list. It is not derived from position, chunk, or type - it is
history. The project's parity contract keeps update-order chaos exactly,
so this order is load-bearing observable behavior, not an implementation
detail.

Therefore any sleeping design must satisfy:

1. **Relative order of awake block entities is unchanged.** Removing
   sleepers from the walk must not permute the survivors.
2. **Waking restores the original ordinal position**, not append-at-end -
   *for sleeps Kestrel adds*. A naive "remove on sleep, `add()` on wake"
   moves the block entity to the back of the list and permanently changes
   its tick position relative to its neighbours. For a hopper chain that is
   a directly observable throughput/ordering change. The exception, and it
   is a real one, is the three state-null types where vanilla itself
   removes and re-appends: there, append **is** parity. See "Vanilla
   already sleeps" above.
3. **Wake-during-walk resolves the same way vanilla's list position would**
   (the Lithium trap above).

That points at a stable ordinal key assigned on first insertion, with the
awake set iterated in ordinal order, rather than a plain compacted list.
Concrete data-structure choice is deliberately **not** settled here -
it should be decided against a measurement, not chosen in a design doc.

## Relationship to what already shipped

Step 2 already gave hoppers `HopperState` gating, which is the single
biggest type in this list. Step 5 is therefore (a) the generalization of
that idea to the remaining types and (b) the tick-loop change that turns
gating into an actual walk-size reduction. Note the plan's step 12
("polling-to-event-driven conversion, generalize hopper-sleeping to
everything") is the same idea taken further; step 5 should be built so
step 12 is a continuation, not a rewrite.

## Open items before implementing

1. ~~Enumerate the server-side ticking subset.~~ **Done**, see "Server-side
   classification" above. Two results changed the shape of the step: four
   types never tick on a server at all (one of which Lithium sleeps), and
   signs turned out to be a first-class candidate that Lithium does not
   cover.
2. **Measure the real population.** ~~How many ticking block entities exist
   and what fraction are idle, on a realistic corpus?~~ **Measured
   2026-09-11 on bench PC** (vanilla 26.2 bundler `server.jar`, rebuilt agent
   jar + boot jar with `BlockEntityCensus.class` confirmed inside).
   `BlockEntityCensus.java` (boot jar, TickLogger schema v4) walks
   `Level.blockEntityTickers` per tick on the exit phase and logs per-type
   total/idle counts to `block-entity-census.log`, using the exact item-3
   predicates (hopper counted present-but-never-idle: step 2 owns that
   decision). `harness/corpus/block-entity-census/` parks one idle
   exemplar of every sleep-set type plus busy controls that must NOT
   count idle. Two flat-world runs
   (`run-harness.ps1 -LevelType minecraft:flat`, fresh world each, fixed
   seed 4242424242), summarized with `census-summarize.ps1 -SkipTicks 5`:
   **CENSUS_COMPARE PASS** (1578 ticks compared).

   Per-type idle fractions (idle seen / total seen, -SkipTicks 5 window;
   run1 / run2 identical to one decimal):

   | Type | Seen (run1) | Idle | Idle fraction |
   |---|---|---|---|
   | beehive | 1578 | 1578 | 100.0% |
   | blast furnace | 1578 | 1578 | 100.0% |
   | brewing stand | 3156 | 3156 | 100.0% |
   | campfire | 3156 | 3156 | 100.0% |
   | crafter | 1578 | 1578 | 100.0% |
   | furnace | 3156 | 1578 | 50.0% |
   | hopper | 1578 | 0 | 0.0% (by construction, never idle) |
   | sculk catalyst | 1578 | 1578 | 100.0% |
   | sculk sensor | 1578 | 1578 | 100.0% |
   | sculk shrieker | 1578 | 1578 | 100.0% |
   | shulker box | 1578 | 1578 | 100.0% |
   | sign | 1578 | 1578 | 100.0% |
   | smoker | 1578 | 1578 | 100.0% |
   | **Total** | **25248** | **22092** | **87.5%** |

   Max tickers in one tick: 16 (13 sleep-set types x population: 2
   furnaces, 2 brewing stands, 2 campfires, 1 each of the rest, plus the
   1 hopper; no intrusion: flat world with `generate-structures=false`
   keeps trial chambers, ancient cities and mineshaft spawners out of the
   census).

   Reading the table: the furnace 50.0% is the two controls doing their
   job (1 idle exemplar of 2 = the smelting furnace counts non-idle every
   tick; PRESENT 1578 with ALLIDLE 0 confirms it never flickered idle).
   The brewing-stand 100.0% likewise covers both the idle exemplar and
   the brewing control settling to idle (brewTime runs down, then the
   cheap sufficient prefix holds). Every other sleep-set type is idle on
   every tick it is present, and presence is stable (PRESENT equals tick
   count for all types: nothing flickered or dropped out).

   The "signs dominate" hypothesis as originally framed (signs would be
   the largest single win by population) is **not confirmed by this
   corpus and not refuted either**: the corpus parks exactly one exemplar
   per type by design, so population shares here are construction, not
   measurement. What IS measured: the idle predicates hold steady at
   100% (50% for the furnace pair with its busy control) across ~1580
   ticks on two independent runs with zero tick-by-tick mismatches. The
   step-5 justification therefore rests on the predicate stability, not
   on a population ranking: on a built-up server the ranking comes from
   real-world block-entity counts (signs and furnaces by the thousand),
   where every idle entry pays the full 1-9 preamble every tick. Step 5
   stays justified.

   Caveats, stated plainly: (a) brewing-stand idleness uses the cheap
   sufficient prefix (see item 3), so it over-reports by the bits-changed
   share; the corpus control still settles to idle, bounding that share
   here. (b) The corpus is idle-configurations only; it proves sleepers
   stay asleep, not wake latency or the wake-hook completeness of item 4.
   (c) Bench verdicts transfer across machines, bench timings do not:
   these are predicate fractions, not timings, so they transfer.
3. ~~Write the per-type idle predicates for the server-only set.~~
   **Done**, see "Idle predicates for the rest of the server-only set"
   above, plus the RNG column. The both-sides types were then read too,
   and the verdict is: **none of them sleeps**. Each fails at least one
   admission bar, a different one per type:

   | Type | Why it stays awake |
   |---|---|
   | Spawner | `BaseSpawner.serverTick` decrements a live `spawnDelay` countdown every tick a player is near, and spawns draw RNG (`getOrCreateNextSpawnData`, `delay`). No-data-idle does not exist here; the countdown IS the work. |
   | Trial spawner | `tickServer` runs a state machine (`tickAndGetNext`) plus per-tick mob-liveness sweeps against the entity list. Player-gated, but the gate is "players in range", not data. |
   | Vault | `Server.tick` gates on game time (`stateUpdatingResumesAt`), cycles the display item from the loot table (RNG), and flips dirty flags. |
   | Bell | `!shaking && ticks == 0 && !resonating` is idle, BUT the wake path is `onHit` -> bare `blockEvent` with no `setChanged`, and `nearbyEntities` refreshes on a 60-tick time gate. Sleeps, but the win is one idle bell per village and the wake hook is bespoke. Deferred, not skipped. |
   | Conduit | `tickCount++` every tick unconditionally, plus game-time-gated shape scans and an RNG ambient-sound draw. No idle predicate exists that is cheaper than the tick. |
   | End gateway | `age++` every tick unconditionally; the `setChanged` gate compares `isSpawning()`/`isCoolingDown()` across the increment. A sleeper would have to reproduce the age counter, which is the tick. |
   | Potent sulfur | the DORMANT ticker draws RNG (`nextIntBetweenInclusive`) on the idle path itself, and `STATE` transitions go through `setBlock`. Sleeping it would shift the geyser draw sequence. |

   So the sleep set is closed at ten types: furnace, brewing stand,
   beehive, campfire, crafter, sculk catalyst, sculk sensor, sculk
   shrieker, sign, shulker box. Hoppers are already gated by step 2.
   Bell is the only plausible eleventh; it stays deferred until the ten
   are proven.
4. ~~Decide the wake-set.~~ **Done.** Every mutation path of every
   slept type was traced in the 26.2 decompile, and the inherited
   `setChanged()`-only trigger is **not sufficient**. Three types have
   wake paths that never call `setChanged()` on the slept block entity;
   the wake hook has to observe them too. Per type:

   | Type | Wake paths that call `setChanged()` | Paths that do NOT |
   |---|---|---|
   | Furnace (x3) | own tick (`changed`), `setItem` slot 0 with a new item, any container `setItem`/`removeItem` (base class) | fuel-only insert into a cold furnace: no `setChanged`, no `cookingTotalTime` refresh, no tick-driven wake later. The furnace stays asleep with fuel sitting in it. |
   | Brewing stand | own tick, base `setItem`/`removeItem`, `HAS_BOTTLE` `setBlock` | none found |
   | Campfire | own ticks (`changed`), `placeFood` (`markUpdated`) | `clearContent` (bare `items.clear()`, called by `dowse`), empty-slot `setBlock(LIT)` swaps; both observable, neither funnels through a container mutation |
   | Beehive | own tick (`changed` on release), `addOccupant` (`super.setChanged()`), `releaseAllOccupants` (only when something spawned) | `storeBee` (called from chunk load and from item-form NBT restore) appends to `stored` silently; tick-driven `BeeData.tick()` countdowns can also mature while asleep |
   | Crafter | any container `setItem`/`removeItem` (inherited, both call `setChanged()`) | the wake itself: `dispenseFrom` sets `craftingTicksRemaining = 6` and flips `CRAFTING` via `setBlock` directly, never `setChanged()` |
   | Sculk catalyst | none at all | the only writer is `CatalystListener.handleGameEvent` (`addCursors`); it also `setBlock(PULSE)` directly |
   | Sculk sensor / shrieker | `onDataChanged` -> `setChanged()` (fires while a vibration is in flight) | the arrival itself: `scheduleVibration` (`addCandidate`) runs in the game-event listener, before any tick |
   | Sign | `markUpdated` (wax, text), any container path | `setAllowedPlayerEditor` is a bare field write: **neither** opening a sign for edit **nor** the edit landing calls `setChanged()` |
   | Shulker box | container `setItem`/`removeItem` (loot-aware chain) | the open/close path: `startOpen`/`stopOpen` only send `blockEvent`; the tick-driving `animationStatus` flips in `triggerEvent`, which no sleeper observes |

   Note the campfire row sharpens the hook placement: its `items` list is
   a plain field, not a `Container` (the block entity implements only
   `Clearable`), so there is no `setItem` to intercept at all. Container
   hooks alone miss it.

   So the wake hook cannot be a `setChanged()` mixin alone. Minimum shape:
   sleep/wake state must be reachable from `setChanged()` AND from
   `setBlock` at the slept position, `Container.setItem`/`removeItem`
   touching it, `triggerEvent` addressed to it, the game-event listener,
   and the block entity's own public mutators (`placeFood`,
   `clearContent`, `addOccupant`, `storeBee`). Hoppers are the precedent
   inside this project already:
   `HopperState` clears on container mutation, neighbor generation bump,
   and `entityInside`, not on any single signal. The same multi-signal
   shape generalizes; a single-signal hook is how you get a missed wake,
   and a missed wake is a silent permanent behavior break, the worst
   failure mode available in this project.

   Two related findings that constrain the design further:

   - **Loot-table unpacking is RNG-drawing and lazy.**
     `RandomizableContainerBlockEntity` overrides `isEmpty`/`getItem`/
     `setItem`/`removeItem` to `unpackLootTable` first, and unpacking
     fills from the loot RNG. So a slept crafter or shulker box whose
     `lootTable` is still set is NOT provably idle: the first touch after
     wake draws RNG and fills slots. The idle predicate must include
     `lootTable == null`, or the sleeper must never apply to un-unpacked
     containers. Which of the two is cheaper is an implementation detail;
     the constraint is not negotiable.
   - **Datapack reload can rewrite recipes under a slept furnace.**
     The furnace idle predicate deliberately excludes the both-slots-full
     case (the gap noted in item 3): it claims idleness only when a slot
     is empty, so no recipe decision is being cached across the sleep. A
     broader predicate that memoized "no matching recipe" would need a
     reload hook as a wake condition. Stating the cheaper option taken, so
     a future widening does not silently inherit this analysis.
5. ~~Settle the ordinal structure.~~ **Done, pending measurement-led
   tuning.** `SleepSet` (`core/.../blockentity/`) is the walked sequence:
   stable ordinal keys assigned on first insertion, sleepers dropped out
   of the walk without permuting survivors, two wake modes in the same
   list (PRESERVE_POSITION restores the ordinal slot for Kestrel's own
   sleeps, APPEND takes a fresh key at the back for vanilla's three
   state-null types), and mid-pass wakes deferred to next tick in either
   mode (the Lithium fix, enforced by a woken-this-pass set).
   `SleepSetCheck` proves it, including a differential of APPEND-wake
   against `TickOrderModel`'s vanilla retire/wake path and a negative
   control showing the modes observably differ (a uniform APPEND hook
   permanently moves a Kestrel sleeper to the back). Dependency-free,
   routed around the vanilla classpath in `run-core-check.ps1` like
   `TickOrderCheck`. What measurement still owns: the compaction
   threshold (currently: reclaim when dead slots exceed half the
   sequence) and whether the linear `indexOf` needs an index. Both are
   tuned against numbers, not chosen here.
6. ~~Reproduce the Lithium timing trap in the harness first, as a failing
   case.~~ **Done.** `core/src/vanilla/java/dev/kestrel/blockentity/`
   holds `TickOrderModel`, an executable transcription of
   `tickBlockEntities` + the `updateBlockEntityTicker` register/retire
   paths, and `TickOrderCheck`, which reproduces the trap as a failing
   case: a naive inline wake fires the downstream hopper at tick 7 versus
   the tick-8 vanilla reference (equivalent to it having sat after its
   waker all along), while the deferred wake matches exactly. The model
   also locks the structural facts the implementation will lean on:
   mid-walk registration defers, retire-during-walk suppresses the
   not-yet-visited but leaves the already-visited as a one-pass zombie,
   and vanilla-style wake appends a fresh wrapper generation. It runs on
   plain `javac` with no vanilla classes, and `run-core-check.ps1` routes
   it around the vanilla classpath accordingly. Caveat, stated plainly:
   no headless `Level` exists, so the model's fidelity argument is
   transcription of short quotable code, not measurement - and the check
   is written to FAIL if a future edit makes naive wake match vanilla,
   i.e. if the transcription ever loses the property the step depends on.

## Implementation status (2026-09-11)

Legs 1-3 plus the ten-type generalization landed in
`core/src/vanilla/java/dev/kestrel/blockentity/` (commits 9b8e1cf,
3660d8a, 2058859): `SleepPredicates` (all ten idle predicates, verified
against a fresh 26.2 decompile with four corrections to item 3 above),
`SleeperRegistry` (PRESERVE-only fan-in, 25 wake signals), `SleeperCheck`,
`SleepLoop` (pass discipline for the future tick loop) plus
`SleepLoopCheck`. All four batteries PASS headless. Census measured on
Bench PC (item 2 table above). Wake-event corpus built and validated
(`harness/corpus/block-entity-sleep/`, single-fire 10/15/20/25s chain
confirmed live). Vanilla baselines secured
(`preresearch/baselines/vanilla-26.2-2026-09-11.md`). Owed: the live-loop
differential once Kestrel has a runnable tick loop (Leg 4).
