# Conflict-Rate Instrumentation Experiment: Design Spec

Status: DESIGN ONLY. Do not build the server. Build only the measurement harness described here.
Target implementer: whoever picks this up the day JDK 25 lands on the bench PC.
Plan context: mc-light step 8 (dependency-parallel ticking, Mica pattern:
snapshot-compute-merge-converge). This experiment produces the single number
that decides whether step 8 ships, ships conditional, or dies.

Hardware: Bench PC, Ryzen 7 5700X3D (8C/16T), 64 GB RAM, JDK 25.
Worker count for all projections: T = 8.

---

## 1. What exactly to measure

### 1.1 Unit of parallelism

Two granularities are recorded; analysis aggregates both from one dataset.

- **Operation (op):** one schedulable unit of tick work: a single random block
  tick, a single entity tick, a single scheduled block/fluid tick, one block-entity
  tick. This is the granularity Mica dispatches and re-runs.
- **Chunk-task:** all ops owned by one chunk in one tick phase. This is the
  coarsest dispatch granularity our server might use.

Every op has exactly one **owner chunk**: the chunk whose tick list produced it
(random/scheduled/block-entity ticks), or the chunk containing the entity's
position at tick start (entity ticks).

### 1.2 Access classes

For each op, log every world access as a tuple:

`(tick, op_id, op_type, owner_chunk, pos, kind)` where `kind` is READ or WRITE.

- READ: `getBlockState`, `getFluidState`, `getBlockEntity`, entity AABB/position
  query resolving against a block or entity outside the op's own position.
- WRITE: `setBlockState` (including same-value writes, flagged, see 1.4),
  entity position/velocity mutation, block-entity creation/removal,
  `scheduleTick` calls (logged as WRITE to the tick queue keyed by target pos).

An access is **cross-chunk** when the accessed position's chunk differs from the
owner chunk, or when it touches shared non-positional state (tick queue,
entity lookup maps, POI sets). Cross-chunk accesses are the only ones that can
conflict under chunk-task dispatch; same-chunk accesses are logged for the
op-granularity analysis.

### 1.3 Conflict definition (order-sensitive, value-aware)

Vanilla tick execution has a deterministic serial order O (sorted tick list,
chunk row-major, entity index order). A **conflict** is a pair of ops (A, B)
from different dispatch units whose parallel execution can produce a result
differing from serial execution under O. Three classes:

1. **WW (write-write):** A and B both WRITE the same block position, or mutate
   the same entity, or enqueue scheduled ticks for the same target with
   different delays. Exception: benign same-value writes, see 1.4.
2. **RW (read-write, stale read):** A READs a position (or entity field, or
   queue entry) from the tick-start snapshot that B WRITEs, and A executes
   after B in O. A computed from a stale value. (If A precedes B in O there is
   no anomaly: snapshot value equals the value A would have seen serially.
   Direction relative to O matters. Log O-sequence numbers per op so analysis
   can check this.)
3. **Cascade-touch:** A triggers a neighbor-update cascade (`neighborChanged`,
   `updateNeighborsAt`, redstone propagation, fluid spread checks) that READs
   position P during the cascade even if P's value is unchanged, and B READs P.
   P was causally influenced by A's write, so B's read is order-sensitive even
   though no second write exists. Mica tracks these as "touched positions" and
   the experiment must log them identically: any READ performed while
   executing inside a neighbor-notification call stack is tagged `cascade=1`.

### 1.4 Benign exclusions (logged, counted separately, never silently dropped)

- Same-value writes (state_before == state_after): not conflicts, counted as
  `benign_samevalue`.
- Idempotent flag sets converging to one value regardless of order: counted as
  `benign_convergent`, require manual whitelist per block type, default to
  conflict until whitelisted.
- Packet sends, render dirty-marking, profiler, advancement triggers:
  side effects, not world state. Excluded from conflict math, logged as
  `side_effect` for the deferral-cost estimate only.

Exclusions do not invalidate the result because every excluded access remains
in the raw log with its tag; the analyzer reports rates with and without
exclusions, and the GO decision (section 4) uses the with-exclusions number
only if the exclusion-tagged share is below 5% of accesses.

### 1.5 The three metrics (distinct, do not conflate)

- **Conflict rate:** conflicting op-pairs per 1,000 ops per tick, broken down
  WW / RW / cascade-touch. Diagnostic: tells you *why* work interacts.
- **Taint rate f:** fraction of ops per tick that SCMC would re-run serially =
  conflicted ops PLUS transitively provenance-tainted ops (walk the
  read/write graph from each conflicted op; everything reachable is tainted,
  exactly as Mica's `OverlayMerger` + provenance walk does). THIS is the
  decision metric. Report mean, p50, p99, max per workload.
- **Abort rate:** fraction of ticks with at least one tainted op (fast path
  fails, merge+regroup+serial re-run pays). Diagnostic guard for section 4.

Taint rate is computed by re-implementing Mica's merge logic offline against
the log (Union-Find grouping included, largest-tainted-component recorded).
The analyzer must reproduce Mica's grouping semantics, not an approximation:
seed conflicts from WW/RW/cascade pairs, transitively close over the
provenance graph, group by connected components.

---

## 2. How to instrument WITHOUT building our server

### 2.1 Options

| Option | What | Verdict |
|---|---|---|
| (a) Fabric mod on vanilla 26.2, read-only logging | Mixin intercept at the same points Mica intercepts, but instead of an overlay, append access records to a lock-free buffer. Zero behavior change. | RECOMMENDED |
| (b) Instrumented Paper/Folia build, offline log analysis | Paper changes tick behavior (optimizer defaults, event order, chunk system patches); Folia regionizes ticking, which is a different execution model entirely. | REJECTED: measures the wrong system. Parity contamination. |
| (c) Static analysis of vanilla tick code paths | Points-to through chunk maps and entity lookups is undecidable in practice; flags every possible cross-chunk access with no frequency data. Cannot separate rare from hot. | REJECTED as primary. Accepted as coverage audit for (a) only. |

### 2.2 Why (a)

- Runs the exact vanilla 26.2 logic our server will embed (26.x ships
  deobfuscated; Fabric Loom needs no mappings, plain `implementation` deps,
  Gradle 9.7+, Java 25 toolchain). Tick order O is bit-identical to the future
  oracle, so conflict pairs found here are conflicts the real step 8 would hit.
- Runs on the bench PC with JDK 25 once installed. No Pi involvement (Pi is the
  truth machine for nightly harness runs later, not for this experiment).
- Cost is one small mod, not a server: estimated 300-500 lines of mixin +
  buffer code. No overlay, no merge, no threading changes. Read-only.

### 2.3 Mixin points (26.2, deobfuscated names; checklist derived from Mica's intercept surface)

| Intercept | Records | Notes |
|---|---|---|
| `LevelChunk.setBlockState` | WRITE (pos, before/after state id) | Same choke point Mica uses; catches all vanilla game logic above it |
| `Level.getBlockState`, `Level.getFluidState`, `Level.getBlockEntity` | READ (pos) | Thread-local current-op must be set at op entry (see below) |
| Neighbor-notification path (`Level.neighborChanged`, `updateNeighborsAt`, redstone `updateNeighborsInFront` and equivalents) | cascade flag on stack | Set a thread-local `inCascade` depth counter on entry/exit; any READ with depth > 0 gets `cascade=1` |
| `LevelTicks.schedule` (block + fluid) | WRITE to queue keyed by target pos | Needed for WW-on-queue detection |
| Entity position/velocity mutation + AABB queries crossing entity boundaries | WRITE / READ | Owner chunk = entity chunk at tick start; chunk moves mid-tick logged as events (section 5) |
| Op entry/exit: chunk tick loop, entity tick entry, scheduled-tick dispatch, block-entity tick entry | op_id, op_type, owner chunk, O-sequence number | Assign O-sequence from a tick-global counter to evaluate RW direction |

Coverage audit (option c, limited role): statically list all call sites of
`setBlockState` / `getBlockState` in the 26.2 jar and verify each is reachable
under at least one mixin above. Any gap is a bug in the mod, fix before
measuring. Undercounting conflicts is the experiment's fatal failure mode;
overcounting is corrected by the benign analysis.

### 2.4 Logging mechanism (observer effect control)

- Lock-free single-writer ring buffer per tick thread (tick is single-threaded
  in vanilla, so one buffer suffices), binary records, background thread drains
  to disk. No locks, no I/O, no allocation on the hot path beyond the append.
- SAMPLING IS FORBIDDEN. Conflicts are rare events; sampling destroys exactly
  the signal being measured.
- Wall-clock timings from instrumented runs are INVALID (logging + buffer
  drain perturb MSPT). Only counts leave this experiment. Wall-clock speedup
  is measured later by the differential harness against the uninstrumented
  oracle. State this in every report header so nobody cites instrumented MSPT.
- Determinism self-check (required before any corpus run): capture 500 ticks
  twice on the same world/seed, hash the access logs. Hashes must match.
  If not, the world is non-deterministic under test (leak: wall-clock RNG,
  async chunk I/O) and the setup is broken. Fix setup, do not proceed.

---

## 3. Workload corpus

All scenarios run as headless dedicated vanilla 26.2 + instrument mod,
fixed `server.properties` (tick distance, view distance recorded in report),
fixed world seed per scenario (actual seed pinned in the report; substitutes
allowed only under the rule at the end of this section).

| # | Scenario | Why it is in the corpus | Setup | Ticks to capture |
|---|---|---|---|---|
| W1 | Idle SMP, 3 AFK players | Noise floor. If THIS conflicts, the dispatch granularity is wrong, not the workload. | Fresh 26.2 world, fixed seed, 3 fake players standing in different chunks, no machines. | 6,000 |
| W2 | Mob farm running | Entity-heavy sparse work: the workload step 8 is most likely to win (QuakeTM: independent + heavy private compute). | Standard flushing/gravity mob farm built from published tutorial, ~200 mobs alive, item collection hoppers active. | 6,000 |
| W3 | Sorter wall (dense hopper chain) | Adversarial. Linear interacting cascade; expected to lose. Tests the KILL threshold and sparse-only fallback. | Impulse sorter array, 64 sorters, continuous item throughput, chunk-aligned. | 6,000 |
| W4 | Redstone computer active | Worst case. Dense interacting cascade with cross-chunk propagation. | Smallest published redstone computer/CPU built in-game (fallback: 4-bit adder, the circuit Mica itself verified; build from schematic by hand). Clock running continuously. | 6,000 |
| W5 | Exploring players | Chunk load/unload pressure + border-crossing movement. | 3 players flying outward in different directions, elytra, pre-generated ring to separate steady flight from generation spikes (log both, report separately). | 6,000 |
| W6 | Village + raid chaos | Dense entity + POI + scheduling interaction (doors, beds, job sites, raid waves). | Generated village, fixed seed, raid triggered on schedule, ~60 villagers + 40 raiders. | 6,000 |

6,000 ticks = 5 minutes each. Total 30 minutes of capture plus setup.

Substitute rule: any scenario may use a well-known technical-server test
world (SciCraft-style downloads, published schematics) INSTEAD of a hand
build, provided: world is opened once in vanilla 26.2 and re-saved (no legacy
upgrade path inside the measurement), SHA-256 of the world directory is
pinned in the report, and the hand-build fallback above stays documented.
Never cite a URL as the source of truth; pin the hash.

---

## 4. Decision thresholds (grounded in the QuakeTM cost model)

### 4.1 Cost model and arithmetic

Per tick, let S_par = serial cost of the parallelizable work. SCMC tick cost:

```
P(f) = snapshot + k*S_par/T + M + f*S_par + G_abort
```

- k in [2, 3]: per-access tracking overhead multiplier (QuakeTM envelope,
  conservative; Mica's own logs show tick work is overwhelmingly read-heavy,
  e.g. ~9.5M reads vs ~80 writes per interval, so real effective k is likely
  lower, but thresholds must survive the pessimistic envelope).
- T = 8 workers. M = merge cost, measured by Mica as sub-millisecond against
  10-40 ms ticks: use M/S_par = 0.005.
- f = taint rate (section 1.5). Re-run executes at serial speed on the live
  world, no tracking. Serial remainder + apply cost cancel against baseline.
- G_abort: per-tick regroup fixed cost on aborted ticks; folded into the
  abort guard below, dropped from the headline inequality.

Step 8 wins iff P < S_par:

```
f < 1 - k/T - M/S_par        (break-even)
```

| k | k/T (T=8) | break-even f* |
|---|---|---|
| 2.0 | 0.250 | 0.745 |
| 2.5 | 0.3125 | 0.68 |
| 3.0 | 0.375 | 0.62 |

Break-even alone is not the bar: a 1.1x speedup does not justify building
step 8 when steps 1-7 offer 2-5x each at far lower risk. Require 2x on the
parallelizable share (P <= S_par/2):

```
f_2x = 0.5 - k/T - M/S_par
```

| k | f_2x |
|---|---|
| 2.0 | 0.245 |
| 2.5 | 0.18 |
| 3.0 | 0.12 |

Hence X ~= 0.10: at f <= 10%, even k=3 (worst envelope) clears 2x with margin.
Hence Y ~= 0.40: below worst-case break-even (0.62) with margin, AND above
~40% taint the Union-Find grouping percolates into one giant tainted
component (re-run parallelism collapses too), so effective cost converges to
full serial + full tracking overhead: strictly worse than vanilla.

### 4.2 Thresholds

- **X = 10% (GO):** workload passes if mean taint f <= 0.10 AND p99 tick taint
  <= 0.25 AND abort rate <= 80%.
- **Y = 40% (KILL):** workload fails if mean taint f >= 0.40 sustained across
  the capture, OR more than 50% of tainted ops fall in the single largest
  component on p50 of aborted ticks (giant-component collapse), OR abort rate
  > 95% combined with f > 0.25 (every tick pays full merge+regroup+serial).

### 4.3 Decision table (applied per workload class)

| Result | Fate of step 8 |
|---|---|
| All workloads W1-W6 pass (f <= X) | GO full SCMC as designed. |
| Sparse workloads (W1, W2, W5 steady, W6) pass; dense (W3, W4) fail at >= Y | CONDITIONAL sparse-only: parallelize random ticks + entity ticks; keep scheduled ticks / redstone serial. Dimension sharding first (plan step 18 pulled forward, Amdahl-capped but nearly free). Dense contraptions stay serial by construction. |
| Any sparse workload fails at >= Y | KILL step 8. Rely on steps 1-7 + 12/17/29. Do not revisit without a new mechanism. |
| Sparse workloads land between X and Y | CONDITIONAL with required follow-up: build the overlay prototype against the worst offending workload and measure wall-clock MSPT directly in the differential harness. Counts alone no longer decide. |

---

## 5. Confounds: handling and why exclusions are safe

| Confound | Handling | Why the exclusion does not invalidate the result |
|---|---|---|
| RNG ordering (a real SCMC impl needs per-thread deterministic RNG) | Fixed world seed + fixed player inputs per scenario; determinism self-check (2.4) gates every run. RNG call sites logged as `side_effect`, not conflicts. | RNG determinism is a separate engineering requirement with a known solution (counter-based per-op streams). This experiment measures access interaction, which is RNG-value-independent at the conflict-class level. |
| Chunk load/unload mid-tick | Ticks containing a load/unload event are tagged and EXCLUDED from headline stats, counted separately in `events.csv` with their own taint distribution. | Our server design loads chunks outside the tick or under an epoch guard; mid-tick structural mutation is out of scope for step 8 by design. Steady-state ticks (no structural change) dominate MSPT on farm/SMP servers, which is exactly the population the headline number describes. |
| Player movement across chunk borders | Border-crossing ticks tagged, reported with and without. W5 exists precisely to bound this effect. | Same rationale as above; plus entity owner-chunk is defined at tick start, so crossings are detectable, not silent. |
| Redstone cross-chunk updates | NEVER excluded. This is the signal, not noise. W3/W4 exist to measure it at adversarial density. | N/A (no exclusion). |
| Hopper/POI shared maps (non-positional shared state) | Logged as cross-chunk by construction (1.2); conflicts on shared maps count normally. | No exclusion; shared-map contention is a real step-8 cost and must appear in f. |
| Instrument-mod overhead perturbing thread interleaving | Vanilla tick is single-threaded; there is no interleaving to perturb. The log is a total order. | The parallel schedule being evaluated is hypothetical; the experiment measures what WOULD conflict under any parallel schedule, which is schedule-independent given O. |

---

## 6. Deliverable spec (build in one pass)

### 6.1 File layout

```
conflict-rate/
  mod/                  # Fabric mod source (JDK 25, Gradle 9.7+, Loom, no refmap needed on 26.x)
    src/...             # mixins per table 2.3 + ring-buffer logger
  corpus/               # per-scenario: server.properties, world PIN (seed + sha256), setup notes
    W1-idle/ W2-mobfarm/ W3-sorter/ W4-computer/ W5-explore/ W6-raid/
  logs/<scenario>/      # raw binary logs + determinism-check hashes
  analyze.py            # single script: binary log -> CSVs + report (no dependencies beyond stdlib + numpy)
  report/               # CSVs + SUMMARY.md per run
  run_corpus.sh         # end-to-end: build mod, launch server per scenario, capture, analyze
```

### 6.2 CSV schemas

`accesses.csv` (one row per logged access; generated offline from binary log):

```
tick,op_id,op_type,owner_cx,owner_cz,x,y,z,rw,cross,cascade,state_before,state_after,o_seq
```

- `op_type` in {random, entity, scheduled_block, scheduled_fluid, block_entity}.
- `rw` in {r, w}. `cross`, `cascade` in {0, 1}.
- `state_before/after`: numeric block-state id, empty for reads.
- `o_seq`: serial-order sequence number of the op within the tick.

`ticks.csv` (one row per tick; the primary analysis input):

```
tick,workload,n_ops,n_accesses,n_cross,n_conflict_pairs,n_tainted_ops,taint_rate,aborted,largest_component,ww,rw,cascade,benign_samevalue,benign_convergent,excluded_structural
```

- `aborted` in {0, 1}. `largest_component`: size (ops) of the largest tainted
  Union-Find component, 0 if none. `excluded_structural`: 1 if the tick was
  excluded for load/unload (section 5), else 0.

`events.csv` (confound tags):

```
tick,workload,event
```

- `event` in {chunk_load, chunk_unload, player_border_cross, raid_wave_start}.

### 6.3 Summary stats (analyzer must emit all of these per workload)

- Ticks captured / excluded-structural / determinism-check hash.
- Taint rate: mean, p50, p99, max. Abort rate. Conflict pairs per 1k ops.
- Breakdown: WW / RW / cascade-touch shares; benign shares (same-value,
  convergent); cross-chunk access share.
- Largest-component histogram over aborted ticks (p50 + share of tainted ops
  in the largest component: feeds the Y giant-component rule).
- Op-type split: which op types contribute most taint (decides sparse-only
  scope: if scheduled_block dominates, sparse-only = drop scheduled ticks).
- Decision row per workload: PASS / FAIL / MARGINAL with the X/Y rule cited,
  plus the overall step-8 verdict from table 4.3.

### 6.4 Reproduce commands (exact; bench PC, PowerShell)

```powershell
# prereqs: JDK 25 installed, JAVA_HOME set
cd conflict-rate\mod
.\gradlew build
cd ..
.\run_corpus.sh   # builds mod, launches vanilla 26.2 + mod per corpus/W*,
                  # captures 6000 ticks each, runs determinism check, runs analyze.py
python analyze.py --input logs\ --out report\
```

`run_corpus.sh` (or `.ps1` if the implementer prefers; one script, no manual
steps) fails loudly if: determinism check mismatches, any scenario yields
fewer than 6,000 non-excluded ticks, or any mixin coverage-audit gap is open.

### 6.5 Done criteria

- All six scenarios captured at >= 6,000 non-excluded ticks with matching
  determinism hashes recorded.
- Coverage audit (2.3) closed: every `setBlockState`/`getBlockState` call site
  in the 26.2 jar mapped to a mixin, or explicitly classified `side_effect`
  with justification in SUMMARY.md.
- `report/SUMMARY.md` contains the per-workload decision rows and exactly one
  of: GO / CONDITIONAL (with named scope) / KILL for step 8, citing the
  numbers. No essay, one verdict.
- Raw logs retained (they are the evidence for the follow-up prototype if the
  verdict is CONDITIONAL).

---

## Open questions for the implementer (not blockers)

1. Fake-player driver for W1/W5/W6: Carpet-mod bots vs scripted client.
   Either is fine; record which. Carpet on 26.2 must be verified available;
   if not, scripted vanilla clients.
2. `run_corpus.sh` vs `.ps1`: Bench PC-native PowerShell is acceptable if bash
   tooling is missing there. One script either way.
3. If Fabric Loom for 26.2 is broken on day one, fallback is a plain Java
   agent (`-javaagent`) with ASM intercepts at the same points. Same log
   format, same analyzer. Do not let tooling gaps change the measurement.
