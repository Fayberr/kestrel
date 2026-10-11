<#
.SYNOPSIS
  M0 gate run: SAME vanilla 26.2 boot records JFR + ASM stopwatch CSV together.

.DESCRIPTION
  Boots real vanilla with the kestrel javaagent (ASM chunkgen-watch.csv)
  AND -XX:StartFlightRecording (recording.jfr with minecraft.ChunkGeneration
  events), forceloads a square, then leaves both artifacts in the work dir.
  The Pi-side join is: python3 harness/tools/jfr-baseline.py
    --jfr-text <(jfr print --events minecraft.ChunkGeneration recording.jfr)
    --match chunkgen-watch.csv
  Gate (build-order row M0): inline ASM within ±10% of JFR per joined chunk.

  Usage (bench PC, FOREGROUND ssh):
    pwsh -File harness/tools/jfr-watch.ps1 [-Seed 4242424242] [-Radius 4] [-Seconds 180]

  Mirrors chunkgen-watch.ps1 layout (no pre-made world/, datapacks under
  world/datapacks, forceload in BLOCK coords) plus JFR flags.
#>
param(
    [long] $Seed = 4242424242,
    [int] $Radius = 4,
    [int] $Seconds = 180,
    [string] $VanillaBoot = 'C:\Users\bench\kestrel\vanilla-boot-test',
    [string] $AgentDir = 'C:\Users\bench\kestrel\harness\agent\build\libs'
)

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4.1+1'

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("jfrwatch-" + $Seed)
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force -Path $work | Out-Null
$world = $work
Set-Content -Path (Join-Path $world 'eula.txt') -Value 'eula=true'
@"
server-port=25574
online-mode=false
max-players=0
view-distance=10
simulation-distance=10
spawn-monsters=false
spawn-animals=false
gamemode=creative
difficulty=peaceful
level-seed=$Seed
level-type=minecraft\:normal
generate-structures=true
"@ | Set-Content -Path (Join-Path $world 'server.properties')

$agentJar = Get-ChildItem $AgentDir -Filter 'kestrel-harness*.jar' `
    | Where-Object { $_.Name -notlike '*boot*' } | Select-Object -First 1 -ExpandProperty FullName
if (-not $agentJar) { throw "no agent jar in $AgentDir (build the harness agent first)" }
$ticksLog = Join-Path $work 'ticks.log'
$jfrFile = Join-Path $work 'recording.jfr'

$dp = Join-Path $world 'world\datapacks\jfrwatch\data\jfrwatch\function'
New-Item -ItemType Directory -Force -Path $dp | Out-Null
# /forceload takes BLOCK coords: chunk corners so radius R covers (2R+1)^2.
$coords = for ($x = -$Radius; $x -le $Radius; $x++) {
    for ($z = -$Radius; $z -le $Radius; $z++) { "forceload add $($x * 16) $($z * 16)" }
}
Set-Content -Path (Join-Path $dp 'init.mcfunction') -Value ($coords -join "`n")
$tagDir = Join-Path $world 'world\datapacks\jfrwatch\data\minecraft\tags\function'
New-Item -ItemType Directory -Force -Path $tagDir | Out-Null
Set-Content -Path (Join-Path $tagDir 'load.json') -Value '{"values":["jfrwatch:init"]}'
Set-Content -Path (Join-Path $world 'world\datapacks\jfrwatch\pack.mcmeta') -Value @'
{"pack":{"description":"jfr watch forceload","pack_format":81,"min_format":81,"max_format":81}}
'@

$serverJar = Join-Path $VanillaBoot 'server.jar'
$log = Join-Path $work 'stdout.log'
$err = Join-Path $work 'stderr.log'
# ChunkGeneration JFR events fire only when BOTH hold (javap-verified 2026-09-13):
# (1) ChunkGenerationEvent.TYPE.isEnabled (a JVM recording must enable the
#     minecraft.* custom events - an external -XX:StartFlightRecording with
#     settings=profile does NOT list them, hence 0 events); (2) the server
#     starts its OWN recording via --jfrProfile, which loads the bundled
#     flightrecorder-config.jfc (CUSTOM_EVENTS incl. ChunkGenerationEvent,
#     JfrProfiler.java) and dumps on exit. So: pass --jfrProfile to the
#     server (it writes its own jfr file into the world dir), no JVM flags.
$p = Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin\java.exe') `
    -ArgumentList @("-javaagent:$agentJar=$ticksLog",
        '-jar', $serverJar, '--nogui', '--jfrProfile') `
    -WorkingDirectory $world -NoNewWindow -PassThru `
    -RedirectStandardOutput $log -RedirectStandardError $err
try {
    Start-Sleep -Seconds $Seconds
} finally {
    Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
}
$csv = Join-Path $work 'chunkgen-watch.csv'
Write-Output "=== csv: $csv ==="
if (Test-Path $csv) {
    Write-Output ("rows: " + ((Get-Content $csv | Measure-Object -Line).Lines - 1))
} else {
    Write-Output "NO CSV (check $err tail):"
    Get-Content $err -ErrorAction SilentlyContinue | Select-Object -Last 15
}
# The server writes its own recording (DATE-named .jfr) into the world dir
# on exit (setDumpOnExit, JfrProfiler.java) - locate it, don't expect ours.
$jfrFound = Get-ChildItem $world -Filter '*.jfr' -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
Write-Output "=== jfr (server --jfrProfile dump) ==="
if ($jfrFound) {
    Write-Output ("jfr file: " + $jfrFound.FullName + " bytes: " + $jfrFound.Length)
} else {
    Write-Output "NO JFR FILE (check $err tail):"
    Get-Content $err -ErrorAction SilentlyContinue | Select-Object -Last 15
}
