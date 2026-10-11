# Kestrel G2 step-1 oracle corpus: first real boot

Live-boot notes for `harness/tools/corpus-gen.ps1` on bench PC. The script was
written earlier and had **never been booted**; four things were open. This
is what actually happened.

## TL;DR

The first boot failed, and **none of the four open questions was the reason**.
Two real bugs sat underneath them, both of which had to be fixed before any
of the questions could even be asked:

1. The agent's per-tick `PowerTrace` instrument starved the tick loop and
   tripped the 60 s server watchdog, **hard-crashing the server mid-generation**
   (no region files, the entire point of the run).
2. `save-all` and `stop` are level-4 (`LEVEL_OWNERS`) commands, and datapack
   functions are parsed with a level-2 command source, so **no level-4 command
   can ever be called from a datapack function**. The clean-stop design could
   not work as written.

Because of (1), the pilot never got past tick 211, which is why the "wave2
never fired" symptom looked like a `schedule`-from-load failure. It was not:
`/schedule function corpus:wave2 60s` means **1200 game ticks**, and the
server had only reached 211 when it died.

## 1. What broke

### 1a. PowerTrace watchdog crash (the run-killer)

Pilot 1 (`-HalfSide 4 -CoreHalfSide 2 -Seconds 180`) booted, the datapack
loaded, wave1 applied, and then:

```
[19:48:29] [Server Watchdog/ERROR]: This crash report has been saved to:
  ...\crash-reports\crash-2026-09-13_19.48.29-server.txt
```

Crash report:

```
Description: Watching Server
java.lang.Error: Watchdog (Watching Server)
  at dev.kestrel.harness.PowerTrace.collectWires(PowerTrace.java:281)
  at dev.kestrel.harness.PowerTrace.fullScan(PowerTrace.java:229)
  at dev.kestrel.harness.PowerTrace.buildLine(PowerTrace.java:132)
  at dev.kestrel.harness.PowerTrace.traceExit(PowerTrace.java:93)
  at dev.kestrel.harness.TickLogger.onTick(TickLogger.java:112)
  at net.minecraft.server.MinecraftServer.tickServer(MinecraftServer.java:1025)
```

`TickLogger.onTick` called four proof instruments unconditionally on every
tick exit: `PowerTrace`, `BlockEntityCensus`, `SpawnProof`, `AcProof`. Each
does a full reflective walk of every loaded chunk. The log shows the spiral
directly:

```
powertrace scan tick=1   holders=961  chunks=44
powertrace scan tick=2   holders=961  chunks=49
...
powertrace scan tick=211 holders=...  chunks=...
```

211 ticks in 82 s of wall clock = **~2.6 tps against a healthy 20 tps**, and
degrading, until one tick exceeded the watchdog's 60 s and the server was
killed. None of those four instruments feeds this corpus.

This is the failure mode already known for `WorldStateHasher` ("per-block reflective walks trip the 60 s
watchdog on force-loaded volumes") - `WorldStateHasher` got the
discovery-cache fix, but `PowerTrace` was added later and never did.

### 1b. Level-4 commands are unreachable from datapack functions

The datapack's `corpus:finish` was `say` + `save-all flush` + `stop`. It
failed **at datapack load**, not at run time:

```
[19:47:06] [ServerMain/ERROR]: Failed to load function corpus:finish
java.lang.IllegalArgumentException: Whilst parsing command on line 2:
  Unknown or incomplete command. See below for error at position 0: <--[HERE]
  at net.minecraft.commands.functions.CommandFunction.fromLines(CommandFunction.java:81)
  at net.minecraft.server.ServerFunctionLibrary.lambda$reload$3(ServerFunctionLibrary.java:85)
```

"Position 0" is the start of line 2, i.e. `save-all` itself is unknown to the
parser. Verified with `javap` against the nested `server-26.2.jar`:

- `SaveAllCommand`: `ldc "save-all"` ... `getstatic Commands.LEVEL_OWNERS` ... `requires(...)`
- `StopCommand`: `ldc "stop"` ... `getstatic Commands.LEVEL_OWNERS` ... `requires(...)`
- Both ARE registered (`Commands.class` calls `SaveAllCommand.register` unconditionally)
- and the commands that *did* parse in the same datapack are `forceload`,
  `schedule` and `say`, which are all `LEVEL_GAMEMASTERS`. The split is
  exactly at the parse source's level: **level 2 commands load, level 4
  commands do not**.

Brigadier filters `requires()`-gated nodes during parsing, and functions are
parsed with a level-2 source, so the node is invisible and the parse fails
with "unknown command". The same would happen to `stop`. The datapack
function parser was never going to be able to shut the server down.

Note the failure is **silent in effect**: the server boots, looks perfectly
healthy, and runs an empty world. Only the `Failed to load function` line
gives it away. The script did not check for it; it now does.

### 1c. `wave2` was a symptom, not a bug

Pilot 1 reported `CORPUS_WAVE2_APPLIED never appeared`. That reads like
`schedule function` from a `load` function failing to fire (open question 1).
It was not. `/schedule ... 60s` is 1200 **game** ticks; the server died at
tick 211 because of 1a. Whether schedule-from-load works on 26.2 is therefore
still **untested** - see the NOT-verified list.

## 2. Fixes made

**Agent (`harness/agent/src/main/java/dev/kestrel/harness/TickLogger.java`).**
Two new system-property gates, both defaulting to the existing behaviour so
every current caller (`run-harness.ps1`, `chunkgen-watch.ps1`, the JFR/ASM
comparison runs) is unaffected:

- `kestrel.harness.perTickTraces` (default `true`) - gates the four per-tick
  proof instruments off.
- `kestrel.harness.tickHash` (default `true`) - gates the per-tick
  `WorldStateHasher` walk. Disabling writes the same 0-hash sentinel the
  enter-phase lines already use, so `ticks.log` line counts stay readable.

`corpus-gen.ps1` always passes `-Dkestrel.harness.perTickTraces=false`, plus
`-Dkestrel.harness.tickHash=false` when `-DisableTickHash` is given.

**Script (`harness/tools/corpus-gen.ps1`).**

- **Clean stop without datapack functions.** The server is now launched via a
  generated `boot.bat` through `cmd.exe` with `RedirectStandardInput`, so the
  script keeps the stdin end of a pipe and writes `stop` to the server console
  after the generation window. (`Start-Process` cannot redirect stdin from a
  live pipe.) `finish.mcfunction` is gone entirely.
- **Generation window starts at wave1, not at process start.** Boot takes
  20-40 s; counting it silently shrank the budget. `generationSeconds` is
  recorded in the manifest.
- **Fail fast on a dead server** - the window is skipped if the process
  already exited, so a crashed run fails in seconds instead of burning 10 min.
- **Detect `Failed to load function`** and fail the self-check loudly (1b).
- **Keep `stdout.log` and `hash-probe.csv`** in `OutDir`. `stdout.log` is the
  only evidence for the wave markers and used to live in a temp dir that the
  next run deletes.
- **Honest `cleanStop`**: `cleanStop` now means "we sent `stop` and the
  process exited by itself"; `killedByBackstop` is recorded separately. The
  old `cleanStop = $p.HasExited` was true even when the server had
  *crashed*, which is exactly backwards.
- Header fixed to say `powershell`, not `pwsh` (bench PC has **Windows
  PowerShell 5.1** only; there is no `pwsh` on PATH).
- Manifest `schema` bumped to 2 (marker rename `finish` -> `shutdown`,
  plus `stopCommandSent`, `killedByBackstop`, `datapackLoadErrors`,
  `probeRows`, `generationSeconds`, `tickHash`).

**Agent staging rot (recurring).** `HashProbe.java` was missing from the
Bench PC staging tree while `HarnessAgent.java` already referenced it, so the
last successful agent build predated the HashProbe change. Confirmed by the boot jar
contents (no `HashProbe.class`). Fixed by wiping `src` + `build` and
re-copying the whole tree from HEAD; the rebuilt boot jar now contains
`HashProbe.class` + `HashProbe$Handles.class`, and no bootstrap classes leak
into the agent jar.

## 3. What the instrument layer proved (pilot 1, before the crash)

The crash was the only failure. Everything below it worked:

- `chunkgen-watch.csv` - **81 data rows**, one per forceloaded chunk, every
  stage column populated except `structure_starts_ms` / `structure_references_ms`
  (0, correct: structures are OFF).
- `hash-probe.csv` - **419 rows** with a clean pipeline pyramid:
  **P1=169, P2=169, P3=81** chunks. 81 chunks reached P3 (LIGHT/FULL), 169
  reached P2 (CARVERS) - the extra 88 are neighbours pulled in by FEATURES
  writing into an 8-neighbourhood. That is the documented pipeline-depth
  signal and it is exactly the shape expected.
- The load function ran and `forceload add` from it works: wave1's
  `say CORPUS_WAVE1_APPLIED` appeared and chunks started loading.

## 4. Open questions

| # | Question | Verdict |
|---|----------|---------|
| 1 | Does `schedule function` from a `load` function fire on 26.2? | **STILL OPEN** - the pilot died before tick 1200. NOT disproven. |
| 2 | Does stop-from-schedule shut the server down cleanly? | **MOOT / REPLACED** - `stop` cannot run from a datapack function at all (level-4). Shutdown is now driven over console stdin. |
| 3 | Agent CSV rows under this config? | **YES** - 81 csv rows + 419 probe rows, see section 3. |
| 4 | Throughput: 2401 chunks in 600 s? | see section 5 |

## 5. Run results

_(filled in after the pilot and full run)_

## 6. NOT verified / honest remainder

_(filled in after the pilot and full run)_
