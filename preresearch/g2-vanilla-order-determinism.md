# G2: is VANILLA's world generation order-independent? (measured: no)

Control experiment for the cross-order parity question. Follow-up to
`g2-feature-order-notes.md` (which established *how* vanilla picks chunk
order) and to `BatchParityGate`'s cross-order numbers (which measure
Kestrel's stability across two shuffled FEATURES orders).

## 0. Why this was run

BatchParityGate reports Kestrel's cross-order cell stability at 99.6564%,
and that 0.34% shortfall was being read as a Kestrel defect: "Kestrel
disagrees with itself, so it is nondeterministic, so it can never be
block-exact against a fixed vanilla oracle, so order independence is the
first thing to fix."

Every word of that is downstream of an assumption nobody had tested:
**that vanilla scores 100% on the same measurement.** Two things argued
against it:

1. `g2-feature-order-notes.md` section 3 recorded a live 26.2 server
   decorating a bulk-loaded area across three `Worker-Main` threads, with
   the merged order set by thread scheduling. Vanilla's own chunk order is
   not fixed between runs.
2. The code whose order sensitivity BatchParityGate measures is not a
   Kestrel reimplementation of feature placement. `HeadlessNoise.features`
   calls vanilla's real `PlacedFeature.placeWithBiomeCheck` with vanilla's
   real `WorldgenRandom.setFeatureSeed`, through vanilla's real
   `NoiseBasedChunkGenerator`. Kestrel supplies the loop order and the
   write view, nothing else. Order sensitivity there is most likely
   vanilla's, not Kestrel's.

## 1. Method

`harness/tools/vanilla-order-determinism.py` boots a throwaway 26.2 server
(fresh working dir, ephemeral port, never an existing world or a live one),
forceloads one chunk square, waits for the region files to stop growing,
saves and stops. `VanillaSelfParityCheck` then diffs two region dirs cell
by cell using the same metrics BatchParityGate reports for Kestrel.

- Seed 4242424242, `level-type=minecraft:normal`, `generate-structures=false`
  (matches how Kestrel generates, so no structure can be blamed for a diff).
- Chunk square [-8, 7] on both axes. 400 chunks reach `minecraft:full`.
- Runs: A and B use one sweep forceload and are procedurally **identical**.
  C requests the same square row by row from +z down to -z. D and E repeat
  sweep vs rows-reverse with ticks frozen.

Order regimes tested: A vs B isolates pure thread-scheduling variation
(nothing about the request differs). A vs C and D vs E add a deliberately
different request order on top.

## 2. Results

| Comparison | order difference | ticks | cell parity | exact chunks |
|---|---|---|---|---|
| A vs B | thread scheduling only | live | 99.8476% | 171/400 = 42.75% |
| A vs C | + rows-reverse request | live | 99.6560% | 49/400 = 12.25% |
| D vs E | + rows-reverse request | frozen | 99.6660% | 58/400 = 14.50% |

**Vanilla run twice with an identical procedure reproduces fewer than half
its chunks byte for byte.** Under a deliberately different order it drops to
roughly one chunk in seven.

Alongside Kestrel's own figures (33x33 grid, seed 4242424242):

| | cell stability |
|---|---|
| Kestrel, two shuffled FEATURES orders | 99.6564% |
| Vanilla, two different orders (D vs E) | 99.6660% |

Kestrel's cross-order instability is vanilla's cross-order instability, to
within 0.01 percentage points. It is not a Kestrel defect and there is
nothing there to fix.

## 3. Confounds checked (both matter, both excluded)

**Partially generated chunks.** Stopping the server flushes every chunk the
generator had in flight. A 16x16 forceload leaves 1,764 chunks on disk of
which only 400 are `minecraft:full`; the rest sit at `structure_starts`,
`biomes`, `carvers` or `initialize_light`. Those legitimately differ between
runs because generation stopped at a different point. VanillaSelfParityCheck
compares only chunks that are `minecraft:full` in **both** dirs.

**Edge spill-in.** A chunk can be `minecraft:full` while a radius-1
neighbour is still at `carvers`, and a feature rooted in that neighbour
spills in only once the neighbour decorates. Chunks near the edge of what a
run generated can differ for reasons that are artifacts of the probe. The
check therefore reports a ladder of nested interiors. The effect does not
weaken toward the middle; the innermost boxes are *worse* (D vs E: 3.13%
exact over [-4,3]^2, 14.50% over the full set). Not an edge artifact.

**Random ticks.** Forceloaded chunks tick with no players online, so a run
that idles ~60s while generation settles also runs ~60s of random ticks off
an unseeded `Level.random`. Two independent arguments rule this out:

1. Freezing ticks barely moves the number (99.6560% live -> 99.6660%
   frozen). If ticks were driving the divergence, freezing them would have
   collapsed it.
2. The **deep band diverges most**, and nothing random-ticks stone into
   coal ore. D vs E by depth: y[-64,0) 99.3644%, y[0,64) 99.6001%,
   y[64,320) 99.7578%. Top mismatch kinds are `sculk <-> deepslate`
   (~11k cells), `stone <-> coal_ore` (~8k), `granite <-> diorite`,
   `andesite <-> granite`. Ore blobs, sculk patches and stone-variant blobs
   spill across chunk borders and their placement reads terrain that has or
   has not been decorated yet.

## 4. Mechanism

Feature *selection* is order-independent by construction: `setFeatureSeed`
derives from the chunk origin, so which features are tried and where is a
pure function of position and seed. What is order-dependent is the *result*,
because placement reads the world. A blob rooted in chunk X that spills into
chunk Y sees a different Y depending on whether Y has decorated yet, and
two blobs that overlap resolve last-writer-wins. Vanilla has no mechanism
that would make this order-independent, and measurably does not achieve it.

## 5. Consequences

- "A given seed produces the same world vanilla would, block for block" is
  not an achievable target, because vanilla does not achieve it against
  itself. There is no canonical world for a seed at the chunk-border
  feature level. **The oracle corpus is one sample from a distribution, not
  ground truth**, and treating a fixed capture as truth overstates any
  implementation's error.
- Kestrel's ~0.56% total gap splits into roughly 0.34% that vanilla also
  has and cannot be removed, and roughly 0.21% that is genuinely Kestrel's.
  Only the second number is worth engineering against.
- A parity target should be stated against vanilla's own run-to-run
  variance, not against a single capture. Chunk-level "exact match" is a
  particularly misleading headline: vanilla scores 42.75% against itself.

## 6. Caveats

- One seed, one 400-chunk area, one version (26.2), one machine. The effect
  is large and consistent across three independent run pairs, but the exact
  percentages are not a universal constant.
- Bulk `forceload` with parallel workers is a harsher order regime than a
  single player walking. It is, however, the regime a pregenerator or a
  busy server actually runs in.
- The cell-parity metric here compares `BlockState` identity (properties
  included) and is directly comparable to BatchParityGate's cell parity.
  The "exact chunk" column is NOT directly comparable to BatchParityGate's
  chunk-hash stability, which also folds in heightmaps.
- The near-exact agreement between Kestrel's 99.6564% and vanilla's
  99.6660% is partly luck: different grid sizes, areas and perturbations.
  The matching order of magnitude is the real signal, not the fourth digit.

## 6b. External corroboration

Asked for independently, because the `stone -> coal_ore` and
`granite -> diorite` rows in the mismatch table look wrong at first reading.
They are not.

`minecraft.wiki/w/Feature`, verbatim:

> When features are generated, they can spill over into neighboring chunks
> that have been previously generated. Thus, a tree at the edge of the
> generated world may be overwritten by a village before the player reaches
> it. It is also theoretically possible for two worlds generated with the
> same seed, from the same version of *Minecraft*, to differ slightly
> depending on the players' travel routes, because the chunk generation
> order may determine which of two conflicting features overwrite or
> suppress the other.

The same page defines a feature as "a natural deposit of ores or other
blocks". That is the missing link for anyone reading the mismatch table:
ores and stone variants are not carved by the noise pass, they are placed
during FEATURES, by the same machinery as trees. So they inherit the same
order sensitivity, which is why they appear in the diff at all.

Two deltas between the wiki and the measurement, both worth keeping:

- The wiki says "theoretically possible". The measurement says routine. Only
  14.50% of chunks survive an order change byte-identical.
- The wiki frames it as an edge-of-world, travel-route curiosity. Section 3's
  nested-interior ladder shows the rate is *higher* in the middle of a fully
  generated box than at its rim, so it is not an edge artifact.

## 6c. Where the divergence sits inside a chunk (border-distance histogram)

Run to test one specific reading of §4: if spill is the mechanism, differing
cells should sit near chunk borders. Border distance is
`min(x, 15-x, z, 15-z)`, so 0 at the rim and 7 at the centre. D vs E, ticks
frozen, all 400 full chunks.

| bd | cells differ | of that band | share of all diffs | uniform share | ratio |
|---|---|---|---|---|---|
| 0 | 32820 | 0.3561% | 24.99% | 23.44% | 1.07x |
| 1 | 27810 | 0.3482% | 21.17% | 20.31% | 1.04x |
| 2 | 23055 | 0.3411% | 17.55% | 17.19% | 1.02x |
| 3 | 17925 | 0.3242% | 13.65% | 14.06% | 0.97x |
| 4 | 13306 | 0.3094% | 10.13% | 10.94% | 0.93x |
| 5 | 9074 | 0.2954% | 6.91% | 7.81% | 0.88x |
| 6 | 5546 | 0.3009% | 4.22% | 4.69% | 0.90x |
| 7 | 1807 | 0.2941% | 1.38% | 1.56% | 0.88x |

**The prediction was wrong and the correction is the useful part.** The
expectation going in was heavy clustering at bd 0 and 1. What came out is
near-uniform: edge(bd 0-1) 1.05x against core(bd 6-7) 0.89x, a spread of
1.18x across the whole chunk.

That does not weaken §4, it sharpens it. Neighbour feature writes reach a
full chunk, radius 1, sixteen blocks. The deepest any cell sits from a border
is seven. **So a chunk has no spill-immune interior**, and a flat histogram is
the shape the mechanism actually predicts. The residual 1.18x tilt is just
how many of the eight neighbours can reach a given cell: more at a corner,
fewer in the middle.

Consequence for gating: there is no sub-chunk safe region to exempt. Any
tolerance has to be stated per chunk, never "interior cells must match".

### Ores specifically

Counting cells that are ore on either side (this is the union, so it is
conditioned on the outcome and is not a clean per-block probability, but it
is the number that answers "does ore move between runs"):

- Ore-involved cells: 212341, which is **0.54% of the volume**.
- Ore-involved cells that disagree: 19154, which is **9.02% of them**, against
  a baseline diff rate of 0.33% for cells in general.
- Ore accounts for **14.58% of all disagreements** off 0.54% of the volume, an
  over-representation of about **27x**.
- Flat across border distance: 9.49% at bd=0, 9.68% at bd=7. Ore placement is
  order-sensitive everywhere in the chunk, not just at the rim.

So the `stone -> coal_ore` row is not a rounding artifact or a tick. Ore is
the single most order-sensitive thing in the world, by a wide margin, which
is exactly what §6b's "ores are features" note predicts once you accept that
ore blobs are placed by the same spill-prone machinery as trees.

## 7. Artifacts

- Tools: `harness/tools/vanilla-order-determinism.py`,
  `core/src/vanilla/java/dev/kestrel/worldgen/VanillaSelfParityCheck.java`.
- Run dirs and diffs: `/tmp/vanilla-order/run{A..E}`, `cmp-*.txt`, `hist-DE.txt`
  (throwaway, not committed).
- Reproduce: see the header comment of the runner; each pair is ~4 minutes.

## 8. Player-driven final-route control (2026-09-20)

The same order sensitivity appears in the exact Step-9 route, not only bulk
forceloads. The route uses seed 4242424242, view distance 2,
(0,0) -> (4,0) -> (4,4), with 65 scored chunks.

- Two completely uninstrumented Vanilla 26.2 runs with the same build, seed
  and route differed in 57/65 scored block payloads.
- ActiveProcessorCount=2 did not create a canonical Vanilla world. After
  clean shutdown, the persisted Anvil worlds differed in 53/65 scored
  chunks; all 65 existed in both.
- This excludes javaagent instrumentation, packet timing and an
  intermediate-send artifact as the cause.
- Two fresh Kestrel async runs of the same route were exact in 65/65 chunks
  for blocks, biome wire and light wire, with 65/65 light coverage. Only
  transport resend counts varied.

The final gate therefore keeps zero-tolerance exactness on real invariants:
strict matched-order Tier R for Vanilla worldgen semantics, exact Kestrel
fresh-boot live-route repeatability for block/biome/light network payloads,
and AsyncLiveOrderParityCheck for identical admitted center order. It does
not copy Vanilla answers or accept a diff tolerance.
