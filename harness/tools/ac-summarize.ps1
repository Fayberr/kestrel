<#
.SYNOPSIS
  Summarizes ac-proof.log files (AcProof, schema v1).

.DESCRIPTION
  Reads one or two AC proofs and prints the propagation verdict: ticks,
  settling ticks (two-tick network lock not yet held - legitimately
  mid-propagation, not a failure), settled ticks, and total DIVERGE lines.
  Zero DIVERGE over the settled redstone-line window IS the live proof of
  the propagation core (power algebra only - update order/counts stay
  proven headlessly by AcWireProofCheck).

  Display uses [Console]::WriteLine, not Write-Output: Write-Output inside
  a function whose return value is assigned merges into that return value
  and the script prints nothing (mspt-analyze.ps1 WI-10 root cause).

.EXAMPLE
  pwsh -File ac-summarize.ps1 C:\path\to\ac-proof.log

.EXAMPLE
  pwsh -File ac-summarize.ps1 run1\ac-proof.log run2\ac-proof.log -SkipTicks 5
#>
param(
    [Parameter(Mandatory = $true)][string]$Log1,
    [string]$Log2 = $null,
    [int]$SkipTicks = 0
)

$ErrorActionPreference = 'Stop'

function Read-Ac([string]$path) {
    $rows = @()
    $diverges = 0
    foreach ($line in (Get-Content $path | Where-Object { $_ -notmatch '^#' -and $_.Trim() -ne '' })) {
        if ($line -match 'DIVERGE') { $diverges++; continue }
        $parts = $line.Split(',')
        $tick = [int]$parts[0]
        if ($tick -le $SkipTicks) { continue }
        $rows += [pscustomobject]@{ Tick = $tick; Verdict = $parts[1]; Wires = [int]$parts[2] }
    }
    return @{ Rows = $rows; Diverges = $diverges }
}

function Summarize($data, [string]$label) {
    $rows = $data.Rows
    [Console]::WriteLine("=== $label ($($rows.Count) ticks, DIVERGE lines: $($data.Diverges)) ===")
    if ($rows.Count -eq 0) { [Console]::WriteLine('(no rows)'); return }
    $settling = @($rows | Where-Object { $_.Verdict -eq 'settling' }).Count
    $settled = $rows.Count - $settling
    [Console]::WriteLine("settling (mid-propagation, no verdict): $settling; settled: $settled")
    if ($data.Diverges -eq 0) {
        [Console]::WriteLine("AC_PROOF PASS ($settled settled ticks, zero DIVERGE)")
    } else {
        [Console]::WriteLine("AC_PROOF FAIL ($($data.Diverges) DIVERGE lines)")
    }
}

$data1 = Read-Ac $Log1
Summarize $data1 $Log1

if ($Log2) {
    $data2 = Read-Ac $Log2
    Summarize $data2 $Log2
    # tick-by-tick comparison: same tick sequence, same verdict + wire count
    $rows1 = $data1.Rows; $rows2 = $data2.Rows
    $n = [Math]::Min($rows1.Count, $rows2.Count)
    $mism = 0
    for ($i = 0; $i -lt $n; $i++) {
        $a = $rows1[$i]; $b = $rows2[$i]
        if (($a.Tick -ne $b.Tick) -or ($a.Verdict -ne $b.Verdict) -or ($a.Wires -ne $b.Wires)) {
            [Console]::WriteLine("AC_MISMATCH at index {0}: [{1}] vs [{2}]" -f $i, $a.Tick, $b.Tick)
            $mism++
            if ($mism -ge 10) { [Console]::WriteLine('... stopping after 10 mismatches'); break }
        }
    }
    if ($n -eq 0) {
        [Console]::WriteLine('AC_COMPARE FAIL (no comparable rows)')
    } elseif ($mism -eq 0) {
        [Console]::WriteLine("AC_COMPARE PASS ($n ticks compared)")
    } else {
        [Console]::WriteLine("AC_COMPARE FAIL ($mism mismatches)")
    }
}
