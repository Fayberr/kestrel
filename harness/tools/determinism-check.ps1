<#
.SYNOPSIS
  Determinism self-check (conflict-rate-design.md section 2.4): run the
  harness twice against identical fresh worlds (same seed, same settings,
  no players), then diff the tick logs. Before any world_state_hash log is
  trusted for a parity decision, this must PASS.

.NOTES
  Compares phase + tick_count + world_state_hash per line index (not seq,
  which is a per-process monotonic counter and trivially identical by
  construction; not nanos, which is wall-clock and never expected to
  match). Runs sequentially (not in parallel) to avoid both instances
  fighting over the same default server port.
#>
param(
    [Parameter(Mandatory = $true)][string]$ServerJar,
    [Parameter(Mandatory = $true)][string]$AgentJar,
    [string]$BaseDir = "$env:USERPROFILE\kestrel\determinism-check",
    [long]$Seed = 4242424242,
    [int]$RunSeconds = 30,
    [int]$ViewDistance = 3,
    [string]$DatapackDir = $null,
    # World-generation passthrough (defaults match run-harness.ps1). Without
    # these, a corpus that needs flat/spawn flags cannot determinism-check
    # its own configuration.
    [string]$LevelType = 'minecraft:normal',
    [switch]$SpawnMobs = $false,
    [switch]$SpawnAnimals = $false,
    # Skip lines whose tick_count is <= this value before comparing. Added
    # 2026-09-10 to test the "early settling window" hypothesis for the
    # collision-stress corpus's 2-mismatch FAIL (see
    # preresearch/collision-path-analysis.md, "harness/corpus/collision-stress"
    # section, follow-up option (a)). Default 0 = no skipping, unchanged
    # behavior for the already-trusted plain-empty-world corpus.
    [int]$SkipTicks = 0
)

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$runHarness = Join-Path $scriptDir 'run-harness.ps1'

$run1Dir = Join-Path $BaseDir 'run1'
$run2Dir = Join-Path $BaseDir 'run2'

$harnessArgs = @{
    ServerJar = $ServerJar; AgentJar = $AgentJar; Seed = $Seed
    RunSeconds = $RunSeconds; ViewDistance = $ViewDistance
    DatapackDir = $DatapackDir; LevelType = $LevelType
    SpawnMobs = $SpawnMobs; SpawnAnimals = $SpawnAnimals
}

Write-Output '=== run 1 ==='
& $runHarness -WorldDir $run1Dir @harnessArgs

Write-Output '=== run 2 ==='
& $runHarness -WorldDir $run2Dir @harnessArgs

# @(...) forces an array even when Get-Content | Where-Object yields a
# single match, which otherwise returns a bare [Char]/[String] scalar and
# breaks .Split(',') below with a confusing "no method named Split" error.
# Enter-phase lines carry a 0-hash sentinel (TickLogger schema 6 is
# exit-only), so they are excluded: only exit lines hash world state.
$log1 = @(Get-Content (Join-Path $run1Dir 'ticks.log') | Where-Object { $_ -notmatch '^#' -and $_ -notmatch ',enter,' })
$log2 = @(Get-Content (Join-Path $run2Dir 'ticks.log') | Where-Object { $_ -notmatch '^#' -and $_ -notmatch ',enter,' })

if ($SkipTicks -gt 0) {
    $before1 = $log1.Count
    $before2 = $log2.Count
    $log1 = @($log1 | Where-Object { [int]($_.Split(',')[2]) -gt $SkipTicks })
    $log2 = @($log2 | Where-Object { [int]($_.Split(',')[2]) -gt $SkipTicks })
    Write-Output "SkipTicks=$SkipTicks : run1 $before1 -> $($log1.Count) lines, run2 $before2 -> $($log2.Count) lines"
}

$n = [Math]::Min($log1.Count, $log2.Count)
Write-Output "run1 lines=$($log1.Count) run2 lines=$($log2.Count) comparing first $n"
if ($log1.Count -ne $log2.Count) {
    Write-Output "DETERMINISM_CHECK FAIL (tick_count mismatch: run1=$($log1.Count) run2=$($log2.Count) exit lines)"
    exit 1
}

$mismatches = 0
for ($i = 0; $i -lt $n; $i++) {
    $a = $log1[$i].Split(',')
    $b = $log2[$i].Split(',')
    # columns: seq,phase,tick_count,nanos,world_state_hash
    if ($a[1] -ne $b[1] -or $a[2] -ne $b[2] -or $a[4] -ne $b[4]) {
        Write-Output "MISMATCH at line $i : run1=[$($log1[$i])] run2=[$($log2[$i])]"
        $mismatches++
        if ($mismatches -ge 10) {
            Write-Output '... stopping after 10 mismatches'
            break
        }
    }
}

if ($n -eq 0) {
    Write-Output 'DETERMINISM_CHECK FAIL (no comparable lines - a run produced zero ticks)'
    exit 1
} elseif ($mismatches -eq 0) {
    Write-Output "DETERMINISM_CHECK PASS ($n ticks compared)"
} else {
    Write-Output "DETERMINISM_CHECK FAIL ($mismatches mismatches out of $n)"
    exit 1
}
