<#
.SYNOPSIS
  MSPT analyzer: implements docs/BENCHMARKS.md section 2B exactly.

.DESCRIPTION
  Reads a harness ticks.log (columns seq,phase,tick_count,nanos,
  world_state_hash) and reports median/p95 MSPT per the documented
  methodology: exit-phase lines only (enter lines carry the 0-hash
  sentinel and no timing meaning), SkipTicks 5 warmup discard, and
  raw-99th-percentile GC-outlier trim before computing figures.
  Prints one MEDIAN line plus the paired-tick count, or FAIL lines
  when the log cannot support a reading.

.EXAMPLE
  pwsh -File mspt-analyze.ps1 C:\path\to\ticks.log
  pwsh -File mspt-analyze.ps1 run1\ticks.log run2\ticks.log -SkipTicks 5
#>
param(
    [Parameter(Mandatory = $true)][string]$Log1,
    [string]$Log2 = $null,
    [int]$SkipTicks = 5
)

$ErrorActionPreference = 'Stop'

function Read-Mspt([string]$path) {
    $ms = @()
    foreach ($line in (Get-Content $path | Where-Object { $_ -notmatch '^#' -and $_.Trim() -ne '' })) {
        $parts = $line.Split(',')
        if ($parts.Count -lt 5) { continue }
        if ($parts[1] -ne 'exit') { continue }
        $tick = [int]$parts[2]
        if ($tick -le $SkipTicks) { continue }
        $ms += ([double]$parts[3]) / 1e6
    }
    return $ms
}

function Get-Pct([array]$sorted, [double]$p) {
    if ($sorted.Count -eq 0) { return $null }
    $rank = [Math]::Ceiling($p / 100.0 * $sorted.Count) - 1
    if ($rank -lt 0) { $rank = 0 }
    if ($rank -ge $sorted.Count) { $rank = $sorted.Count - 1 }
    return $sorted[$rank]
}

function Report([array]$ms, [string]$label) {
    # NOTE (WI-10 root cause): display output uses [Console]::WriteLine, NOT
    # Write-Output. Write-Output inside Report() merges into the function's
    # return value and is captured by the `$m = Report ...` assignment at the
    # call site, so the script exited 0 printing NOTHING. [Console]::WriteLine
    # bypasses the success stream and always reaches stdout. Same family as
    # the @(...) single-match gotcha documented in determinism-check.ps1.
    if ($ms.Count -eq 0) {
        [Console]::WriteLine("MSPT_ANALYZE FAIL ($label : no exit-phase rows past SkipTicks=$SkipTicks)")
        return $null
    }
    $sorted = @($ms | Sort-Object)
    $p99 = Get-Pct $sorted 99
    $trimmed = @($sorted | Where-Object { $_ -le $p99 })
    $n = $trimmed.Count
    if ($n % 2 -eq 1) { $med = $trimmed[[Math]::Floor($n / 2)] }
    else { $med = [math]::Round(($trimmed[$n / 2 - 1] + $trimmed[$n / 2]) / 2, 3) }
    $p95 = Get-Pct $trimmed 95
    [Console]::WriteLine(("MSPT {0}: paired={1} trimmed={2} median={3}ms p95={4}ms (SkipTicks={5}, p99trim)" -f $label, $ms.Count, $n, $med, $p95, $SkipTicks))
    return $med
}

# ,(...) wraps the call result so a single-element Read-Mspt return does not
# unroll: without it, `$m.Count` on a scalar loses the pairing the MEDIAN
# line reports (same array-unrolling family as determinism-check.ps1).
# @() wrap: FAIL path returns $null, which @() coerces to an empty array
# (Count 0); success yields a 1-element array. Check .Count, not -ne $null:
# an empty array is non-null, so `$null -ne $m` is True even on FAIL.
$m1 = @(Report @(Read-Mspt $Log1) $Log1)
if ($Log2) {
    $m2 = @(Report @(Read-Mspt $Log2) $Log2)
    if (($m1.Count -gt 0) -and ($m2.Count -gt 0)) {
        $delta = [math]::Round($m2[0] - $m1[0], 3)
        [Console]::WriteLine(("MSPT delta (log2-log1): {0}ms" -f $delta))
    }
}
