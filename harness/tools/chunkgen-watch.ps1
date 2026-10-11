<#
.SYNOPSIS
  Vanilla 26.2 chunk-gen stopwatch: per-stage ms/chunk via the tick harness.

.DESCRIPTION
  Boots a REAL vanilla 26.2 server with the kestrel javaagent (which now
  times every ChunkStatusTasks.generate* stage per chunk and writes
  chunkgen-watch.csv next to the tick log), pre-generates chunks with a
  forceload spiral (no players, no redstone, no mobs - pure terrain cost),
  then prints the median/p95 per-stage table.

  Why this shape instead of a standalone probe: chunk stages need a live
  ServerLevel (WorldGenRegion, RandomState, StructureManager, light
  engine). Booting the real server gives all of that for free, and the
  javaagent hook is the same mechanism as every other harness probe.

  Usage (bench PC, FOREGROUND ssh - detached launches sit silent on failure):
    pwsh -File harness/tools/chunkgen-watch.ps1 [-Seed 4242424242] [-Radius 4] [-Seconds 120]

  Output: per-stage median/p95 ms/chunk table + csv path.

.PARAMETER Seed
  World seed. Default 4242424242.

.PARAMETER Radius
  Forceload square half-side in chunks (radius 4 = 9x9 = 81 chunks).
  Default 4.

.PARAMETER Seconds
  How long to let generation run before reading the csv. Default 120.

.PARAMETER VanillaBoot
  Dir with server.jar (bundler) + libraries. Default C:\Users\bench\kestrel\vanilla-boot-test.

.PARAMETER AgentDir
  Dir with the built agent jars. Default C:\Users\bench\kestrel\harness\agent\build\libs.
#>
param(
    [long] $Seed = 4242424242,
    [int] $Radius = 4,
    [int] $Seconds = 120,
    [string] $VanillaBoot = 'C:\Users\bench\kestrel\vanilla-boot-test',
    [string] $AgentDir = 'C:\Users\bench\kestrel\harness\agent\build\libs'
)

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4.1+1'

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("cgwatch-" + $Seed)
New-Item -ItemType Directory -Force -Path $work | Out-Null
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force -Path $work | Out-Null
$world = $work
Set-Content -Path (Join-Path $world 'eula.txt') -Value 'eula=true'
# NOTE: server.properties + world/ live side by side in the WORKING dir
# (run-harness.ps1 pattern): the server creates world/ itself on boot.
# A pre-made world/ dir without level.dat breaks dimension loading
# ('Overworld settings missing').
@"
server-port=25574
online-mode=false
max-players=0
view-distance=10
spawn-monsters=false
spawn-animals=false
gamemode=creative
difficulty=peaceful
level-seed=$Seed
level-type=minecraft\:normal
generate-structures=true
spawn-monsters=false
spawn-animals=false
view-distance=10
simulation-distance=10
difficulty=peaceful
gamemode=creative
max-players=0
"@ | Set-Content -Path (Join-Path $world 'server.properties')

$agentJar = Get-ChildItem $AgentDir -Filter 'kestrel-harness*.jar' `
    | Where-Object { $_.Name -notlike '*boot*' } | Select-Object -First 1 -ExpandProperty FullName
if (-not $agentJar) { throw "no agent jar in $AgentDir (build the harness agent first)" }
$ticksLog = Join-Path $work 'ticks.log'

# Forceload function: spiral-free square, entry point only (single-fire).
$dp = Join-Path $world 'world\datapacks\cgwatch\data\cgwatch\function'
New-Item -ItemType Directory -Force -Path $dp | Out-Null
# /forceload takes BLOCK coordinates, not chunk coords:
# multiply out to chunk corners so radius R really covers (2R+1)^2 chunks.
$coords = for ($x = -$Radius; $x -le $Radius; $x++) {
    for ($z = -$Radius; $z -le $Radius; $z++) { "forceload add $($x * 16) $($z * 16)" }
}
Set-Content -Path (Join-Path $dp 'init.mcfunction') -Value ($coords -join "`n")
$tagDir = Join-Path $world 'world\datapacks\cgwatch\data\minecraft\tags\function'
New-Item -ItemType Directory -Force -Path $tagDir | Out-Null
Set-Content -Path (Join-Path $tagDir 'load.json') -Value '{"values":["cgwatch:init"]}'
Set-Content -Path (Join-Path $world 'world\datapacks\cgwatch\pack.mcmeta') -Value @'
{"pack":{"description":"chunkgen watch forceload","pack_format":81,"min_format":81,"max_format":81}}
'@

$serverJar = Join-Path $VanillaBoot 'server.jar'
$log = Join-Path $work 'stdout.log'
$err = Join-Path $work 'stderr.log'
$p = Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin\java.exe') `
    -ArgumentList @("-javaagent:$agentJar=$ticksLog", '-jar', $serverJar, '--nogui') `
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
    Get-Content $csv | Select-Object -First 5
    Write-Output "..."
    Write-Output ("rows: " + ((Get-Content $csv | Measure-Object -Line).Lines - 1))
} else {
    Write-Output "NO CSV (check $err tail):"
    Get-Content $err -ErrorAction SilentlyContinue | Select-Object -Last 15
}
