# Running a live Kestrel server

## Why this file exists

The dev runtime (vanilla server jar, libraries, datapack, play world,
classpath file) used to live in `/tmp`. On this machine
`/usr/lib/tmpfiles.d/tmp.conf` contains `D /tmp`, which tells
systemd-tmpfiles to **empty `/tmp` on every boot**. A reboot on
2026-09-14 deleted the play world, the vanilla jar, the extracted
libraries, the datapack and `cp.txt` in one go, and no procedure for
rebuilding any of it was written down.

Everything now lives under `~/kestrel-run`.

**UPDATE 2026-09-16: `~/kestrel-run` is NOT reliably persistent either.**
The box came back from a reboot with `~/kestrel-run` AND
`~/.local/toolchains/jdk-25.0.4.1+1` both gone, while `~/projects/kestrel`
(this repo, including uncommitted diffs) survived on the same single
ext4 root filesystem. Best guess: that host syncs a subset of `$HOME` in
from elsewhere, and anything written locally outside that sync scope does
not survive a recycle.
**Do not trust that either the JDK or `~/kestrel-run` exists** - always
run `scripts/setup-env.sh` at the start of a session before assuming any
of this is there, not just "after a reboot". It is cheap and idempotent
either way. Do not move any of it back to `/tmp`.

## CI compile gate (GitHub Actions)

`.github/workflows/gates.yml` runs a small verification set on a
GitHub-hosted runner: bootstrap the environment via
`scripts/setup-env.sh`, then `compileJava` followed by a fast set of
`checkRepro` checks. It exists because a sandboxed environment can have
its network egress blocked from `piston-data.mojang.com`, so it cannot
fetch the pinned vanilla jar itself and prove a branch compiles; a GitHub
runner has normal network access
and 88ac74f added x86_64 support to `setup-env.sh`, so the bootstrap now
works there too.

**Trigger:** manual only (`workflow_dispatch`), never on push or on a
schedule - branches get pushed all day and this would otherwise burn
Actions minutes for no reason. To run it: GitHub -> repo -> Actions tab ->
"gates" workflow in the left sidebar -> "Run workflow" button -> pick the
branch -> Run workflow. The resulting run URL looks like
`https://github.com/<owner>/<repo>/actions/runs/<run-id>`.

The "Run workflow" form has an optional `checks` input: a comma separated
list of `checkRepro` check names to run instead of the default fast set
for that one run (for example `TickLoopCheck,FluidParityCheck`). Leave it
empty to run the default fast set. Each name is validated against
`core/build.gradle`'s `checkClassMap` before anything runs, so a typo
fails the job instead of silently running nothing.

**What it does:** installs Temurin 25 via `actions/setup-java`, runs
`scripts/setup-env.sh` to fetch the pinned vanilla server jar (sha1
verified) and libraries, caches `~/kestrel-run` keyed on that pinned sha1
and Gradle's own caches via `actions/setup-java`'s `cache: gradle`, then
runs `./gradlew compileJava` followed by `./gradlew checkRepro` over the
default fast set: `TickLoopCheck`, `FluidParityCheck`,
`EventReentrancyCheck`, `DuplicateScheduleCheck`,
`ScheduledTickRevisionCheck`, `RedstoneProofCheck`, `AcWireProofCheck`,
`RedstoneParityCheck`, `StatusPingCheck` and `ServerPropertiesCheck`. These use
the oracle fixtures already checked into
the repo plus the pinned vanilla jar, so a runner can run them. Measured
on run 36164148445 (the eight-check set of the time): the check step alone takes
about 50 seconds (`BUILD
SUCCESSFUL in 49s`, all 8 checks executed) and the job end to end takes a
bit under two minutes with the caches warm. The two added since then are file
and socket checks with no worldgen or oracle work, so they add about a second
each. Every command and its full
output is printed in the job log. Any failure (jar download, checksum
mismatch, compile error, an unknown `checks` input, or any single check)
fails the job; nothing is allowed to skip silently. Bounded to 30 minutes.

**What it deliberately does not cover:** the heavy worldgen and parity
suites (`FeaturesCheck`, `StructuresCheck`, `BatchParityGate`,
`ParallelNoiseCheck`, `StructureReferencesCheck`, etc.) - these are slow
and stay on the dev box, run manually via `./gradlew checkRepro` per
`core/README.md`. It also does not launch a live server or touch
`~/kestrel-run/play`; see "Launch" below for that.

## Release builds (GitHub Actions)

`.github/workflows/release.yml` is the second workflow and the one an owner uses.
On every push to `main` that can change the binary (docs-only pushes are ignored,
because this repository is private and Actions minutes are metered), it
bootstraps the same environment `gates.yml` does, compiles, runs the same fast
check set as a precondition, and only then publishes a zip to a rolling
release tagged `latest`:

```
gh release download latest --pattern kestrel-main.zip
```

The publish step cannot run unless every check passed, so a red `main` never
produces a runnable zip. The zip holds Kestrel's compiled classes and the
launcher scripts from `dist/`, and deliberately holds no Mojang binary, so the
first run on a fresh machine downloads the pinned 26.2 server bundler and, when
no Java 25 is present, the pinned Temurin 25 build, verifying both. The
`BUILD-COMMIT.txt` in the zip is what the startup banner reports as
`build commit=` when there is no `.git` directory to walk to.

Options and limits are documented in `dist/README.txt`, which ships inside the
zip: `--port`, `--online`, `--world` and `KESTREL_MEMORY`. Two differences from
the live server here are deliberate. Offline mode is the default for the same
reason it is here, and an offline server must not be exposed to the internet.
Saving is **on** in the release launcher, where the live server here keeps
`--no-save`; pass `--no-save` for a disposable world.

## One-time / post-reboot setup (actually: run every session, see above)

```
scripts/setup-env.sh
```

Installs the pinned JDK 25 toolchain to `~/.local/toolchains` if missing
(sha256 verified), downloads the pinned 26.2 server bundler (sha1
verified), extracts the server jar + 39 libraries to `~/kestrel-run/vinner`,
extracts the vanilla datapack to `~/kestrel-run/packdump`, and writes
`~/kestrel-run/cp.txt`. Idempotent: re-running it reuses an already-correct
JDK/jar and only re-extracts the rest. Requires `unzip` (`sudo apt-get
install -y unzip` if missing). Supports both aarch64 (the Raspberry Pi)
and x86_64, each pinned to its own verified Temurin JDK build; any other
architecture is refused with an error.

## Build

```
cd core && ./gradlew compileJava --offline \
  -PvanillaJar=$HOME/kestrel-run/vinner/META-INF/versions/26.2/server-26.2.jar \
  -PvanillaLibs=$HOME/kestrel-run/vinner
```

Gradle hashes task inputs, so `touch`ing a source file will NOT force a
rebuild. Use `--rerun-tasks` when you actually need to recompile against
a changed jar.

## Launch

**Launch it as a systemd user unit, NOT with `setsid nohup ... & disown`.**

Every child process inherits the cgroup of the session that started it, and
`setsid` + `nohup` + `disown` do NOT escape a cgroup (they only detach the
tty and the job table). When the parent service is restarted, systemd
SIGTERMs the whole cgroup and then SIGKILLs whatever is left, so a server
launched the old way dies silently with no exception in its log. This
destroyed two long-running servers on 2026-09-14/15 before the cause was
found.

### Build a deploy snapshot first

A running JVM keeps loading classes from its classpath directory, so the
live server must never run from a folder that later builds rewrite
(`~/projects/kestrel/core/build`, or any worktree). Build the commit you
want to run into its own local clone under `~/kestrel-run/deploy/<hash>`:

```
C=$(git -C ~/projects/kestrel rev-parse --short HEAD)   # a commit on main
D=$HOME/kestrel-run/deploy/$C
git clone -q --no-checkout ~/projects/kestrel $D && git -C $D checkout -q --detach $C
(cd $D && JAVA_HOME=$HOME/.local/toolchains/jdk-25.0.4.1+1 ./core/gradlew -q -p core compileJava \
  -PvanillaJar=$HOME/kestrel-run/vinner/META-INF/versions/26.2/server-26.2.jar \
  -PvanillaLibs=$HOME/kestrel-run/vinner)
git -C $D status --porcelain   # must print nothing
```

Use a clone, not a worktree or a copied `classes` folder. The startup
banner (`[kestrel] build commit=...`) finds the commit by walking up from
the classpath to a `.git` directory. A worktree's `.git` is a file and a
copied folder has none, so both log `commit=unknown`.

### Start

```
export XDG_RUNTIME_DIR=/run/user/$(id -u)
D=$HOME/kestrel-run/deploy/<hash>
systemd-run --user --unit=kestrel-server \
  --working-directory=$HOME/kestrel-run \
  --property=StandardOutput=append:$HOME/kestrel-run/server.log \
  --property=StandardError=append:$HOME/kestrel-run/server.log \
  --property=Restart=no --property=SuccessExitStatus=143 \
  --setenv=MALLOC_ARENA_MAX=2 \
  $HOME/.local/toolchains/jdk-25.0.4.1+1/bin/java -Xmx2G \
  -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=$HOME/kestrel-run/oom.hprof \
  -XX:+UseG1GC \
  -XX:G1PeriodicGCInterval=10000 -XX:G1PeriodicGCSystemLoadThreshold=0 \
  -XX:+G1PeriodicGCInvokesConcurrent \
  -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=25 \
  -Dkestrel.datapack=$HOME/kestrel-run/packdump/data \
  -cp "$D/core/build/classes/java/main:$(cut -d: -f2- $HOME/kestrel-run/cp.txt)" \
  dev.kestrel.net.KestrelServer $HOME/kestrel-run/play/world 25577 387435628208 --no-save --offline
```

- **Keep `--offline` in the launch command.** It is the flag that pins online mode
  off, so the command line stays the authority and a lost or freshly created
  `server.properties` cannot silently flip the live server to `online-mode=true`
  on the next start. It is not the Gradle `--offline` used elsewhere in this
  file. A launch without it would come up authenticating against Mojang.

- **Heap: `-Xmx2G` for a played server.** `-Xmx256M` passed the 441-chunk
  benchmark route, but on 2026-09-25 a player walking in one direction
  OOMed it after about 900 generated chunks, because `LiveGenerator`'s
  chunk grid grows and is never evicted. Chunk generation slowed to 1-3 s
  per chunk under GC pressure first (invisible, walkable chunks), then the
  JVM died. Until chunks unload, the live set grows with explored area.
- **Keep `--no-save`.** The live world is disposable and saving must stay
  disabled on it (no autosave, save-all or shutdown save). A launch copied
  from an older version of this file without it autosaved the live world on
  2026-09-17. `387435628208` is the live world's seed.
- `cut -d: -f2-` drops the first `cp.txt` entry (the repo's own
  `core/build/classes/java/main`) and keeps the vanilla jar and libraries.
- `--working-directory=$HOME/kestrel-run` keeps log4j's `logs/` folder out
  of the repo.
- `SuccessExitStatus=143`: the JVM exits with 143 on SIGTERM. Without this,
  a normal `systemctl --user stop` leaves the unit `failed`, and the next
  `systemd-run --unit=kestrel-server` refuses with "already loaded" until
  you run `systemctl --user reset-failed kestrel-server`.
- After start, `grep 'build commit' ~/kestrel-run/server.log | tail -1` must
  show the deployed hash with no `-dirty` suffix.
- Delete an old `deploy/<hash>` only after the server no longer runs from it.
- Never point ConfigProbe at the live port. For an end-to-end check, start
  a throwaway server from the same snapshot on a spare port with a scratch
  world and `--no-save`, then run the ConfigProbe command below against it
  with `--play 40`.

### Chunk generation mode, and why `MALLOC_ARENA_MAX=2`

Since 2026-09-24, fresh chunks are generated asynchronously by default: a
parity-gated background worker plus the `--carver-threads` pool (default:
cores - 1, at most 3). This is the path that reaches Paper-level generation
speed (see `docs/BENCHMARKS.md`) and keeps generation off the tick thread.
`--sync-chunkgen` switches back to generating on the tick thread under a
40 ms per-tick budget (`--gen-budget-ms` only applies in that mode). Both
modes produce identical terrain; sync is much slower. `--async-chunkgen` is
still accepted and does nothing, so old launch lines keep working.

The generation pool threads each get their own glibc malloc arena, which
shows up as RSS outside the JVM's own accounting (NMT). On the 65-chunk
live route (2026-09-24, VPS, `-Xmx256M`), `MALLOC_ARENA_MAX=2` lowered
connected RSS p50 from 469.0 to 430.3 MiB with the Step-10 build and from
428.1 to 411.7 MiB with the Step-9 build. It did not change throughput
(8.07 vs 8.16 chunks/s) or idle RSS (337-343 MiB in all four combinations).

### Why these heap flags

`-Xmx4G` used to be the whole line. A heap-cap ladder showed the server
completes a full 441-chunk generation at every cap down to 192M with no OOM
and no throughput loss (genMs flat at 76-79 s), because the live set is
about 177 MiB regardless of what it is offered. A larger cap only inflates
committed heap, and RSS follows.

The earlier memory-only configuration used
`-XX:-G1PeriodicGCInvokesConcurrent` to force periodic stop-the-world Full
GCs so G1 would uncommit aggressively. It did reach 321.6 MiB idle RSS, but
the Step-9 connected-player gate measured three periodic Full-GC pauses at
90.614-106.254 ms (p50 101.844 ms) and a 103.628 ms live tick outlier. That
configuration is therefore **rejected for production**.

The launch above explicitly keeps `G1PeriodicGCInvokesConcurrent` enabled.
This prioritizes tick latency over the memory-only 350 MiB experiment; the
remaining RSS/uncommit work is an optimization target, not a reason to ship
regular 100 ms stop-the-world pauses. The 256 MiB heap cap and free-ratio
bounds remain useful independently of that rejected flag.

Full analysis: `preresearch/g2-memory-350-feasibility.md`.

`XDG_RUNTIME_DIR` is unset in this environment and the user bus will not
connect without it. `Linger=yes` should be enabled for your user (`loginctl enable-linger`), so the
unit survives logout.

Verify the escape worked (this is the whole point, so check it):

```
systemctl --user show -p MainPID --value kestrel-server | xargs -I{} cat /proc/{}/cgroup
# want: /user.slice/.../kestrel-server.service
# NOT:  the cgroup of the session that launched it
```

Manage it with `systemctl --user {status,stop,restart} kestrel-server`.
A unit name also makes the two-writer preflight trivial, since systemd
refuses to start a unit that is already active.

System `java` is 17 and throws `UnsupportedClassVersionError`. Always use
the jdk-25 toolchain path.

### Before every launch

Two writers on one world produce a torn sidecar read. Verify BOTH:

```
ps -eo pid,args | grep '[d]ev.kestrel.net.KestrelServer'   # must be empty
ss -tln | grep ':25577'                                     # must be empty
```

Use the `[d]ev...` bracket form. A plain `pgrep -f dev.kestrel.net.KestrelServer`
matches the grep/pgrep command's OWN command line, so it reports phantom
pids and, if you pipe it into `kill`, terminates your own shell.

The bracket form fails in one case: a script that runs the check and then
the launch. That script's own command line contains the launch text, so
the check finds it and reports a running server. In a script, list only
java processes instead: `ps -C java -o pid=,args= | grep KestrelServer`.

Shutdown can take up to ~2 minutes (with `--no-save` it takes about a
second): the shutdown hook fsyncs RegionFiles
and ignores SIGTERM until it finishes. A slow shutdown looks dead while
still holding the port and writing saves. Be patient; never launch onto a
port that still answers.

## Checks

```
java -Dkestrel.datapack=$HOME/kestrel-run/packdump/data \
  -cp "$HOME/projects/kestrel/$(cat $HOME/kestrel-run/cp.txt)" dev.kestrel.net.NetCheck
```

Takes ~10 minutes and must end in `NetCheck: PASS`. It binds port 0
(ephemeral), so it is safe to run alongside a live server and never
touches the live world.

## Config-phase byte diff (ConfigProbe)

`dev.kestrel.net.ConfigProbe` is a scripted synthetic client. It drives
handshake -> login -> the whole configuration phase against ANY 26.2
server and dumps every packet it receives, so the same client can be
pointed at the real vanilla jar and at Kestrel and the two captures
diffed byte for byte. This is what found the three phantom RegistryData
packets on 2026-09-15.

```
java -cp "$HOME/projects/kestrel/$(cat $HOME/kestrel-run/cp.txt)" \
  dev.kestrel.net.ConfigProbe --host 127.0.0.1 --port 25599 \
  --out /tmp/cap-vanilla --packs echo
```

Writes `<out>` (structured decode) and `<out>.hex` (full payload hex).
The output is deterministic: no timestamps, offline UUID pinned by name,
so a plain `diff` of two captures is meaningful.

- `--packs echo` answers SelectKnownPacks the way a stock client does
  (echo the list back), which is the only answer that satisfies
  vanilla's all-or-nothing `List.equals` and gets the ids-only stream.
- `--packs none` sends an empty list, forcing full registry values out
  of both servers, which is what you want when diffing registry
  contents rather than the negotiation.
- `--packs extra` echoes plus one unrequested pack.
- `--snbt` renders registry entry NBT as flat sorted paths, which is the
  form to diff for value differences. `--dump-ids` prints the vanilla
  packet id tables. `--play N` continues into the play phase for N
  chunk packets.

Point it at a throwaway server, never at the live one on 25577. The probe now refuses port
25577 outright unless `--allow-live` is passed, so an accidental live capture cannot happen
again; its default port is still 25577, which is why the guard exists. Note that `--dump-ids`
prints only the configuration-phase tables, not the play-phase ones.

## Debug flags

- `-Dkestrel.tracklog=true` - per-player position telemetry, off by
  default. Emits on the `VIOLATION_REPORT_TICKS` (100) interval.
- `-Dkestrel.datapack=<dir>` - vanilla datapack `data/` directory.

Last audited against the code and the gates: 2026-09-28, main `919ce0d`. The CI
fast set, the `checkClassMap` validation and the measured timings were checked
against `.github/workflows/gates.yml` and `core/build.gradle` at that commit.
