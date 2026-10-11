# G10 dimension terrain

## Implemented scope

`HeadlessNoise` now selects a Vanilla `LevelStem`, biome source, noise settings,
dimension type, height, and level key through an explicit dimension parameter.
The existing no-argument entry points still select the Overworld. Nether uses
the Vanilla 0 through 255 build range and disables skylight from its dimension
type. End generation uses the Vanilla End biome source and noise settings, so
the central island and outer island density function come from the server jar.

This work is terrain generation only. It does not add live worlds, portal
travel, player dimension changes, or dimension persistence and routing.

## End features outside terrain generation

The End terrain generator can produce the island terrain and biome decoration
that belongs to the chunk-status pipeline. A complete live End still needs
server-level systems that are deliberately outside this change:

- Obsidian spikes and their regeneration are coordinated by `EndDragonFight`.
  Spike placement also spawns End crystal entities. A terrain-only generator
  has neither the fight state nor a live entity manager, so it cannot provide
  the crystals or fight-owned regeneration semantics.
- Exit portals and the sequence of small return gateways are fight progression
  state. The server must persist which gateways have been awarded and create
  them when dragons are defeated.
- An End gateway block needs its block entity, exit position, and teleport
  behavior. Placing terrain blocks alone is not a working gateway.
- Dragon state, boss-bar state, respawn crystals, fight tickets, and the dragon
  entity belong to the future live multi-world and dragon-fight work.

## Parity harness status

The Vanilla capture tool accepts `--dimension overworld|nether|end` and issues
its force-load commands in that dimension. The replay selects the same
dimension and validates its dynamic build height. The capture also records the
real light executor batch for each INITIALIZE_LIGHT and LIGHT completion.

Block and biome smoke replays are exact for Nether and End. The current strict
Nether light experiment is not a passing gate: a 5 by 5 requested window gives
zero block and biome differences, exact skylight, but 13 of 49 scored chunks
still differ in block-light layers. Per-completion light hashes are racy, while
saved-region hashes can include later server mutations, so the harness now
captures a stable pre-shutdown engine snapshot. That snapshot proves the
remaining difference is the replay of Vanilla's asynchronous light lifecycle.
Do not claim G10 part 1 complete until the executor-batch replay reaches zero
and the large Nether, End, and Overworld gates are rerun from frozen classes.
