<#
.SYNOPSIS
  G2 step-1 vanilla oracle corpus: pregenerate a 49x49 chunk area at FULL
  (structures OFF), then archive region files + stage CSV + a manifest.

.DESCRIPTION
  Boots a REAL vanilla 26.2 server with the kestrel javaagent (same mechanism
  as chunkgen-watch.ps1), forceloads a square in center-first WAVES, and
  copies the resulting overworld *.mca out as a reusable corpus. Note the
  overworld lives at world/dimensions/minecraft/overworld/region on 26.2 -
  see the region-copy comment further down, this cost the first pilot its
  entire corpus.

  Why a corpus and not a live spiral: the JFR/ASM pyramid shrinks
  529 -> 49 -> 25 -> 10 -> 1 down the status chain (g2-build-order.md
  finding 5), so a live run cannot supply N >= 1000 FEATURES samples. That
  needs ~32x32 chunks at FULL plus ~8 chunks of margin per side for the
  lower-status rings, hence 49x49 = 2401 starts.

  FORCELOAD WAVE DESIGN (the documented choice): wave1 = the inner
  (2*CoreHalfSide+1)^2 = 33x33 core, issued by the datapack load function so
  it fills first; wave2 = the outer ring (49x49 minus the core), issued
  WaveGapSeconds later by `schedule function corpus:wave2 <gap>s` from that
  same load function. Chosen over RCON/stdin writes because it needs no
  console plumbing (chunkgen-watch.ps1's proven shape). Each wave emits a
  `say CORPUS_WAVE<n>_APPLIED` marker, checked out of stdout.log, because a
  scheduled function that silently fails to fire is otherwise invisible.

  CLEAN STOP - why it is NOT a datapack function (measured 2026-09-13):
  the first pilot scheduled `corpus:finish` = `save-all flush` + `stop` and
  it DIED AT DATAPACK LOAD:
    Failed to load function corpus:finish
    IllegalArgumentException: Whilst parsing command on line 2:
    Unknown or incomplete command ... at position 0
  Both `save-all` and `stop` are gated on Commands.LEVEL_OWNERS (permission
  level 4; confirmed by javap against the nested server-26.2.jar), and
  datapack functions are parsed with a level-2 command source, so Brigadier's
  `requires` filter removes those nodes and the parse fails. NO level-4
  command can ever be called from a datapack function. The clean stop is
  therefore driven from OUTSIDE: the server is launched with its stdin on a
  pipe and the script writes `stop` to the console when the window expires.
  (Tried and rejected: relying on the backstop SIGKILL loses every chunk
  written since the last autosave, and the region files are the point.)

  JAVAAGENT TRACES: the agent runs four per-tick proof instruments
  (PowerTrace, BlockEntityCensus, SpawnProof, AcProof) that each walk every
  loaded chunk on every tick exit. None of them feed this corpus, and on a
  force-loaded volume that walk blows the 60 s server watchdog - the first
  pilot hard-crashed at tick 211 after 82 s of wall clock with the server
  thread parked in PowerTrace.collectWires, losing all region files. Corpus
  runs pass -Dkestrel.harness.perTickTraces=false (default ON, so every
  other harness caller is unchanged).

  Usage (bench PC, FOREGROUND ssh - detached launches sit silent on failure):
    pwsh -File harness/tools/corpus-gen.ps1 -Seed 4242424242 -HalfSide 24 -Seconds 600


  MANIFEST SCHEMA (manifest.json in OutDir; re-read and asserted at the end):
    { "schema":3, "createdUtc":"ISO-8601", "seed":<long>, "halfSide":24,
      "coreHalfSide":16, "chunkSquare":"49x49", "startChunks":2401,
      "structures":false, "seconds":600, "generationSeconds":600.2,
      "waveGapSeconds":60, "port":25574,
      "levelType":"minecraft:normal", "tickHash":true,
      "serverJar":{"name","bytes","sha256"},
      "agentJars":[{"name","bytes","sha256"}, ...],
      "cleanStop":true, "stopCommandSent":true, "killedByBackstop":false,
      "waveMarkers":{"wave1":true,"wave2":true,"shutdown":true},
      "datapackLoadErrors":0,
      "csvRows":2401, "probeRows":28812, "regionSource":"world\dimensions\minecraft\overworld\region",
      "regionCount":4,
      "regions":[{"name","bytes","sha256"}, ...], "notes":"..." }
.PARAMETER Seed
  World seed. Default 4242424242 (matches every prior harness run).
.PARAMETER HalfSide
  Forceload square half-side in chunks. Default 24 -> 49x49 = 2401 starts
  (32x32 FULL core plus ~8 chunks of margin per side).
.PARAMETER Seconds
  Generation window: wall-clock seconds to let the server generate after the
  datapack load, before the script writes `stop` to the server console.
  Default 600.
.PARAMETER WaveGapSeconds
  Delay between wave1 (core) and wave2 (ring). Default 60. NOTE this is a
  delay in GAME TICKS (/schedule Ns = 20*N ticks); if the server is ticking
  slower than 20 tps while generating, wall-clock arrival is later. Measured
  2026-09-13 (pilot 2, 81 chunks, perTickTraces off): 180 s of wall clock
  bought only tick 1199, i.e. ~6.7 tps, so the default 60 s gap (= 1200 game
  ticks) JUST missed the window and wave2 never fired. -Seconds must be sized
  against the TICK rate, not the wall clock: budget well over 20*WaveGapSeconds
  seconds of generation on a large corpus, e.g. -Seconds 900 for the ring to
  land at HalfSide 24.
.PARAMETER CoreHalfSide
  Half-side of the FULL core wave. Default 16 (33x33).
.PARAMETER Port
  server-port for this run. Default 25574 (the watcher family port used by
  chunkgen-watch.ps1 / jfr-watch.ps1). Kept OFF 25575 on purpose:
  mspt-live.ps1 hardcodes 25575, and on 2026-09-13 the second pilot died at
  boot with FAILED TO BIND TO PORT because a concurrent mspt-live run had
  taken 25575. The process-based guard below cannot catch that race - the
  other server may bind AFTER we check - so a distinct port is the real fix.
.PARAMETER OutDir
  Corpus destination. Default C:\Users\bench\kestrel\corpus\seed-<Seed>-<struct|nostruct>.
.PARAMETER GenerateStructures
  OFF by default (step-1 first pass): writes generate-structures=false,
  which removes traps 2/3/4/5/11/18 from the oracle.
.PARAMETER DisableTickHash
  Pass -Dkestrel.harness.tickHash=false. The per-tick world-state hash walks
  every loaded chunk each tick; it is a determinism oracle for the live
  harness, not an input to this corpus. Leave it on unless the hash walk is
  itself starving the tick loop on a large corpus.
.PARAMETER Force
  Boot even if another Minecraft server is already running on this box. Off
  by default: a second server skews the throughput measurement and the two
  fight over temp/world dirs.
.PARAMETER VanillaBoot
  Dir with server.jar (bundler). Default C:\Users\bench\kestrel\vanilla-boot-test.
.PARAMETER AgentDir
  Dir with the built agent jars. Default C:\Users\bench\kestrel\harness\agent\build\libs.
#>
param(
    [long] $Seed = 4242424242,
    [int] $HalfSide = 24,
    [int] $Seconds = 600,
    [int] $WaveGapSeconds = 60,
    [int] $CoreHalfSide = 16,
    [int] $Port = 25574,
    [string] $OutDir = '',
    [switch] $GenerateStructures,
    [switch] $DisableTickHash,
    [switch] $Force,
    [string] $VanillaBoot = 'C:\Users\bench\kestrel\vanilla-boot-test',
    [string] $AgentDir = 'C:\Users\bench\kestrel\harness\agent\build\libs'
)

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4.1+1'
$levelName = 'world'

# Refuse to boot over another Minecraft server. Bench PC is shared with the
# other harness workflows, and two servers on one box both skew the
# throughput measurement and fight over the world dirs. Filter on the command
# line, not the process name: Gradle daemons are java.exe too and are
# harmless. Found live on the first full-run attempt - an mspt-live.ps1 sweep
# from another session was mid-run and had to be waited out.
if (-not $Force) {
    $others = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
                Where-Object { "$($_.CommandLine)" -match 'server\.jar|--nogui' })
    if ($others.Count -gt 0) {
        Write-Output 'ABORT: a Minecraft server is already running on this box.'
        $others | ForEach-Object { Write-Output ("  pid={0} {1}" -f $_.ProcessId, $_.CommandLine) }
        Write-Output 'Re-run when it exits, or pass -Force to override.'
        exit 2
    }
    # Belt and braces: something may already be LISTENing on our port without
    # looking like a Minecraft server (a leftover, or a foreign process). This
    # does NOT close the race below - see the -Port help - it only catches a
    # listener that predates us.
    $busyPort = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
    if ($busyPort.Count -gt 0) {
        Write-Output ("ABORT: port {0} is already in LISTEN state on this box." -f $Port)
        $busyPort | ForEach-Object { Write-Output ("  {0}:{1} pid={2}" -f $_.LocalAddress, $_.LocalPort, $_.OwningProcess) }
        Write-Output 'Re-run when it frees, pass -Port <n>, or pass -Force to override.'
        exit 2
    }
}

if (-not $OutDir) {
    $suffix = if ($GenerateStructures) { 'struct' } else { 'nostruct' }
    $OutDir = "C:\Users\bench\kestrel\corpus\seed-$Seed-$suffix"
}
$work = Join-Path ([System.IO.Path]::GetTempPath()) "corpus-$Seed"
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force -Path $work | Out-Null
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

Set-Content -Path (Join-Path $work 'eula.txt') -Value 'eula=true'
$structFlag = if ($GenerateStructures) { 'true' } else { 'false' }
# NOTES: server.properties + world/ live side by side in the WORKING dir
# (run-harness.ps1 pattern): the server creates world/ itself on boot. A
# pre-made world/ dir without level.dat breaks dimension loading
# ('Overworld settings missing'). sync-chunk-writes=true so the backstop
# SIGKILL loses as little region data as possible.
#
# pause-when-empty-seconds=0 (NOT the vanilla default 60) - found live
# 2026-09-13 on the third pilot. This run is PLAYERLESS by construction, so
# the vanilla default pauses the server 60 s after boot: stdout.log logs
# 'Server empty for 60 seconds, pausing' and the tick counter FREEZES. The
# 300 s pilot's ticks.log ends with thousands of enter/exit lines all at
# tick_count=1199 and the counter never moves again. `schedule function
# corpus:wave2 60s` fires at tick 1200, so wave2 can NEVER fire on a paused
# server, and generation stops with it - this, not a schedule-from-load
# bug, is why CORPUS_WAVE2_APPLIED was missing from every pilot so far
# (corpus-notes.md open question 1 is answered: the schedule is fine, the
# server was asleep). 0 = never pause.
@"
server-port=$Port
online-mode=false
max-players=0
view-distance=10
simulation-distance=10
level-name=$levelName
level-seed=$Seed
level-type=minecraft\:normal
generate-structures=$structFlag
spawn-monsters=false
spawn-animals=false
gamemode=creative
difficulty=peaceful
sync-chunk-writes=true
pause-when-empty-seconds=0
"@ | Set-Content -Path (Join-Path $work 'server.properties')

# One line per chunk, mirroring chunkgen-watch.ps1:88-91. /forceload takes
# BLOCK coordinates, so x*16 lands exactly on the chunk corner. The
# `forceload add <from> <to>` rectangle form would be 1 line instead of
# 1089/1312, but the per-chunk form is the shape that has actually been
# proven on this harness; both stay under maxCommandChainLength (65536).
function Get-ForceloadLines([int] $halfSide, [int] $skipInner) {
    $lines = @()
    for ($x = -$halfSide; $x -le $halfSide; $x++) {
        for ($z = -$halfSide; $z -le $halfSide; $z++) {
            if ($skipInner -ge 0 -and [Math]::Abs($x) -le $skipInner -and [Math]::Abs($z) -le $skipInner) { continue }
            $lines += "forceload add $($x * 16) $($z * 16)"
        }
    }
    @($lines)
}

$dpFn = Join-Path $work "$levelName\datapacks\corpus\data\corpus\function"
New-Item -ItemType Directory -Force -Path $dpFn | Out-Null
Set-Content -Path (Join-Path $dpFn 'wave1.mcfunction') `
    -Value ((Get-ForceloadLines $CoreHalfSide -1) + @('say CORPUS_WAVE1_APPLIED') -join "`n")
Set-Content -Path (Join-Path $dpFn 'wave2.mcfunction') `
    -Value ((Get-ForceloadLines $HalfSide $CoreHalfSide) + @('say CORPUS_WAVE2_APPLIED') -join "`n")
# NO finish.mcfunction: `stop`/`save-all` are level-4 (LEVEL_OWNERS) and a
# datapack function is parsed with a level-2 source, so they cannot even be
# LOADED from here - see the CLEAN STOP note in the header. The shutdown is
# driven from the script over the server's console stdin instead.
Set-Content -Path (Join-Path $dpFn 'init.mcfunction') -Value (@(
        'function corpus:wave1',
        "schedule function corpus:wave2 ${WaveGapSeconds}s"
    ) -join "`n")
$tagDir = Join-Path $work "$levelName\datapacks\corpus\data\minecraft\tags\function"
New-Item -ItemType Directory -Force -Path $tagDir | Out-Null
Set-Content -Path (Join-Path $tagDir 'load.json') -Value '{"values":["corpus:init"]}'
# min_format/max_format, NOT min_inclusive/max_inclusive (26.2 schema).
Set-Content -Path (Join-Path $work "$levelName\datapacks\corpus\pack.mcmeta") -Value @'
{"pack":{"description":"kestrel G2 corpus forceload waves","pack_format":81,"min_format":81,"max_format":81}}
'@

$agentJar = Get-ChildItem $AgentDir -Filter 'kestrel-harness*.jar' `
    | Where-Object { $_.Name -notlike '*boot*' } | Select-Object -First 1 -ExpandProperty FullName
if (-not $agentJar) { throw "no agent jar in $AgentDir (build the harness agent first)" }
$serverJar = Join-Path $VanillaBoot 'server.jar'
if (-not (Test-Path $serverJar)) { throw "no server.jar in $VanillaBoot (this must be the bundler jar, not the nested server-26.2.jar)" }
$ticksLog = Join-Path $work 'ticks.log'
$log = Join-Path $work 'stdout.log'
$err = Join-Path $work 'stderr.log'

# Launch via a generated .bat so the child's stdout/stderr go straight to
# files (no PowerShell stream plumbing to deadlock on), while WE keep the
# stdin end of a pipe to drive `stop`. WriteAllText with explicit CRLF: a
# .bat with bare LF is at the mercy of cmd's line discipline.
$javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
$tickHashArg = if ($DisableTickHash) { ' -Dkestrel.harness.tickHash=false' } else { '' }
$bat = Join-Path $work 'boot.bat'
$batBody = "@echo off`r`n" +
    "`"$javaExe`" -javaagent:`"$agentJar=$ticksLog`" -Dkestrel.harness.perTickTraces=false$tickHashArg -jar `"$serverJar`" --nogui > `"$log`" 2> `"$err`"`r`n"
[System.IO.File]::WriteAllText($bat, $batBody, [System.Text.Encoding]::ASCII)

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $env:ComSpec
$psi.Arguments = '/c "' + $bat + '"'
$psi.WorkingDirectory = $work
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.CreateNoWindow = $true
$p = New-Object System.Diagnostics.Process
$p.StartInfo = $psi
[void]$p.Start()

# Wait for wave1 to land before starting the clock: boot (bundler unpack,
# datapack load, spawn prep) takes 20-40 s, and counting it would silently
# shrink the generation budget. The marker also proves the load function ran.
$bootDeadline = (Get-Date).AddSeconds(300)
while (-not $p.HasExited -and (Get-Date) -lt $bootDeadline) {
    if ((Test-Path $log) -and (@(Select-String -Path $log -Pattern 'CORPUS_WAVE1_APPLIED' -SimpleMatch -ErrorAction SilentlyContinue).Count -gt 0)) { break }
    Start-Sleep -Seconds 2
}
$genStart = Get-Date

# Generation window. The server is generating force-loaded chunks this whole
# time; `stop` goes in afterwards, so the window is the throughput budget.
# Skip the wait entirely if the server already died - a crashed run should
# fail in seconds, not after silently burning the whole window.
if ($p.HasExited) {
    Write-Output "WARN: server exited before wave1 (exit code $($p.ExitCode)) - skipping the generation window"
} else {
    Start-Sleep -Seconds $Seconds
}
$genSeconds = [Math]::Round(((Get-Date) - $genStart).TotalSeconds, 1)
$stopSent = $false
try {
    $p.StandardInput.WriteLine('stop')
    $p.StandardInput.Flush()
    $stopSent = $true
} catch {
    Write-Output "WARN: could not write 'stop' to the server console: $_"
}

# A clean stop saves every chunk on the way out, which can take a while on a
# 2401-chunk volume; 180 s is generous. The kill is a backstop only, and the
# manifest records when it fired.
$deadline = (Get-Date).AddSeconds(180)
while (-not $p.HasExited -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 2 }
$killedByBackstop = -not $p.HasExited
if ($killedByBackstop) {
    # /T, not $p.Kill(): the process we hold is cmd.exe, and java is its
    # CHILD. Killing cmd alone leaves an orphaned server holding the world
    # dir (and the next run's guard would rightly refuse to boot).
    & taskkill.exe /PID $p.Id /T /F 2>&1 | Out-Null
}
$p.WaitForExit()
Start-Sleep -Seconds 2

function Get-FileInfo([System.IO.FileInfo] $f) {
    [ordered]@{ name = $f.Name; bytes = $f.Length; sha256 = (Get-FileHash $f.FullName -Algorithm SHA256).Hash.ToLower() }
}

$csv = Join-Path $work 'chunkgen-watch.csv'
$probeCsv = Join-Path $work 'hash-probe.csv'
if (Test-Path $csv) { Copy-Item $csv (Join-Path $OutDir 'chunkgen-watch.csv') -Force }
if (Test-Path $probeCsv) { Copy-Item $probeCsv (Join-Path $OutDir 'hash-probe.csv') -Force }
if (Test-Path $ticksLog) { Copy-Item $ticksLog (Join-Path $OutDir 'ticks.log') -Force }
# stdout.log is the ONLY evidence for the wave markers below - keep it with
# the corpus rather than in a temp dir that the next run deletes.
if (Test-Path $log) { Copy-Item $log (Join-Path $OutDir 'stdout.log') -Force }

# WHERE THE REGIONS ACTUALLY ARE (measured 2026-09-13, pilot 2): 26.2 writes
# the overworld to world/dimensions/minecraft/overworld/region, NOT the flat
# world/region. The first pilot only globbed the flat path, so it reported
# regionCount=0 on a run that had in fact written 4 healthy region files
# (~1.1-1.3 MB each): the corpus was there and the copy step lost it. Global
# dirs still use the flat shape, hence the fallback rather than a hard swap.
$regionDir = $null
foreach ($cand in @(
        (Join-Path $work "$levelName\dimensions\minecraft\overworld\region"),
        (Join-Path $work "$levelName\region"))) {
    if (Test-Path $cand) { $regionDir = $cand; break }
}
$regions = @()
if ($regionDir) {
    Copy-Item (Join-Path $regionDir '*.mca') $OutDir -Force -ErrorAction SilentlyContinue
    $regions = @(Get-ChildItem $regionDir -Filter '*.mca' | ForEach-Object { Get-FileInfo $_ })
}
# Record which path actually won, so a future layout change is visible in the
# manifest instead of showing up as a silent regionCount=0.
$regionSource = if ($regionDir) { $regionDir.Replace($work, '').TrimStart('\') } else { $null }
# @() wrap: Get-Content | ... returns a bare scalar on a 1-line file.
$csvRows = 0
if (Test-Path $csv) { $csvRows = @(Get-Content $csv).Count - 1 }
$probeRows = 0
if (Test-Path $probeCsv) { $probeRows = @(Get-Content $probeCsv).Count - 1 }

$markers = [ordered]@{}
foreach ($w in @('wave1', 'wave2', 'shutdown')) {
    $pat = "CORPUS_$($w.ToUpper())_APPLIED"
    # No in-game marker for the shutdown (level-4 commands cannot run from a
    # function, see header) - the server's own shutdown line is the evidence.
    if ($w -eq 'shutdown') { $pat = 'Stopping the server' }
    $markers[$w] = (Test-Path $log) -and (@(Select-String -Path $log -Pattern $pat -SimpleMatch).Count -gt 0)
}
# A datapack function that fails to parse does NOT stop the server: it boots,
# looks healthy, and runs an empty world. Always count these explicitly.
$loadErrors = 0
if (Test-Path $log) { $loadErrors = @(Select-String -Path $log -Pattern 'Failed to load function' -SimpleMatch).Count }
# An empty-server PAUSE freezes the tick counter, which silently strands the
# scheduled wave2 (see the pause-when-empty-seconds=0 note on the
# server.properties heredoc). server.properties should prevent it; count it
# anyway, because a pause is invisible in every other field.
$pausedEmpty = 0
if (Test-Path $log) { $pausedEmpty = @(Select-String -Path $log -Pattern 'pausing' -SimpleMatch).Count }

$side = 2 * $HalfSide + 1
$manifestPath = Join-Path $OutDir 'manifest.json'
$manifest = [ordered]@{
    schema           = 3
    createdUtc       = (Get-Date).ToUniversalTime().ToString('o')
    seed             = $Seed
    halfSide         = $HalfSide
    coreHalfSide     = $CoreHalfSide
    port             = $Port
    chunkSquare      = "$($side)x$($side)"
    startChunks      = $side * $side
    structures       = [bool]$GenerateStructures
    seconds          = $Seconds
    generationSeconds = $genSeconds
    waveGapSeconds   = $WaveGapSeconds
    levelType        = 'minecraft:normal'
    tickHash         = -not [bool]$DisableTickHash
    serverJar        = Get-FileInfo (Get-Item $serverJar)
    agentJars        = @(Get-ChildItem $AgentDir -Filter 'kestrel-harness*.jar' | ForEach-Object { Get-FileInfo $_ })
    stopCommandSent  = $stopSent
    cleanStop        = (-not $killedByBackstop) -and $stopSent
    killedByBackstop = $killedByBackstop
    waveMarkers      = $markers
    datapackLoadErrors = $loadErrors
    pausedEmpty      = $pausedEmpty
    csvRows          = $csvRows
    probeRows        = $probeRows
    regionSource     = $regionSource
    regionCount      = $regions.Count
    regions          = $regions
    notes            = "G2 step-1 vanilla oracle corpus, structures=$structFlag, forceload waves (core $(2*$CoreHalfSide+1)x$(2*$CoreHalfSide+1) then ring to $($side)x$($side) ${WaveGapSeconds}s later). Shutdown driven over console stdin (datapack functions cannot call level-4 commands). Agent ran with -Dkestrel.harness.perTickTraces=false; tickHash=$(-not [bool]$DisableTickHash). Block hash cut points P1/P2/P3 are NOT in this manifest: they come from hash-probe.csv over these region files."
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -Path $manifestPath

# --- SELFCHECK: re-read the manifest we just wrote and assert it against the
# files actually on disk (counts + every region sha256 re-hashed).
$problems = @()
if (-not (Test-Path $manifestPath)) {
    $problems += 'manifest.json was not written'
} else {
    $m = Get-Content $manifestPath -Raw | ConvertFrom-Json
    if (@($m.regions).Count -ne $regions.Count) { $problems += "manifest regions=$(@($m.regions).Count) but copied=$($regions.Count)" }
    if ($m.regionCount -ne $regions.Count) { $problems += "manifest regionCount=$($m.regionCount) but copied=$($regions.Count)" }
    if ($m.csvRows -ne $csvRows) { $problems += "manifest csvRows=$($m.csvRows) but actual=$csvRows" }
    if ($m.probeRows -ne $probeRows) { $problems += "manifest probeRows=$($m.probeRows) but actual=$probeRows" }
    # [long], not [int]: 4242424242 exceeds Int32 and the cast THROWS, which
    # would abort the self-check at the end of a full run.
    if ([long]$m.seed -ne $Seed) { $problems += "manifest seed=$($m.seed) but requested=$Seed" }
    foreach ($r in @($m.regions)) {
        $copy = Join-Path $OutDir $r.name
        if (-not (Test-Path $copy)) { $problems += "region $($r.name) listed in manifest but missing from OutDir"; continue }
        $h = (Get-FileHash $copy -Algorithm SHA256).Hash.ToLower()
        if ($h -ne $r.sha256) { $problems += "sha256 mismatch for $($r.name)" }
    }
}
if ($loadErrors -gt 0) { $problems += "$loadErrors datapack function(s) failed to load - the world may be EMPTY, see stdout.log" }
if ($pausedEmpty -gt 0) { $problems += "the server PAUSED (empty-server pause) - the tick counter froze and wave2 could not fire; check pause-when-empty-seconds in the server.properties heredoc" }
if ($regions.Count -lt 1) { $problems += "no region files were produced (looked for $levelName\dimensions\minecraft\overworld\region and $levelName\region under the temp work dir; see stderr tail below)" }
if ($csvRows -lt 1) { $problems += 'chunkgen-watch.csv has no data rows' }
if (-not $markers['wave1']) { $problems += 'CORPUS_WAVE1_APPLIED never appeared in stdout.log - the load function did not run' }
if (-not $markers['wave2']) { $problems += 'CORPUS_WAVE2_APPLIED never appeared in stdout.log - scheduled wave may not have fired' }
if (-not $stopSent) { $problems += 'the stop command was never written to the server console' }
if ($killedByBackstop) { $problems += 'the server had to be killed - the shutdown did not complete in time (region data may be truncated)' }

Write-Output "=== corpus: $OutDir ==="
Write-Output ("waves: wave1=$($markers['wave1']) wave2=$($markers['wave2']) shutdown=$($markers['shutdown']) stopSent=$stopSent cleanStop=$(-not $killedByBackstop)")
Write-Output ("csv rows: $csvRows | probe rows: $probeRows | region files: $($regions.Count) | datapack load errors: $loadErrors | manifest: $manifestPath")
if ($problems.Count -gt 0) {
    Write-Output 'CORPUS_SELFCHECK FAIL'
    $problems | ForEach-Object { Write-Output "  - $_" }
    Get-Content $err -ErrorAction SilentlyContinue | Select-Object -Last 15
    exit 1
}
Write-Output "CORPUS_SELFCHECK PASS ($($regions.Count) region files, $csvRows csv rows, $probeRows probe rows)"
