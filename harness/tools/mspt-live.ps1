<#
.SYNOPSIS
  Full benchmark matrix runner for Kestrel MSPT live comparison:
  Vanilla (3 repeats) + Paper (3 repeats) [+ Kestrel flat-platform row].

.DESCRIPTION
  Runs the full reference benchmark matrix on bench PC:
  - Fixed seed (default 4242424242).
  - Deterministic tp-bot spiral route (3 sequential waypoints).
  - 3 repeats per server (Vanilla and Paper).
  - Captures kestrel-harness ticks.log proxy timing.
  - Passes -Dkestrel.harness.perTickTraces=false and -Dkestrel.harness.tickHash=false
    so vanilla baseline measures server performance without harness trace overhead.
  - Captures full provenance manifest per repeat (ISO-8601 start/end, server jar SHA256,
    javaagent jar SHA256, exact JVM flags, schema field).
  - Asserts manifest integrity with self re-read check mirroring corpus-gen.ps1 discipline.
  - Emits standard MSPTRUN lines and structured JSON rows.
  - Generates markdown comparison table with speedup multipliers.
  - Saves all JSON rows to the destination file.

  Usage (bench PC, FOREGROUND ssh):
    pwsh -NoProfile -File harness/tools/mspt-live.ps1 [-Seed 4242424242] [-Repeats 3] [-Stay 30]
#>
param(
    [long] $Seed = 4242424242,
    [int] $Repeats = 3,
    [int] $Stay = 30,
    [int] $Bots = 3,
    [float] $Y = 120.0,
    [int] $Port = 25575,
    [string] $VanillaBoot = 'C:\Users\bench\kestrel\vanilla-boot-test',
    [string] $PaperJar = 'C:\Users\bench\bench\paper\paper-26.2.jar',
    [string] $AgentDir = 'C:\Users\bench\kestrel\harness\agent\build\libs',
    [string] $OutJson = '',
    [switch] $SkipKestrel
)

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4.1+1'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $java)) { throw "java not found at $java" }

if (-not $OutJson) {
    $OutJson = 'C:\Users\bench\kestrel\harness\tools\mspt-matrix-results.json'
}

# Helper to capture file metadata (discipline mirrored from corpus-gen.ps1)
function Get-FileInfo([System.IO.FileInfo] $f) {
    if (-not $f -or -not (Test-Path $f.FullName)) { return $null }
    [ordered]@{
        name   = $f.Name
        bytes  = $f.Length
        sha256 = (Get-FileHash $f.FullName -Algorithm SHA256).Hash.ToLower()
    }
}

# 1. Guard against competing servers
Write-Host "[mspt-live] checking for active java processes or port $Port usage..."
$maxWaitSeconds = 3600
$waitStart = Get-Date
while ($true) {
    $busyJava = @(Get-CimInstance Win32_Process -Filter "Name like '%java%'" -ErrorAction SilentlyContinue |
        Where-Object { "$($_.CommandLine)" -match 'server\.jar|--nogui|paper' }).Count
    $busyPort = @(Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue).Count
    if ($busyJava -eq 0 -and $busyPort -eq 0) { break }
    if (((Get-Date) - $waitStart).TotalSeconds -gt $maxWaitSeconds) {
        throw "bench PC remained busy for >1h; aborting benchmark"
    }
    Write-Host "[mspt-live] bench PC busy (competing java servers: $busyJava, port $Port active: $busyPort). Retrying in 5 minutes..."
    Start-Sleep -Seconds 300
}
Write-Host "[mspt-live] host is clear. Ready to begin benchmark matrix."

# 2. Locate jars and bot
$serverJar = Join-Path $VanillaBoot 'server.jar'
if (-not (Test-Path $serverJar)) { throw "vanilla server.jar not found at $serverJar" }
$serverJarInfo = Get-FileInfo (Get-Item $serverJar)

$paperExists = Test-Path $PaperJar
if (-not $paperExists) {
    Write-Warning "Paper jar not found at $PaperJar; checking alternatives..."
    $paperCandidate = Get-ChildItem 'C:\Users\bench' -Filter 'paper*.jar' -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($paperCandidate) {
        $PaperJar = $paperCandidate.FullName
        $paperExists = $true
        Write-Host "[mspt-live] located Paper jar at $PaperJar"
    }
}
$paperJarInfo = if ($paperExists) { Get-FileInfo (Get-Item $PaperJar) } else { $null }

$agentJar = Get-ChildItem $AgentDir -Filter 'kestrel-harness*.jar' `
    | Where-Object { $_.Name -notlike '*boot*' } | Select-Object -First 1 -ExpandProperty FullName
if (-not $agentJar) { throw "no harness agent jar in $AgentDir" }
$agentJarInfo = Get-FileInfo (Get-Item $agentJar)

$tpBot = 'C:\Users\bench\kestrel\harness\tools\tp-bot.py'
if (-not (Test-Path $tpBot)) { $tpBot = 'C:\Users\bench\kestrel\tp-bot-test.py' }
if (-not (Test-Path $tpBot)) { throw "tp-bot.py not found" }

Write-Host "[mspt-live] Vanilla jar: $serverJar ($($serverJarInfo.bytes) bytes, sha256=$($serverJarInfo.sha256))"
if ($paperExists) {
    Write-Host "[mspt-live] Paper jar:   $PaperJar ($($paperJarInfo.bytes) bytes, sha256=$($paperJarInfo.sha256))"
} else {
    Write-Host "[mspt-live] Paper jar:   NOT FOUND"
}
Write-Host "[mspt-live] Agent jar:   $agentJar ($($agentJarInfo.bytes) bytes, sha256=$($agentJarInfo.sha256))"
Write-Host "[mspt-live] TpBot:       $tpBot"

# 3. Compute spiral waypoints
function Get-SpiralPoints([long]$sVal, [int]$count, [float]$step = 64.0, [float]$targetY = 120.0) {
    if ($count -le 0) { return @() }
    $x_origin = [float]((($sVal % 1000) - 500) * 16)
    $z_origin = [float](((([UInt64]($sVal -shr 16)) % 1000) - 500) * 16)
    
    $coords = [System.Collections.Generic.List[psobject]]::new()
    $x = 0; $z = 0
    $coords.Add([PSCustomObject]@{ X = [math]::Round($x_origin + $x * $step, 1); Y = [math]::Round($targetY, 1); Z = [math]::Round($z_origin + $z * $step, 1) })
    
    $dx = 1; $dz = 0
    $seg_len = 1
    $seg_passed = 0
    while ($coords.Count -lt $count) {
        $x += $dx
        $z += $dz
        $coords.Add([PSCustomObject]@{ X = [math]::Round($x_origin + $x * $step, 1); Y = [math]::Round($targetY, 1); Z = [math]::Round($z_origin + $z * $step, 1) })
        $seg_passed++
        if ($seg_passed -eq $seg_len) {
            $seg_passed = 0
            $tmp = -$dz; $dz = $dx; $dx = $tmp
            if ($dz -eq 0) { $seg_len++ }
        }
    }
    return $coords
}

$spiral = Get-SpiralPoints -sVal $Seed -count $Bots -step 64.0 -targetY $Y
Write-Host "[mspt-live] Spiral waypoints for seed $Seed (n=$($spiral.Count)):"
foreach ($pt in $spiral) {
    Write-Host "  -> ($($pt.X), $($pt.Y), $($pt.Z))"
}

# 4. Helper function to parse ticks.log
function Parse-TicksLog([string]$path) {
    if (-not (Test-Path $path)) { return $null }
    $lines = @(Get-Content $path | Where-Object { $_ -match ',exit,' })
    if ($lines.Count -lt 2) { return $null }
    $ts = @()
    foreach ($l in $lines) {
        $parts = $l.Split(',')
        if ($parts.Count -ge 4) { $ts += [long]$parts[3] }
    }
    if ($ts.Count -lt 2) { return $null }
    $deltas = @()
    for ($i = 1; $i -lt $ts.Count; $i++) {
        $deltas += [math]::Round(($ts[$i] - $ts[$i-1]) / 1e6, 3)
    }
    $sorted = $deltas | Sort-Object
    $n = $sorted.Count
    $med = if ($n % 2 -eq 1) { $sorted[[int]($n / 2)] } else { [math]::Round(($sorted[[int]($n / 2) - 1] + $sorted[[int]($n / 2)]) / 2.0, 3) }
    $p95Idx = [math]::Max(0, [math]::Min($n - 1, [int][math]::Ceiling(0.95 * $n) - 1))
    $p95 = $sorted[$p95Idx]
    $mean = [math]::Round(($deltas | Measure-Object -Average).Average, 3)
    return [PSCustomObject]@{
        Count  = $n
        Median = $med
        P95    = $p95
        Mean   = $mean
    }
}

# Array to store all JSON row results
$allResults = [System.Collections.Generic.List[psobject]]::new()

# 5. Execute a benchmark run
function Run-BenchTarget([string]$serverType, [string]$jarPath, [int]$repeatIndex) {
    Write-Host "`n========================================================"
    Write-Host " RUNNING $serverType (repeat $repeatIndex of $Repeats) - Seed $Seed"
    Write-Host "========================================================"

    $work = Join-Path ([System.IO.Path]::GetTempPath()) "mspt-live-$serverType-$repeatIndex-$Seed"
    if (Test-Path $work) { Remove-Item -Recurse -Force $work }
    New-Item -ItemType Directory -Force -Path $work | Out-Null

    Set-Content -Path (Join-Path $work 'eula.txt') -Value 'eula=true'
    @"
server-port=$Port
online-mode=false
max-players=20
view-distance=10
simulation-distance=10
spawn-monsters=false
spawn-animals=false
gamemode=creative
difficulty=peaceful
level-seed=$Seed
level-type=minecraft\:normal
generate-structures=true
max-tick-time=-1
"@ | Set-Content -Path (Join-Path $work 'server.properties')

    $ticksLog = Join-Path $work 'ticks.log'
    $stdoutLog = Join-Path $work 'stdout.log'
    $stderrLog = Join-Path $work 'stderr.log'

    $startUtc = (Get-Date).ToUniversalTime().ToString('o')
    $jvmArgs = @(
        '-Xms256M',
        '-Xmx2G',
        '-Dkestrel.harness.perTickTraces=false',
        '-Dkestrel.harness.tickHash=false',
        "-javaagent:$agentJar=$ticksLog",
        '-jar',
        $jarPath,
        '--nogui'
    )

    Write-Host "[$serverType #$repeatIndex] launching server process with gates: perTickTraces=false tickHash=false..."
    $proc = Start-Process -FilePath $java `
        -ArgumentList $jvmArgs `
        -WorkingDirectory $work -NoNewWindow -PassThru `
        -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog

    $ready = $false
    $deadline = (Get-Date).AddSeconds(180)
    $readyPatterns = @('Done (', 'Done!', 'Server started', 'listening on')

    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        if ($proc.HasExited) {
            Write-Warning "[$serverType #$repeatIndex] server process exited prematurely"
            break
        }
        if (Test-Path $stdoutLog) {
            $content = Get-Content $stdoutLog -Raw -ErrorAction SilentlyContinue
            if ($content) {
                foreach ($pat in $readyPatterns) {
                    if ($content.Contains($pat)) { $ready = $true; break }
                }
                if ($ready) { break }
            }
        }
    }

    if (-not $ready) {
        Write-Error "[$serverType #$repeatIndex] boot timed out or failed. Killing process..."
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        return $null
    }

    Write-Host "[$serverType #$repeatIndex] server is READY. Driving $Bots sequential bots on spiral..."
    $botIdx = 0
    foreach ($pt in $spiral) {
        $botIdx++
        $bName = "TpBot$botIdx"
        Write-Host "[$serverType #$repeatIndex] bot $bName -> ($($pt.X), $($pt.Y), $($pt.Z)) for ${Stay}s..."
        & py $tpBot --host 127.0.0.1 --port $Port --x $pt.X --y $pt.Y --z $pt.Z --stay $Stay --name $bName 2>&1 `
            | Select-String 'TPBOT|BOT_EXIT' | ForEach-Object { Write-Host "   $_" }
    }

    Write-Host "[$serverType #$repeatIndex] stopping server..."
    # Graceful stop attempt via process termination
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3

    $endUtc = (Get-Date).ToUniversalTime().ToString('o')
    $durSec = [math]::Round(((Get-Date $endUtc) - (Get-Date $startUtc)).TotalSeconds, 2)

    # Parse results
    $timing = Parse-TicksLog -path $ticksLog
    if (-not $timing) {
        Write-Warning "[$serverType #$repeatIndex] no exit deltas parsed from $ticksLog"
        $med = 0.0; $p95 = 0.0; $mean = 0.0; $ticksCount = 0
    } else {
        $med = $timing.Median
        $p95 = $timing.P95
        $mean = $timing.Mean
        $ticksCount = $timing.Count
    }

    # Extract chunk count
    $chunksLoaded = 0
    if (Test-Path $stdoutLog) {
        $lastChunkLine = Get-Content $stdoutLog | Select-String 'chunks=(\d+)' | Select-Object -Last 1
        if ($lastChunkLine -and ($lastChunkLine.Matches.Groups.Count -ge 2)) {
            $chunksLoaded = [int]$lastChunkLine.Matches.Groups[1].Value
        }
    }
    if ($chunksLoaded -eq 0) {
        $regDirs = @(
            Join-Path $work 'world\dimensions\minecraft\overworld\region',
            Join-Path $work 'world\region'
        )
        foreach ($rd in $regDirs) {
            if (Test-Path $rd) {
                $mcaCount = @(Get-ChildItem $rd -Filter '*.mca' -ErrorAction SilentlyContinue).Count
                if ($mcaCount -gt 0) { $chunksLoaded = $mcaCount * 32; break }
            }
        }
    }

    $targetJarInfo = if ($serverType -eq 'vanilla') { $serverJarInfo } else { $paperJarInfo }
    $medStr = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0:F2}", $med)
    $p95Str = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0:F2}", $p95)
    $runLine = "MSPTRUN server=$serverType seed=$Seed med=$medStr p95=$p95Str"
    Write-Host $runLine

    $notesText = "repeat=$repeatIndex/$Repeats; bots=$Bots; stay=${Stay}s; ticks.log proxy"
    $rowObj = [ordered]@{
        schema        = 1
        server        = $serverType
        seed          = [long]$Seed
        repeat        = $repeatIndex
        ticks         = [int]$ticksCount
        mspt_med      = [double]$med
        mspt_p95      = [double]$p95
        mspt_mean     = [double]$mean
        chunks_loaded = [int]$chunksLoaded
        startUtc      = $startUtc
        endUtc        = $endUtc
        start_utc     = $startUtc
        end_utc       = $endUtc
        duration_s    = $durSec
        jvm_flags     = $jvmArgs
        jvmFlags      = $jvmArgs
        server_jar    = $targetJarInfo
        serverJar     = $targetJarInfo
        agent_jar     = $agentJarInfo
        agentJar      = $agentJarInfo
        javaagent_jar = $agentJarInfo
        javaagentJar  = $agentJarInfo
        notes         = $notesText
    }

    $jsonStr = $rowObj | ConvertTo-Json
    Write-Host $jsonStr

    # Cleanup temp dir
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue

    return $rowObj
}

# 6. Run Vanilla repeats
for ($r = 1; $r -le $Repeats; $r++) {
    $row = Run-BenchTarget -serverType 'vanilla' -jarPath $serverJar -repeatIndex $r
    if ($row) { $allResults.Add($row) }
    Start-Sleep -Seconds 5
}

# 7. Run Paper repeats
if ($paperExists) {
    for ($r = 1; $r -le $Repeats; $r++) {
        $row = Run-BenchTarget -serverType 'paper' -jarPath $PaperJar -repeatIndex $r
        if ($row) { $allResults.Add($row) }
        Start-Sleep -Seconds 5
    }
} else {
    Write-Host "`n[mspt-live] skipping Paper runs: jar not present"
}

# 8. Record Kestrel flat-platform status
if (-not $SkipKestrel) {
    Write-Host "`n========================================================"
    Write-Host " KESTREL FLAT-PLATFORM STATUS"
    Write-Host "========================================================"
    $kestrelNotes = "NOT-IMPLEMENTED: Skipped (Kestrel flat-platform has no worldgen; G2 real terrain generation is in planning stage, only 17x17 flat platform exists; javaagent tick-logging does not attach to non-MinecraftServer)"
    $kestRunLine = "MSPTRUN server=kestrel seed=$Seed med=0.00 p95=0.00"
    Write-Host $kestRunLine
    $kestStart = (Get-Date).ToUniversalTime().ToString('o')
    $kestRow = [ordered]@{
        schema        = 1
        server        = "kestrel"
        seed          = [long]$Seed
        repeat        = 0
        ticks         = 0
        mspt_med      = 0.0
        mspt_p95      = 0.0
        mspt_mean     = 0.0
        chunks_loaded = 0
        startUtc      = $kestStart
        endUtc        = $kestStart
        start_utc     = $kestStart
        end_utc       = $kestStart
        duration_s    = 0.0
        jvm_flags     = @()
        jvmFlags      = @()
        server_jar    = $null
        serverJar     = $null
        agent_jar     = $agentJarInfo
        agentJar      = $agentJarInfo
        javaagent_jar = $agentJarInfo
        javaagentJar  = $agentJarInfo
        notes         = $kestrelNotes
    }
    Write-Host ($kestRow | ConvertTo-Json)
    $allResults.Add($kestRow)
}

# 9. Format Multipliers and Summary Table
Write-Host "`n========================================================"
Write-Host " BENCHMARK RESULTS & MULTIPLIER SUMMARY"
Write-Host "========================================================"

$vanRows = @($allResults | Where-Object { $_.server -eq 'vanilla' })
$paperRows = @($allResults | Where-Object { $_.server -eq 'paper' })

$vanMedAvg = if ($vanRows.Count -gt 0) { [math]::Round(($vanRows | Measure-Object -Property mspt_med -Average).Average, 3) } else { 0.0 }
$vanP95Avg = if ($vanRows.Count -gt 0) { [math]::Round(($vanRows | Measure-Object -Property mspt_p95 -Average).Average, 3) } else { 0.0 }

$paperMedAvg = if ($paperRows.Count -gt 0) { [math]::Round(($paperRows | Measure-Object -Property mspt_med -Average).Average, 3) } else { 0.0 }
$paperP95Avg = if ($paperRows.Count -gt 0) { [math]::Round(($paperRows | Measure-Object -Property mspt_p95 -Average).Average, 3) } else { 0.0 }

$speedup = if ($paperMedAvg -gt 0) { [math]::Round($vanMedAvg / $paperMedAvg, 2) } else { 0.0 }
$ratio = if ($vanMedAvg -gt 0) { [math]::Round($paperMedAvg / $vanMedAvg, 3) } else { 0.0 }

$vanMedAvgStr = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0:F2}", $vanMedAvg)
$paperMedAvgStr = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0:F2}", $paperMedAvg)
$speedupStr = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0:F2}", $speedup)
$ratioStr = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0:F3}", $ratio)

$summaryMarkdown = @"

### Raw Measured Runs
| Server | Repeat | Seed | Ticks | Median MSPT | P95 MSPT | Mean MSPT | Chunks | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
"@

$repMap = @{}
foreach ($r in $allResults) {
    $srv = $r.server
    if (-not $repMap.ContainsKey($srv)) { $repMap[$srv] = 0 }
    $repMap[$srv]++
    $mMed = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0:F2}", $r.mspt_med)
    $mP95 = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0:F2}", $r.mspt_p95)
    $mMean = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0:F2}", $r.mspt_mean)
    $summaryMarkdown += "`n| $($r.server) | #$($repMap[$srv]) | $($r.seed) | $($r.ticks) | $mMed ms | $mP95 ms | $mMean ms | $($r.chunks_loaded) | $($r.notes) |"
}

$summaryMarkdown += @"


### Comparison & Multipliers
| Configuration | Seed | Repeat Count | Baseline Median | Server Median | Multiplier (Speedup) | MSPT Ratio |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Vanilla (Baseline)** | $Seed | $($vanRows.Count) | $vanMedAvgStr ms | $vanMedAvgStr ms | **1.00x (baseline)** | 1.000x |
| **Paper** | $Seed | $($paperRows.Count) | $vanMedAvgStr ms | $paperMedAvgStr ms | **${speedupStr}x faster** | ${ratioStr}x |
| **Kestrel (flat-platform)** | $Seed | 0 | $vanMedAvgStr ms | N/A | Skipped (no worldgen) | N/A |
"@

Write-Host $summaryMarkdown

# 10. Write JSON results with full provenance manifest
$manifest = [ordered]@{
    schema        = 1
    createdUtc    = (Get-Date).ToUniversalTime().ToString('o')
    seed          = [long]$Seed
    repeats       = $Repeats
    bots          = $Bots
    staySeconds   = $Stay
    targetY       = $Y
    perTickTraces = $false
    tickHash      = $false
    serverJar     = $serverJarInfo
    paperJar      = $paperJarInfo
    agentJar      = $agentJarInfo
    baseJvmFlags  = @('-Xms256M', '-Xmx2G', '-Dkestrel.harness.perTickTraces=false', '-Dkestrel.harness.tickHash=false')
    results       = $allResults
}

$jsonContent = $manifest | ConvertTo-Json -Depth 6
Set-Content -Path $OutJson -Value $jsonContent -Encoding utf8
Write-Host "`n[mspt-live] all raw JSON rows and manifest written to $OutJson"

# 11. Self re-read assert (manifest discipline mirroring corpus-gen.ps1)
Write-Host "`n[mspt-live] verifying results JSON discipline (self re-read assert)..."
$problems = @()
if (-not (Test-Path $OutJson)) {
    $problems += "manifest file $OutJson was not written"
} else {
    $m = Get-Content $OutJson -Raw | ConvertFrom-Json
    if ($m.schema -ne 1) { $problems += "manifest schema=$($m.schema), expected 1" }
    if ([long]$m.seed -ne $Seed) { $problems += "manifest seed=$($m.seed), expected $Seed" }
    if (@($m.results).Count -ne $allResults.Count) { $problems += "manifest results count $(@($m.results).Count) != $($allResults.Count)" }
    if ($m.agentJar.sha256 -ne $agentJarInfo.sha256) { $problems += "agent jar sha256 mismatch: manifest=$($m.agentJar.sha256) actual=$($agentJarInfo.sha256)" }
    if ($m.serverJar.sha256 -ne $serverJarInfo.sha256) { $problems += "server jar sha256 mismatch: manifest=$($m.serverJar.sha256) actual=$($serverJarInfo.sha256)" }
    if ($paperExists -and ($m.paperJar.sha256 -ne $paperJarInfo.sha256)) { $problems += "paper jar sha256 mismatch: manifest=$($m.paperJar.sha256) actual=$($paperJarInfo.sha256)" }
    foreach ($row in @($m.results)) {
        if ($row.schema -ne 1) { $problems += "result row for $($row.server) missing schema field" }
        if (-not $row.startUtc) { $problems += "result row for $($row.server) missing startUtc" }
        if (-not $row.endUtc) { $problems += "result row for $($row.server) missing endUtc" }
        if ($row.server -ne 'kestrel') {
            if (-not $row.server_jar -or -not $row.server_jar.sha256) { $problems += "result row for $($row.server) missing server_jar sha256" }
            if (-not $row.agent_jar -or -not $row.agent_jar.sha256) { $problems += "result row for $($row.server) missing agent_jar sha256" }
            if (@($row.jvm_flags).Count -eq 0) { $problems += "result row for $($row.server) missing jvm_flags" }
            if ($row.ticks -le 0) { $problems += "result row for $($row.server) has zero ticks" }
            if ($row.mspt_med -le 0) { $problems += "result row for $($row.server) has zero mspt_med" }
        }
    }
}

if ($problems.Count -gt 0) {
    Write-Host "MSPT_SELFCHECK FAIL:`n$($problems -join "`n")" -ForegroundColor Red
    throw "MSPT_SELFCHECK FAIL: $($problems.Count) problems detected"
}
Write-Host "MSPT_SELFCHECK PASS ($($allResults.Count) result rows verified with full provenance)"
