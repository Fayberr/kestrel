<#
.SYNOPSIS
  Summarizes block-entity-census.log files (BlockEntityCensus, schema v1).

.DESCRIPTION
  Reads one or two census logs and prints per-type totals: TICKERS SEEN
  (sum of per-tick counts), TICKS PRESENT, TICKS IDLE, and the idle
  fraction. With two logs (two runs of the same corpus), also compares
  them tick-by-tick like determinism-check.ps1 does for ticks.log:
  same tick_count sequence and same per-type total/idle columns.

  Why sums AND tick-presence: a sign asleep the whole run contributes 1
  to TICKS PRESENT on every tick; the sum column weights by population.
  Both matter: the sum sizes the preamble win, the presence shows
  stability (an entry flickering idle/non-idle across ticks is a smell).

.EXAMPLE
  pwsh -File census-summarize.ps1 C:\path\to\block-entity-census.log

.EXAMPLE
  pwsh -File census-summarize.ps1 run1\block-entity-census.log run2\block-entity-census.log -SkipTicks 5
#>
param(
    [Parameter(Mandatory = $true)][string]$Log1,
    [string]$Log2 = $null,
    [int]$SkipTicks = 0
)

$ErrorActionPreference = 'Stop'

function Read-Census([string]$path) {
    $rows = @()
    foreach ($line in (Get-Content $path | Where-Object { $_ -notmatch '^#' -and $_.Trim() -ne '' })) {
        $parts = $line.Split(',')
        $tick = [int]$parts[0]
        if ($tick -le $SkipTicks) { continue }
        $tickers = [int]$parts[1]
        $types = @{}
        for ($i = 2; $i -lt $parts.Count; $i++) {
            # cells look like name=total/idle
            $cell = $parts[$i]
            $eq = $cell.IndexOf('=')
            $sl = $cell.IndexOf('/')
            if ($eq -lt 0 -or $sl -lt 0) { continue }
            $name = $cell.Substring(0, $eq)
            $total = [int]$cell.Substring($eq + 1, $sl - $eq - 1)
            $idle = [int]$cell.Substring($sl + 1)
            $types[$name] = @($total, $idle)
        }
        $rows += [pscustomobject]@{ Tick = $tick; Tickers = $tickers; Types = $types }
    }
    return $rows
}

function Summarize([array]$rows, [string]$label) {
    Write-Output "=== $label ($($rows.Count) ticks) ==="
    if ($rows.Count -eq 0) { Write-Output '(no rows)'; return }
    $agg = @{}
    $maxTickers = 0
    foreach ($r in $rows) {
        if ($r.Tickers -gt $maxTickers) { $maxTickers = $r.Tickers }
        foreach ($name in $r.Types.Keys) {
            if (-not $agg.ContainsKey($name)) { $agg[$name] = @(0, 0, 0, 0) }
            $t, $i = $r.Types[$name]
            $agg[$name][0] += $t      # TICKERS SEEN (population x ticks)
            $agg[$name][1] += 1       # TICKS PRESENT
            if ($i -eq $t -and $t -gt 0) { $agg[$name][2] += 1 }  # TICKS ALL-IDLE
            $agg[$name][3] += $i      # TICKS IDLE (idle entries x ticks)
        }
    }
    Write-Output ('max tickers in one tick: ' + $maxTickers)
    Write-Output 'type            SEEN  PRESENT  ALLIDLE  IDLESEEN  idlefrac'
    foreach ($name in ($agg.Keys | Sort-Object)) {
        $seen, $present, $allidle, $idleseen = $agg[$name]
        $frac = if ($seen -gt 0) { '{0:P1}' -f ($idleseen / $seen) } else { 'n/a' }
        Write-Output ('{0,-15} {1,5} {2,7} {3,7} {4,8}  {5}' -f $name, $seen, $present, $allidle, $idleseen, $frac)
    }
    $totSeen = ($agg.Values | ForEach-Object { $_[0] } | Measure-Object -Sum).Sum
    $totIdle = ($agg.Values | ForEach-Object { $_[3] } | Measure-Object -Sum).Sum
    $frac = if ($totSeen -gt 0) { '{0:P1}' -f ($totIdle / $totSeen) } else { 'n/a' }
    Write-Output ('TOTAL' + ' ' * 11 + '{0,5}  (idle {1} = {2})' -f $totSeen, $totIdle, $frac)
}

$rows1 = Read-Census $Log1
Summarize $rows1 $Log1

if ($Log2) {
    $rows2 = Read-Census $Log2
    Summarize $rows2 $Log2
    # tick-by-tick comparison: same tick sequence, same cells
    $n = [Math]::Min($rows1.Count, $rows2.Count)
    $mism = 0
    for ($i = 0; $i -lt $n; $i++) {
        $a = $rows1[$i]; $b = $rows2[$i]
        $same = ($a.Tick -eq $b.Tick) -and ($a.Tickers -eq $b.Tickers)
        if ($same) {
            $keys = @($a.Types.Keys) + @($b.Types.Keys) | Sort-Object -Unique
            foreach ($k in $keys) {
                $ta = $a.Types[$k]; $tb = $b.Types[$k]
                if (($null -eq $ta) -ne ($null -eq $tb)) { $same = $false; break }
                if ($ta -and ($ta[0] -ne $tb[0] -or $ta[1] -ne $tb[1])) { $same = $false; break }
            }
        }
        if (-not $same) {
            Write-Output ("CENSUS_MISMATCH at index {0}: [{1}] vs [{2}]" -f $i, $a.Tick, $b.Tick)
            $mism++
            if ($mism -ge 10) { Write-Output '... stopping after 10 mismatches'; break }
        }
    }
    if ($n -eq 0) {
        Write-Output 'CENSUS_COMPARE FAIL (no comparable rows)'
    } elseif ($mism -eq 0) {
        Write-Output ("CENSUS_COMPARE PASS ($n ticks compared)")
    } else {
        Write-Output ("CENSUS_COMPARE FAIL ($mism mismatches)")
    }
}
