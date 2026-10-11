<#
.SYNOPSIS
  Runs a kestrel-core headless check or bench on bench PC against the real
  vanilla 26.2 classes.

.DESCRIPTION
  Every class under core/src/vanilla is a standalone main() that boots the
  vanilla registry (Bootstrap.bootStrap()) and proves or benches one thing.
  They need the BUNDLER's extracted libraries/ on the classpath as well as
  the nested server jar - see harness/README.md and the "Standalone
  vanilla-class processes need the bundler's libraries/ dir too" note.

  Defaults match the bench PC layout. Nothing here is bench PC-specific except
  those defaults; pass -VanillaJar/-VanillaLibs to run elsewhere.

  Note: core/ on bench PC is an scp'd staging copy, NOT a git checkout. Sync
  core/src before trusting a run, or you will silently test stale code.

.EXAMPLE
  pwsh -File run-core-check.ps1 -Class dev.kestrel.ai.MobAiProofCheck

.EXAMPLE
  # Whole suite, exits non-zero if any check fails.
  pwsh -File run-core-check.ps1 -All
#>
[CmdletBinding(DefaultParameterSetName = 'Single')]
param(
    [Parameter(ParameterSetName = 'Single', Mandatory = $true, Position = 0)]
    [string] $Class,

    [Parameter(ParameterSetName = 'All', Mandatory = $true)]
    [switch] $All,

    [switch] $Bench,
    [string] $Classes     = 'C:\Users\bench\kestrel\core\build\classes\java\main',
    [string] $VanillaJar  = 'C:\Users\bench\kestrel\vanilla\server-26.2.jar',
    [string] $VanillaLibs = 'C:\Users\bench\kestrel\vanilla-boot-test\libraries',
    [string] $LogDir      = 'C:\Users\bench\kestrel',
    [string] $Heap        = '2G'
)

$ErrorActionPreference = 'Stop'

# Every proof check. Benches are separate: they print timings, not verdicts,
# and their numbers are machine-specific (never compare Pi rows to bench PC rows).
# Standalone checks (TickOrderCheck, SleepSetCheck, SleeperCheck,
# SleepLoopCheck, SpawnCapsCheck) touch no Minecraft classes and compile
# against javac with no vanilla jar. They are in the suite so a partial run
# stays honest, but the runner must route them around the vanilla classpath.
$StandaloneChecks = @(
    'dev.kestrel.blockentity.TickOrderCheck',
    'dev.kestrel.blockentity.SleepSetCheck',
    'dev.kestrel.blockentity.SleeperCheck',
    'dev.kestrel.blockentity.SleepLoopCheck',
    'dev.kestrel.spawn.SpawnCapsCheck'
)
$ProofClasses = @(
    'dev.kestrel.ai.MobAiProofCheck',
    'dev.kestrel.hopper.HopperTransferCheck',
    'dev.kestrel.hopper.HopperStateCheck',
    'dev.kestrel.redstone.RedstoneProofCheck',
    'dev.kestrel.redstone.AcWireProofCheck',
    'dev.kestrel.redstone.AcFlagCheck',
    'dev.kestrel.collision.KestrelCollideCheck',
    'dev.kestrel.collision.ShapeBoxCacheCheck',
    'dev.kestrel.block.FixtureRuntimeCheck',
    'dev.kestrel.blockentity.TickOrderCheck',
    'dev.kestrel.blockentity.SleepSetCheck',
    'dev.kestrel.blockentity.SleeperCheck',
    'dev.kestrel.blockentity.SleepLoopCheck',
    'dev.kestrel.spawn.SpawnCapsCheck',
    'dev.kestrel.loop.TickLoopCheck',
    'dev.kestrel.loop.PersistCheck',
    'dev.kestrel.net.NetCheck'
)
$BenchClasses = @(
    'dev.kestrel.ai.SensorScanBench',
    'dev.kestrel.hopper.HopperBench',
    'dev.kestrel.collision.CollisionBench'
)

$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $java)) {
    # The User-scope JAVA_HOME on bench PC has rotted to a stale scoop shim
    # before; fall back to the known-good Machine-level JDK 25.
    $java = 'C:\Program Files\Java\jdk-25.0.4.1+1\bin\java.exe'
}
foreach ($required in @($java, $Classes, $VanillaJar, $VanillaLibs)) {
    if (-not (Test-Path $required)) { throw "not found: $required" }
}

$libs = (Get-ChildItem -Recurse -Filter *.jar $VanillaLibs | ForEach-Object { $_.FullName }) -join ';'
$cp = "$Classes;$VanillaJar;$libs"

$targets = if ($All) { if ($Bench) { $BenchClasses } else { $ProofClasses } } else { @($Class) }

$failed = @()
foreach ($target in $targets) {
    $short = $target.Split('.')[-1]
    $out = Join-Path $LogDir "run-$short-stdout.txt"
    $err = Join-Path $LogDir "run-$short-stderr.txt"
    $targetCp = if ($StandaloneChecks -contains $target) { $Classes } else { $cp }
    $p = Start-Process -FilePath $java `
        -ArgumentList @("-Xmx$Heap", '-cp', $targetCp, $target) `
        -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $out -RedirectStandardError $err
    Write-Output "=== $target (exit $($p.ExitCode)) ==="
    Get-Content $out -ErrorAction SilentlyContinue
    if ($p.ExitCode -ne 0) {
        $failed += $target
        Write-Output "--- stderr (tail 20) ---"
        # The real cause of an apparent JVM crash is usually a plain Java
        # exception down here; always read it before believing the headline.
        Get-Content $err -ErrorAction SilentlyContinue | Select-Object -Last 20
    }
}

if ($failed.Count -gt 0) {
    Write-Output ""
    Write-Output "FAILED: $($failed -join ', ')"
    exit 1
}
Write-Output ""
Write-Output "All $($targets.Count) target(s) OK."
