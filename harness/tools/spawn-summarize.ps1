<#
.SYNOPSIS
  Summarizes spawn-proof.log files (SpawnProof, schema v1).

.DESCRIPTION
  Reads one or two spawn proofs and prints the proof verdict: ticks,
  no-state ticks (tickChunks had not run yet), the spawnableChunkCount
  0-to-289 flip tick (counter only fills once player-nearby chunks exist -
  baselines comparing pre/post bot login must account for it), per-category
  pass/fail transitions, and total DIVERGE lines. Zero DIVERGE over the
  live window IS the global-cap proof.

  Display uses [Console]::WriteLine, not Write-Output: Write-Output inside
  a function whose return value is assigned merges into that return value
  and the script prints nothing (mspt-analyze.ps1 WI-10 root cause).

.EXAMPLE
  pwsh -File spawn-summarize.ps1 C:\path\to\spawn-proof.log

.EXAMPLE
  pwsh -File spawn-summarize.ps1 run1\spawn-proof.log run2\spawn-proof.log -SkipTicks 5
#>
param(
    [Parameter(Mandatory = $true)][string]$Log1,
    [string]$Log2 = $null,
    [int]$SkipTicks = 0
)

$ErrorActionPreference = 'Stop'

function Read-Spawn([string]$path) {
    $rows = @()
    $diverges = 0
    foreach ($line in (Get-Content $path | Where-Object { $_ -notmatch '^#' -and $_.Trim() -ne '' })) {
        if ($line -match '^DIVERGE') { $diverges++; continue }
        $parts = $line.Split(',')
        $tick = [int]$parts[0]
        if ($tick -le $SkipTicks) { continue }
        $cells = @{}
        $chunks = -1
        foreach ($c in $parts[1..($parts.Count - 1)]) {
            if ($c -match '^(\w+):(\d+)/(\d+):(pass|fail)$') {
                $cells[$Matches[1]] = $Matches[4]
            } elseif ($c -match '^\d+$') {
                $chunks = [int]$c
            }
        }
        $rows += [pscustomobject]@{ Tick = $tick; Chunks = $chunks; Cells = $cells }
    }
    return @{ Rows = $rows; Diverges = $diverges }
}

function Summarize($data, [string]$label) {
    $rows = $data.Rows
    [Console]::WriteLine("=== $label ($($rows.Count) ticks, DIVERGE lines: $($data.Diverges)) ===")
    if ($rows.Count -eq 0) { [Console]::WriteLine('(no rows)'); return }
    $nostate = @($rows | Where-Object { $_.Chunks -lt 0 }).Count
    [Console]::WriteLine("no-state ticks (tickChunks had not run): $nostate")
    $flip = $null
    $prevChunks = $rows[0].Chunks
    foreach ($r in $rows) {
        if ($prevChunks -eq 0 -and $r.Chunks -gt 0) { $flip = $r.Tick; break }
        $prevChunks = $r.Chunks
    }
    if ($flip -ne $null) {
        [Console]::WriteLine("spawnableChunkCount 0->289 flip at tick $flip (bot-login effect, not a corpus stage)")
    }
    $trans = @{}
    $prev = $null
    foreach ($r in $rows) {
        if ($prev -ne $null) {
            $keys = @($prev.Cells.Keys) + @($r.Cells.Keys) | Sort-Object -Unique
            foreach ($k in $keys) {
                if ($prev.Cells[$k] -ne $r.Cells[$k]) {
                    if (-not $trans.ContainsKey($k)) { $trans[$k] = 0 }
                    $trans[$k]++
                }
            }
        }
        $prev = $r
    }
    if ($trans.Count -eq 0) {
        [Console]::WriteLine('no per-category pass/fail transitions (a battery stuck on one verdict proves nothing - check the cells)')
    } else {
        [Console]::WriteLine('pass/fail transitions per category:')
        foreach ($k in ($trans.Keys | Sort-Object)) {
            [Console]::WriteLine(('  {0,-10} {1}' -f $k, $trans[$k]))
        }
    }
    if ($data.Diverges -eq 0) {
        [Console]::WriteLine("SPAWN_PROOF PASS ($($rows.Count) ticks, zero DIVERGE)")
    } else {
        [Console]::WriteLine("SPAWN_PROOF FAIL ($($data.Diverges) DIVERGE lines)")
    }
}

$data1 = Read-Spawn $Log1
Summarize $data1 $Log1

if ($Log2) {
    $data2 = Read-Spawn $Log2
    Summarize $data2 $Log2
    # tick-by-tick comparison: same tick sequence, same per-category verdicts
    $rows1 = $data1.Rows; $rows2 = $data2.Rows
    $n = [Math]::Min($rows1.Count, $rows2.Count)
    $mism = 0
    for ($i = 0; $i -lt $n; $i++) {
        $a = $rows1[$i]; $b = $rows2[$i]
        $same = ($a.Tick -eq $b.Tick) -and ($a.Chunks -eq $b.Chunks)
        if ($same) {
            $keys = @($a.Cells.Keys) + @($b.Cells.Keys) | Sort-Object -Unique
            foreach ($k in $keys) {
                if ($a.Cells[$k] -ne $b.Cells[$k]) { $same = $false; break }
            }
        }
        if (-not $same) {
            [Console]::WriteLine("SPAWN_MISMATCH at index {0}: [{1}] vs [{2}]" -f $i, $a.Tick, $b.Tick)
            $mism++
            if ($mism -ge 10) { [Console]::WriteLine('... stopping after 10 mismatches'); break }
        }
    }
    if ($n -eq 0) {
        [Console]::WriteLine('SPAWN_COMPARE FAIL (no comparable rows)')
    } elseif ($mism -eq 0) {
        [Console]::WriteLine("SPAWN_COMPARE PASS ($n ticks compared)")
    } else {
        [Console]::WriteLine("SPAWN_COMPARE FAIL ($mism mismatches)")
    }
}
