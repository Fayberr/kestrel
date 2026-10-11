# Kestrel tick-compare harness

Status: working differential-testing harness. Attaches to vanilla 26.2 via
a plain `-javaagent` (ASM bytecode hooks), logs one line per tick plus five
sibling proof/census logs, and diffs runs with the PowerShell/Python tools
below. Schema 6 (exit-only hashing; enter lines carry a 0-hash sentinel).

## Why this exists

The plan's standing rule: every subsystem is proven by differential testing
vs vanilla (state compared every tick) before it lands. This harness is the
infrastructure that comparison runs on. It is deliberately separate from,
and simpler than, the conflict-rate experiment (`preresearch/conflict-rate-design.md`),
which is a specific, later, much larger measurement that decides whether
plan step 8 (dependency-parallel ticking) ships. This harness is the
general-purpose "run vanilla, capture per-tick state" tool every future
optimization gets checked against.

## Design pivot: Fabric mod -> plain javaagent

`conflict-rate-design.md` recommended a Fabric mod (mixins) as the
instrumentation vehicle. Verified 2026-09-10 directly against
`meta.fabricmc.net`: Fabric intermediary mappings for MC 26.2 return the
`0.0.0` placeholder (not yet published), so Fabric Loom is not usable for
26.2 yet. The design doc's own "Open questions" section (item 3)
pre-approved the fallback for exactly this situation: a plain `-javaagent`
with ASM bytecode intercepts at the same hook points, same log format, same
downstream analyzer. That is what `agent/` implements.

Because MC 26.x ships fully deobfuscated (real class/method names, no
mappings needed at all - confirmed via `javap` against
`META-INF/versions/26.2/server-26.2.jar` inside the bundler jar), targeting
real names at the bytecode level is not really a workaround: it is
arguably simpler than stacking a mod-loader + mapping toolchain would have
been in the first place.

## Layout

```
harness/
  agent/          # Java agent (Gradle project, ASM-based bytecode instrumentation)
  corpus/         # differential-run fixtures: datapack scenarios and oracle captures (see corpus/README.md)
  tools/          # runners + analyzers (see below)
  bench.yaml      # whole-server benchmark suite config (run-bench.py)
  paper-genbench/ # Paper 26.2 chunk-generation benchmark plugin (see its README)
  logs/           # (future) captured tick logs, gitignored
  README.md       # this file
```

### tools/

Tick-compare runners:

- `run-harness.ps1` - launches a bounded, headless vanilla run with the
  agent attached. Synchronous in the invoking shell (see the script's own
  header comment for why this, not the Task-Scheduler-detachment pattern,
  is correct here). Flags: `-DatapackDir` (pre-seed a `corpus/` scenario),
  `-LevelType` (e.g. `minecraft:flat` for a clean volume - writes
  `generate-structures=false`; note the server reads `generator-settings`,
  not `level-generator-settings`), `-SpawnMobs`/`-SpawnAnimals` passthrough.
- `determinism-check.ps1` - runs the same world twice and diffs the
  per-tick hash sequences: exit-phase lines only (enter lines carry the
  0-hash sentinel), `tick_count` equality asserted. `-SkipTicks 5` excludes
  the fresh-world settling window; `-LevelType`/`-SpawnMobs`/`-SpawnAnimals`
  forward to run-harness. Before any world_state_hash log is trusted for a
  parity decision, this must PASS for the corpus. Known caveat: entity
  UUIDs are unseeded per run, so entity-bearing corpora FAIL by
  construction - use empty-world controls or the same-tick proof logs.
- `run-core-check.ps1` - runs the `core/` headless proofs/benches against
  the real vanilla classes. `-All` runs every proof and exits non-zero if
  any fails; `-All -Bench` runs the benches instead. These need the
  bundler's extracted `libraries/` on the classpath, not just the nested
  server jar.

Analyzers (one per sibling log, same pattern - all use
`[Console]::WriteLine` for display, never `Write-Output` inside an assigned
function call, per the mspt-analyze WI-10 root cause):

- `census-summarize.ps1` - summarizes `block-entity-census.log` (per-type
  totals + idle fractions; two-log mode diffs tick-by-tick).
- `power-summarize.ps1` - summarizes `power-trace.log` (wire-count
  min/max, ticks with wire/power change vs previous, total transitions;
  two-log mode diffs per-wire powers tick-by-tick).
- `spawn-summarize.ps1` - summarizes `spawn-proof.log` (no-state ticks,
  spawnableChunkCount 0-to-289 flip tick, per-category pass/fail
  transitions, DIVERGE count; zero DIVERGE IS the global-cap proof).
- `ac-summarize.ps1` - summarizes `ac-proof.log` (settling vs settled
  ticks, DIVERGE count; zero DIVERGE over the settled window IS the
  propagation-core proof).
- `mspt-analyze.ps1` - MSPT from `ticks.log` per docs/BENCHMARKS.md §2B:
  exit-phase only, SkipTicks warmup discard, p99 trim, median/p95.
  Accepts one or two logs (two-log mode prints the delta).
- `summarize-results.py` - parses bench PC `bench_results.txt` (or
  `--json bench_results.json`): one row per RESULT line plus per-run
  MEDIAN rollups. Churn rows without a `cycles=` field are flagged
  SUPERSEDED (pre-WI-1-dwell: old bots disconnected instantly, so their
  cpuLoad/rssLoad measured an idle server).

Whole-server benchmark suite:

- `run-bench.py` - generates + optionally launches the `bench.yaml`
  matrix (servers x flag_sets x scenarios x repeats) as bench PC PowerShell.
  `--config`, `--only`, `--servers`, `--flags`, `--bots`, `--repeats`,
  `--stay`, `--mode`, `--launch`, `--poll`. Results append incrementally
  to `bench_results.txt` + `.json`; `flag_sets` assert-match (generator
  fails loudly if the resolved `.bat` lacks the YAML `jvm_flags`); per-bot
  accounting (`bots ok=X failed=Y`), per-block try/catch with
  `RESULT ERROR`, single `benchmark finished` marker.
- `launch-bench.ps1` - detached-launch wrapper for the generated script:
  child stdout/stderr to `bench_run.<pid>.log`, PID lockfile, refuses
  concurrent runs, append-only results, prints `DETACHED` only after the
  child is confirmed alive.
- `load-bot.py` - protocol bot (stdlib only): handshake -> login ->
  config -> play, answers keepalive. Behaviors: idle (AFK stay), spin,
  walk, dig, place (UseItemOn 0x42 against the block below every ~100
  ticks, passive ack listen, never fails), churn (reconnect cycles with dwell: holds each session
  `max(5.0, stay/cycles - 1.0)`s before reconnecting). Short `B<beh><n>`
  names (16-char MC limit).
- `afk-bot.py` - DELETED (WI-13): legacy single-role bot, superseded by
  `load-bot.py --behavior idle`. Reference check before deletion: the only
  in-repo pointer was load-bot.py's "same as afk-bot" packet-ID comment
  (ground truth, kept); no docs, configs, or scripts invoked it. The
  spawn-census recipe uses the idle role: launch the corpus with
  `-SpawnMobs`, attach one `load-bot.py --behavior idle --stay <window>`
  bot so spawn attempts execute past the `getNearestPlayer` null check,
  then prove leg B with `spawn-summarize.ps1` (zero DIVERGE), not the tick
  hash (entity UUIDs differ per run by construction).

**The `core/` tree on bench PC is an scp'd staging copy, not a git checkout.**
Sync `core/src` before trusting a run there; it has silently sat several
commits behind before. Bench numbers are machine-specific and never
transfer between the Pi and bench PC; proof verdicts do (they are
deterministic differentials, so a platform difference is itself a finding).

## Hook point

`net.minecraft.server.MinecraftServer.tickServer(java.util.function.BooleanSupplier)`
- confirmed via `javap -p` as the real per-tick entry point in 26.2.
Instrumented at both entry and exit (tagged `enter` / `exit` in the log);
only exit lines carry a real hash. Enter lines keep tick_count/nanos with
a 0 hash sentinel so line counts and tick coverage stay comparable, while
the per-tick walk cost is paid once.

## Log format (schema_version=6)

```
seq,phase,tick_count,nanos,world_state_hash
```

`world_state_hash` is a real FNV-1a-64 hash (see `WorldStateHasher.java`):
deterministically ordered over every loaded level's dimension key,
entities (sorted by UUID: position, motion, type), and loaded chunks
(sorted by position: non-air block states with position + registry id,
plus block entities sorted by position with container contents hashed
slot-by-slot as item key + count). Reflection-only. Exit phase only.

Walk history: v1 hashed via visibleChunkMap (empty with no players - v1/v2
logs hash ZERO blocks); v2 added container contents; v3 moved to
getChunkNow/updatingChunkMap + section walk with hasOnlyAir skip (the
per-block reflective walk tripped the server watchdog); v4 added the
block-entity census sibling; v5 the spawn-proof sibling; v6 the ac-proof
sibling. Old v1/v2 logs are NOT comparable to v3+ (different chunk set AND
different walk). schema_version=0 logs (identity-hashcode placeholder)
predate all of this and must not be used for any parity decision.

Scoped for small/bounded corpus worlds - see the hasher javadoc for why it
should not be pointed at a full always-loaded survival world yet.

Sibling logs (all next to ticks.log, exit phase only, one line per tick):

- `power-trace.log` (PowerTrace) - per-wire POWER signal trace for the
  experimental-vs-default differential.
- `block-entity-census.log` (BlockEntityCensus, schema v1) - per-tick
  per-type ticker totals + idle counts (`tick,tickers,name=total/idle,...`
  with variable cells in sorted order) for step 5's item 2.
- `spawn-proof.log` (SpawnProof, schema v1) - same-tick global-cap
  recomputation (`count < max*chunks/289` vs vanilla's verdict per
  category) + charge stability signal; DIVERGE lines on mismatch.
- `ac-proof.log` (AcProof, schema v1) - same-tick AC propagation replay
  over live wire data; DIVERGE on mismatch.
- `block-shape-audit.txt` (BlockShapeAudit) - one-shot audit of vanilla
  collision box counts (not per-tick).

## Boot jar: the bootstrap classes, kept separate

`build\libs\kestrel-harness-boot.jar` contains every bootstrap-loaded
class (see `bootstrapClasses` in `agent/build.gradle`): TickLogger,
WorldStateHasher (+Handles), BlockShapeAudit, PowerTrace (+Handles),
BlockEntityCensus (+Handles), SpawnProof (+Handles), AcProof (+Handles),
FeaturesTrace (+SpawnCapture, +LightingCut), ChunkgenWatch, HashProbe
(+Handles) - 18 classes total. Everything the injected tick bytecode or the
exit-phase tracers touch must resolve via bootstrap (the bundler launches
the real server in an isolated URLClassLoader). They ship in a SEPARATE
jar from HarnessAgent itself (bootstrap split-definition crash otherwise -
see build.gradle comments). Fixed, version-free filename; HarnessAgent finds
it next to its own jar at runtime. Don't rename or separate the pair.

## Build & run (bench PC, JDK 25)

```powershell
cd harness\agent
.\gradlew.bat build
# needs network (resolves the shadow plugin); --offline fails even warm.
# Bench PC JAVA_HOME can rot to a stale scoop shim (User scope wins over the
# correct Machine-level JDK): pin per-invocation:
#   $env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4.1+1'
# produces build\libs\kestrel-harness-agent-0.1.0.jar (shaded, ASM etc.) AND
# build\libs\kestrel-harness-boot.jar (12 bootstrap classes, version-free)
# side by side - both required. See build.gradle comments for why the boot
# classes ship separately (bootstrap-classloader split-definition crash).

java -javaagent:build\libs\kestrel-harness-agent-0.1.0.jar=C:\path\to\ticks.log `
     -jar C:\path\to\vanilla-boot-test\server.jar --nogui
```

Use the bundler jar (`vanilla-boot-test\server.jar`, ~60MB), NOT the nested
compile-only `server-26.2.jar` (~25MB): the bundler self-extracts
`libraries/` + the real server and launches it. A standalone process also
needs the bundler's extracted `libraries/` dir on the classpath, not just
the nested jar (DFU/brigadier/fastutil live there).

Expect `[kestrel-harness] agent attached...` and
`[kestrel-harness] instrumenting net/minecraft/server/MinecraftServer.tickServer...`
on stdout, and a growing `ticks.log` with one `enter`/`exit` pair per tick
(exit lines hashed, enter lines 0-sentinel).

## Machine layout (bench PC)

- Bench installs: `C:\Users\bench\bench\` (`vanilla\`, `paper\`,
  `kestrel.log` / `kbench-RUN.bat`, `bench_results.txt` +
  `bench_results.json`). See `bench.yaml` for ports, flags, scenarios.
- Staging copies (NOT git checkouts - sync before trusting a run):
  `C:\Users\bench\kestrel\harness\agent\` (agent sources),
  `C:\Users\bench\kestrel\core\` (headless proof sources).
- Vanilla jars: bundler `C:\Users\bench\kestrel\vanilla-boot-test\server.jar`;
  nested compile-only `server-26.2.jar`; extracted `libraries\` next to it.
- JDK 25: `C:\Program Files\Java\jdk-25.0.4.1+1` (Machine scope; the User
  `JAVA_HOME` scoop shim rots - pin per-invocation, see above).
- Shell: PowerShell 7.6+ (`pwsh`). Plain `powershell` is 5.1 - never use
  it for harness scripts.

## Done (in order)

1. ~~Real per-tick world-state hash~~ DONE (`WorldStateHasher.java`) -
   entities + loaded chunks (block states + block entities + container
   contents), deterministically ordered, FNV-1a-64. Current walk is v3+.
2. ~~Determinism self-check~~ DONE (`tools/determinism-check.ps1`) -
   PASSED on a plain empty-world corpus. `collision-stress` (AI-enabled
   mobs) passes with `-SkipTicks 5` (fresh-world spawn-area settling in
   `tick_count<=1`, not a permanent divergence). See
   `preresearch/collision-path-analysis.md` for the before/after data.
3. ~~Runner script~~ DONE (`tools/run-harness.ps1`) - launches vanilla +
   agent against a throwaway world, headless, synchronous in the invoking
   shell. Supports `-DatapackDir`, `-LevelType`, `-SpawnMobs`,
   `-SpawnAnimals`.
4. Only once (1)-(3) exist and pass: start comparing a Kestrel subsystem's
   tick output against this harness's vanilla capture, per the plan's
   parity rule.
5. ~~Whole-server benchmark suite~~ DONE (`bench.yaml` + `run-bench.py` +
   `load-bot.py` + `launch-bench.ps1`) - servers x flag_sets x scenarios x
   repeats, detached launch, incremental JSON, per-bot accounting.
6. ~~Block-entity census~~ DONE (`BlockEntityCensus.java`, schema v1) -
   per-tick per-type ticker totals + idle counts, `block-entity-census.log`
   next to `ticks.log`. Summarize/diff with `tools/census-summarize.ps1`.
7. ~~Spawn proof~~ DONE (`SpawnProof.java`, schema v1, TickLogger schema 5)
   - same-tick global-cap recomputation over live SpawnState, leg-D result:
   zero DIVERGE over 1074 live ticks with the bot. Charges always 0/0.0 on
   the spawn-census corpus (no MobSpawnCost there) - stability signal only.
8. ~~AC proof~~ DONE (`AcProof.java`, schema v1, TickLogger schema 6) -
   same-tick propagation replay over live wire data; DIVERGE on mismatch.
9. Known gap: block-state hashing is a full scan of every loaded chunk,
   every tick - fine for small corpus worlds, not for a full survival
   world at normal render distance. Incremental/dirty-block-tracking
   hashing (hook block-change call sites instead of scanning) is future
   work if that ever becomes necessary.

The conflict-rate experiment's ring-buffer/lock-free logging and full
READ/WRITE/cascade access-class tracking (conflict-rate-design.md section
2) stay a separate, later build; this harness's logger is intentionally
simple (synchronized, flush-per-line - required, since runs end via kill)
and unsuitable for that experiment's timing-sensitive measurement.
