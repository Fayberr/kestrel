<#
.SYNOPSIS
  Launch vanilla + kestrel-harness-agent against a fresh throwaway world,
  headless, hidden window, bounded wall-clock run. See harness/README.md.

.NOTES
  Runs SYNCHRONOUSLY in the invoking shell (Start-Process -Wait semantics
  via WaitForExit with a timeout) - deliberately NOT using the
  Task-Scheduler-detachment pattern from the original boot validation,
  because that pattern exists to survive an SSH session closing while a
  process keeps running unattended for a long time. A bounded run (tens of
  seconds) doesn't need that: keep the invoking SSH/PowerShell call itself
  alive for the run's duration and there is no detached-process-survives-
  session-close problem to solve. Uses Start-Process -WindowStyle Hidden
  with file-redirected stdout/stderr - the exact call shape already proven
  not to pop a visible window during the original vanilla-boot validation.
#>
param(
    [Parameter(Mandatory = $true)][string]$ServerJar,
    [Parameter(Mandatory = $true)][string]$AgentJar,
    [Parameter(Mandatory = $true)][string]$WorldDir,
    [string]$JavaHome = 'C:\Program Files\Java\jdk-25.0.4.1+1',
    [long]$Seed = 4242424242,
    [int]$RunSeconds = 30,
    [int]$ViewDistance = 3,
    # World generation for the throwaway world. Default normal+structures
    # (matches all historical harness runs). Corpora whose measurement is
    # polluted by nearby vanilla structures (block-entity-census: trial
    # chambers, ancient cities, mineshaft spawners all tick inside the
    # census) pass -LevelType 'minecraft:flat' for a clean volume.
    # generate-structures=false is written whenever the level type is flat.
    # Flat needs explicit generator settings; the bedrock+dirt+grass
    # default preset is used.
    [string]$LevelType = 'minecraft:normal',
    # Optional path to a datapack directory (e.g. harness/corpus/collision-stress)
    # to pre-seed into the fresh world before first launch, so its
    # minecraft:load function tag runs automatically on world creation.
    [string]$DatapackDir = $null,
    # Natural spawning flags (2026-09-11, step-6 measurement leg): default
    # OFF, matching every historical harness run (Entity.random is unseeded,
    # playerless runs never despawn). The spawn-census corpus passes -SpawnMobs
    # -SpawnAnimals for its leg-B statistical soak; leg A (bit-exact,
    # createState walk only) runs the defaults.
    [switch]$SpawnMobs = $false,
    [switch]$SpawnAnimals = $false
)

$ErrorActionPreference = 'Stop'

if (Test-Path $WorldDir) {
    Remove-Item -Recurse -Force $WorldDir
}
New-Item -ItemType Directory -Path $WorldDir | Out-Null

Set-Content -Path (Join-Path $WorldDir 'eula.txt') -Value 'eula=true'

$genStructures = if ($LevelType -eq 'minecraft:flat') { 'false' } else { 'true' }
$levelTypeEscaped = $LevelType -replace ':', '\:'
$generatorSettingsLine = ''
if ($LevelType -eq 'minecraft:flat') {
    # Flat worlds need explicit generator settings or the server falls back
    # to normal generation. Default flat preset: bedrock, dirt, grass.
    # Written as generator-settings (the key the dedicated server reads;
    # level-generator-settings is ignored and the server rewrites it to
    # generator-settings={} on boot).
    $generatorSettingsLine = 'generator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"structures":{}}'
}
$spawnMonstersValue = if ($SpawnMobs) { 'true' } else { 'false' }
$spawnAnimalsValue = if ($SpawnAnimals) { 'true' } else { 'false' }
@"
online-mode=false
level-seed=$Seed
level-type=$levelTypeEscaped
generate-structures=$genStructures
$generatorSettingsLine
# Natural mob spawning defaults OFF (2026-09-10): Entity.random is unseeded
# per entity (confirmed in decompiled Entity.java), so every spawn injects
# entropy into the tick hash AND grows the per-tick entity walk
# (getAllEntities + UUID sort) without bound - despawn needs players, which
# a playerless run never has. Corpora that need entities spawn their own
# explicitly (collision-stress summons 40 zombies itself). Step-6 leg B
# opts in via -SpawnMobs/-SpawnAnimals (statistical soak, never bit-exact).
spawn-monsters=$spawnMonstersValue
spawn-animals=$spawnAnimalsValue
view-distance=$ViewDistance
simulation-distance=$ViewDistance
difficulty=normal
gamemode=survival
max-players=0
"@ | Set-Content -Path (Join-Path $WorldDir 'server.properties')

if ($DatapackDir) {
    $dpName = Split-Path $DatapackDir -Leaf
    $dpTarget = Join-Path $WorldDir "world\datapacks\$dpName"
    New-Item -ItemType Directory -Path $dpTarget -Force | Out-Null
    Copy-Item -Path (Join-Path $DatapackDir '*') -Destination $dpTarget -Recurse -Force
}

$javaExe = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path $javaExe)) {
    throw "java.exe not found at $javaExe - pass -JavaHome explicitly."
}

$ticksLog = Join-Path $WorldDir 'ticks.log'
$stdoutLog = Join-Path $WorldDir 'stdout.log'
$stderrLog = Join-Path $WorldDir 'stderr.log'
$argList = @("-javaagent:$AgentJar=$ticksLog", '-jar', $ServerJar, '--nogui')

$proc = Start-Process -FilePath $javaExe -ArgumentList $argList -WorkingDirectory $WorldDir `
    -WindowStyle Hidden -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog -PassThru

$finishedNaturally = $proc.WaitForExit($RunSeconds * 1000)
if (-not $finishedNaturally) {
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 500
}

$lineCount = 0
if (Test-Path $ticksLog) {
    $lineCount = (Get-Content $ticksLog | Measure-Object -Line).Lines
}

Write-Output "RUN_COMPLETE worldDir=$WorldDir ticksLogLines=$lineCount finishedNaturally=$finishedNaturally"
