<#
.SYNOPSIS
  Summarizes power-trace.log files (PowerTrace, schema v1).

.DESCRIPTION
  Reads one or two power traces and prints per-run wire-count stability:
  ticks, wire-count min/max, ticks where the wire set or any power changed
  vs the previous tick, and total power transitions. With two logs (two
  runs of the same corpus), also compares them tick-by-tick like
  determinism-check.ps1 does for ticks.log: same tick_count sequence and
  same per-wire powers.

  Display uses [Console]::WriteLine, not Write-Output: Write-Output inside
  a function whose return value is assigned merges into that return value
  and the script prints nothing (mspt-analyze.ps1 WI-10 root cause).

.EXAMPLE
  pwsh -File power-summarize.ps1 C:\path\to\power-trace.log

.EXAMPLE
  pwsh -File power-summarize.ps1 run1\power-trace.log run2\power-trace.log -SkipTicks 5
#>
param(
    [Parameter(Mandatory = $true)][string]$Log1,
    [string]$Log2 = $null,
    [int]$SkipTicks = 0
)

$ErrorActionPreference = 'Stop'

function Read-Power([string]$path) {
    $rows = @()
    foreach ($line in (Get-Content $path | Where-Object { $_ -notmatch '^#' -and $_.Trim() -ne '' })) {
        $parts = $line.Split(',')
        $tick = [int]$parts[0]
        if ($tick -le $SkipTicks) { continue }
        # tick,exit,wirecount,x,y,z:power;...
        $count = [int]$parts[2]
        $wires = @{}
        if ($parts.Count -gt 3) {
            foreach ($cell in ($parts[3..($parts.Count - 1)] -join ',').Split(';')) {
                if ($cell.Trim() -eq '') { continue }
                $cp = $cell.LastIndexOf(':')
                if ($cp -lt 0) { continue }
                $wires[$cell.Substring(0, $cp)] = [int]$cell.Substring($cp + 1)
            }
        }
        $rows += [pscustomobject]@{ Tick = $tick; Count = $count; Wires = $wires }
    }
    return $rows
}

function Summarize([array]$rows, [string]$label) {
    [Console]::WriteLine("=== $label ($($rows.Count) ticks) ===")
    if ($rows.Count -eq 0) { [Console]::WriteLine('(no rows)'); return }
    $min = ($rows | ForEach-Object { $_.Count } | Measure-Object -Minimum).Minimum
    $max = ($rows | ForEach-Object { $_.Count } | Measure-Object -Maximum).Maximum
    [Console]::WriteLine("wire count: min=$min max=$max")
    $changedTicks = 0
    $transitions = 0
    $prev = $null
    foreach ($r in $rows) {
        if ($prev -ne $null) {
            $keys = @($prev.Wires.Keys) + @($r.Wires.Keys) | Sort-Object -Unique
            $changed = 0
            foreach ($k in $keys) {
                if ($prev.Wires[$k] -ne $r.Wires[$k]) { $changed++ }
            }
            if ($changed -gt 0) { $changedTicks++; $transitions += $changed }
        }
        $prev = $r
    }
    [Console]::WriteLine("ticks with wire/power change vs previous: $changedTicks (total transitions: $transitions)")
}

$rows1 = Read-Power $Log1
Summarize $rows1 $Log1

if ($Log2) {
    $rows2 = Read-Power $Log2
    Summarize $rows2 $Log2
    # tick-by-tick comparison: same tick sequence, same per-wire powers
    $n = [Math]::Min($rows1.Count, $rows2.Count)
    $mism = 0
    for ($i = 0; $i -lt $n; $i++) {
        $a = $rows1[$i]; $b = $rows2[$i]
        $same = ($a.Tick -eq $b.Tick) -and ($a.Count -eq $b.Count)
        if ($same) {
            $keys = @($a.Wires.Keys) + @($b.Wires.Keys) | Sort-Object -Unique
            foreach ($k in $keys) {
                if ($a.Wires[$k] -ne $b.Wires[$k]) { $same = $false; break }
            }
        }
        if (-not $same) {
            [Console]::WriteLine("POWER_MISMATCH at index {0}: [{1}] vs [{2}]" -f $i, $a.Tick, $b.Tick)
            $mism++
            if ($mism -ge 10) { [Console]::WriteLine('... stopping after 10 mismatches'); break }
        }
    }
    if ($n -eq 0) {
        [Console]::WriteLine('POWER_COMPARE FAIL (no comparable rows)')
    } elseif ($mism -eq 0) {
        [Console]::WriteLine("POWER_COMPARE PASS ($n ticks compared)")
    } else {
        [Console]::WriteLine("POWER_COMPARE FAIL ($mism mismatches)")
    }
}
