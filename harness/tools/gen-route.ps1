<#
.SYNOPSIS
  Generation-saturating benchmark matrix runner for Kestrel MSPT:
  Vanilla (3 repeats) + Paper (3 repeats) [+ Kestrel flat-platform row].

.DESCRIPTION
  Runs the generation-saturating benchmark matrix on bench PC to gate G2 step 9:
  - Saturated route: leg step > 336 blocks (default 512.0 = 32 chunks) ensures
    100% ungenerated fresh chunks at every leg (zero overlap with view-distance=10).
  - Short stay duration (default 4s) ensures server never drains generation queue.
  - Surface altitude (default Y=80.0) forces full column terrain, cave, and feature resolution.
  - Console-driven teleportation (via stdin `tp GenBot <x> <y> <z>`) ensures genuine
    server-side player entity movement and chunk generation ticketing.
  - Captures kestrel-harness ticks.log proxy timing (exit-to-exit deltas).
  - Passes -Dkestrel.harness.perTickTraces=false and -Dkestrel.harness.tickHash=false
    so baseline measures server performance without trace overhead.
  - Full per-repeat provenance manifest (schema=1, ISO-8601 start/end, server jar SHA256,
    javaagent jar SHA256, exact JVM flags).
  - Asserts manifest integrity with self re-read check mirroring corpus-gen.ps1 discipline.
  - Emits standard MSPTRUN lines and structured JSON rows.
  - Generates markdown comparison table with speedup multipliers.

  Usage (bench PC, FOREGROUND ssh):
    # Pilot run (single vanilla repeat):
    pwsh -NoProfile -ExecutionPolicy Bypass -File harness/tools/gen-route.ps1 -Pilot -Port 25576

    # Full matrix run (3x Vanilla + 3x Paper):
    pwsh -NoProfile -ExecutionPolicy Bypass -File harness/tools/gen-route.ps1 -Port 25576
#>
param(
    [long] $Seed = 4242424242,
    [int] $Repeats = 3,
    [int] $Legs = 8,
    [int] $Stay = 4,
    [float] $Step = 512.0,
    [float] $Y = 80.0,
    [int] $Port = 25576,
    [string] $VanillaBoot = 'C:\Users\bench\kestrel\vanilla-boot-test',
    [string] $PaperJar = 'C:\Users\bench\bench\paper\paper-26.2.jar',
    [string] $AgentDir = 'C:\Users\bench\kestrel\harness\agent\build\libs',
    [string] $OutJson = '',
    [switch] $Pilot,
    [switch] $SkipKestrel
)

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4.1+1'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $java)) { throw "java not found at $java" }

if (-not $OutJson) {
    $OutJson = 'C:\Users\bench\kestrel\harness\tools\gen-matrix-results.json'
}

if ($Pilot) {
    $Repeats = 1
    Write-Host "[gen-route] PILOT MODE ACTIVATED: running single vanilla repeat on seed $Seed"
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
Write-Host "[gen-route] checking for active java processes or port $Port usage..."
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
    Write-Host "[gen-route] bench PC busy (competing java servers: $busyJava, port $Port active: $busyPort). Retrying in 15 seconds..."
    Start-Sleep -Seconds 15
}
Write-Host "[gen-route] host is clear. Ready to begin benchmark run."

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
        Write-Host "[gen-route] located Paper jar at $PaperJar"
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

Write-Host "[gen-route] Vanilla jar: $serverJar ($($serverJarInfo.bytes) bytes, sha256=$($serverJarInfo.sha256))"
if ($paperExists) {
    Write-Host "[gen-route] Paper jar:   $PaperJar ($($paperJarInfo.bytes) bytes, sha256=$($paperJarInfo.sha256))"
} else {
    Write-Host "[gen-route] Paper jar:   NOT FOUND"
}
Write-Host "[gen-route] Agent jar:   $agentJar ($($agentJarInfo.bytes) bytes, sha256=$($agentJarInfo.sha256))"
Write-Host "[gen-route] TpBot:       $tpBot"

# 3. Compute expanding spiral route with step > view-distance diameter
function Get-SpiralPoints([long]$sVal, [int]$count, [float]$step = 512.0, [float]$targetY = 80.0) {
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

$route = Get-SpiralPoints -sVal $Seed -count $Legs -step $Step -targetY $Y
Write-Host "[gen-route] Saturating route for seed $Seed (legs=$($route.Count), step=${Step}b, Y=${Y}):"
$legNum = 0
foreach ($pt in $route) {
    $legNum++
    Write-Host "  Leg $legNum -> ($($pt.X), $($pt.Y), $($pt.Z))"
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
    $maxVal = $sorted[-1]
    return [PSCustomObject]@{
        Count  = $n
        Median = $med
        P95    = $p95
        Mean   = $mean
        Max    = $maxVal
    }
}

# Array to store all JSON row results
$allResults = [System.Collections.Generic.List[psobject]]::new()

# 5. Execute a benchmark run
function Run-GenBenchTarget([string]$serverType, [string]$jarPath, [int]$repeatIndex) {
    Write-Host "`n========================================================"
    Write-Host " RUNNING $serverType (repeat $repeatIndex of $Repeats) - Seed $Seed"
    Write-Host "========================================================"

    $work = Join-Path ([System.IO.Path]::GetTempPath()) "gen-route-$serverType-$repeatIndex-$Seed"
    if (Test-Path $work) { Remove-Item -Recurse -Force $work }
    New-Item -ItemType Directory -Force -Path $work | Out-Null

    Set-Content -Path (Join-Path $work 'eula.txt') -Value 'eula=true'
    @"
server-port=$Port
online-mode=false
max-players=10
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

    # Use boot.bat and cmd.exe /c with redirected stdin for reliable console interaction
    $bat = Join-Path $work 'boot.bat'
    $batBody = "@echo off`r`n`"$java`" -Xms256M -Xmx2G -Dkestrel.harness.perTickTraces=false -Dkestrel.harness.tickHash=false -javaagent:`"$agentJar=$ticksLog`" -jar `"$jarPath`" --nogui > `"$stdoutLog`" 2> `"$stderrLog`"`r`n"
    [System.IO.File]::WriteAllText($bat, $batBody, [System.Text.Encoding]::ASCII)

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $env:ComSpec
    $psi.Arguments = '/c "' + $bat + '"'
    $psi.WorkingDirectory = $work
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.CreateNoWindow = $true

    Write-Host "[$serverType #$repeatIndex] launching server process via boot.bat with stdin redirection..."
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    [void]$proc.Start()

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

    Write-Host "[$serverType #$repeatIndex] server is READY. Connecting GenBot..."
    $botStay = $Legs * $Stay + 20
    $botProc = Start-Process -FilePath "py" `
        -ArgumentList @($tpBot, "--host", "127.0.0.1", "--port", "$Port", "--stay", "$botStay", "--name", "GenBot") `
        -PassThru -NoNewWindow

    # Wait for bot to join game
    $joinDeadline = (Get-Date).AddSeconds(45)
    $joined = $false
    while ((Get-Date) -lt $joinDeadline) {
        Start-Sleep -Seconds 1
        if (Test-Path $stdoutLog) {
            if (Get-Content $stdoutLog | Select-String 'GenBot joined the game') {
                $joined = $true
                break
            }
        }
    }

    if (-not $joined) {
        Write-Warning "[$serverType #$repeatIndex] bot join not confirmed in stdout.log; proceeding with teleport schedule..."
    } else {
        Write-Host "[$serverType #$repeatIndex] GenBot joined successfully!"
    }

    Write-Host "[$serverType #$repeatIndex] driving $Legs saturating legs (step=${Step}b, stay=${Stay}s, Y=${Y})..."
    $legIdx = 0
    foreach ($pt in $route) {
        $legIdx++
        $tx = [long][math]::Round($pt.X)
        $ty = [long][math]::Round($pt.Y)
        $tz = [long][math]::Round($pt.Z)
        Write-Host "[$serverType #$repeatIndex] -> Leg $legIdx/${Legs}: console tp GenBot $tx $ty $tz (stay=${Stay}s)..."
        $proc.StandardInput.WriteLine("tp GenBot $tx $ty $tz")
        $proc.StandardInput.Flush()
        Start-Sleep -Seconds $Stay
    }

    Write-Host "[$serverType #$repeatIndex] all legs completed. Gracefully stopping server via console stop..."
    $proc.StandardInput.WriteLine("stop")
    $proc.StandardInput.Flush()
    $proc.WaitForExit(30000)

    if (-not $proc.HasExited) {
        Write-Warning "[$serverType #$repeatIndex] server did not exit within 30s; terminating process tree..."
        & taskkill.exe /PID $proc.Id /T /F 2>&1 | Out-Null
    }
    if ($botProc -and -not $botProc.HasExited) {
        Stop-Process -Id $botProc.Id -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 2

    $endUtc = (Get-Date).ToUniversalTime().ToString('o')
    $durSec = [math]::Round(((Get-Date $endUtc) - (Get-Date $startUtc)).TotalSeconds, 2)

    # Parse timing results
    $timing = Parse-TicksLog -path $ticksLog
    if (-not $timing) {
        Write-Warning "[$serverType #$repeatIndex] no exit deltas parsed from $ticksLog"
        $med = 0.0; $p95 = 0.0; $mean = 0.0; $maxV = 0.0; $ticksCount = 0
    } else {
        $med = $timing.Median
        $p95 = $timing.P95
        $mean = $timing.Mean
        $maxV = $timing.Max
        $ticksCount = $timing.Count
    }

    # Extract region files and chunk count
    $mcaFiles = @(Get-ChildItem -Path $work -Filter "*.mca" -Recurse -ErrorAction SilentlyContinue)
    $mcaCount = $mcaFiles.Count
    $chunksLoaded = $mcaCount * 32

    $targetJarInfo = if ($serverType -eq 'vanilla') { $serverJarInfo } else { $paperJarInfo }
    $medStr = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0:F2}", $med)
    $p95Str = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0:F2}", $p95)
    $ratioStr = if ($med -gt 0) { [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0:F2}", ($p95 / $med)) } else { "N/A" }
    
    $runLine = "MSPTRUN server=$serverType seed=$Seed med=$medStr p95=$p95Str"
    Write-Host $runLine

    if ($Pilot) {
        Write-Host "PILOT_SATURATION_EVIDENCE: p95=$p95Str ms, med=$medStr ms, ratio=${ratioStr}x, mca=$mcaCount, max=$([math]::Round($maxV, 1)) ms"
    }

    $notesText = "repeat=$repeatIndex/$Repeats; legs=$Legs; stay=${Stay}s; step=${Step}b; Y=${Y}; mca=$mcaCount; ticks.log proxy"
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
        mca_files     = [int]$mcaCount
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

    # Cleanup temp directory
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue

    return $rowObj
}

# 6. Run Vanilla benchmark
for ($r = 1; $r -le $Repeats; $r++) {
    $row = Run-GenBenchTarget -serverType 'vanilla' -jarPath $serverJar -repeatIndex $r
    if ($row) { $allResults.Add($row) }
    Start-Sleep -Seconds 5
}

# 7. Run Paper benchmark (skip if Pilot mode)
if (-not $Pilot) {
    if ($paperExists) {
        for ($r = 1; $r -le $Repeats; $r++) {
            $row = Run-GenBenchTarget -serverType 'paper' -jarPath $PaperJar -repeatIndex $r
            if ($row) { $allResults.Add($row) }
            Start-Sleep -Seconds 5
        }
    } else {
        Write-Host "`n[gen-route] skipping Paper runs: jar not present"
    }
}

# 8. Record Kestrel flat-platform status row (skip if Pilot mode or SkipKestrel)
if (-not $Pilot -and -not $SkipKestrel) {
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
        mca_files     = 0
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
$vanMeanAvg = if ($vanRows.Count -gt 0) { [math]::Round(($vanRows | Measure-Object -Property mspt_mean -Average).Average, 3) } else { 0.0 }

$paperMedAvg = if ($paperRows.Count -gt 0) { [math]::Round(($paperRows | Measure-Object -Property mspt_med -Average).Average, 3) } else { 0.0 }
$paperP95Avg = if ($paperRows.Count -gt 0) { [math]::Round(($paperRows | Measure-Object -Property mspt_p95 -Average).Average, 3) } else { 0.0 }
$paperMeanAvg = if ($paperRows.Count -gt 0) { [math]::Round(($paperRows | Measure-Object -Property mspt_mean -Average).Average, 3) } else { 0.0 }

$speedup = if ($paperMedAvg -gt 0) { [math]::Round($vanMedAvg / $paperMedAvg, 2) } else { 0.0 }
$ratio = if ($vanMedAvg -gt 0) { [math]::Round($paperMedAvg / $vanMedAvg, 3) } else { 0.0 }
$meanSpeedup = if ($paperMeanAvg -gt 0) { [math]::Round($vanMeanAvg / $paperMeanAvg, 2) } else { 0.0 }

$vanMedAvgStr = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0:F2}", $vanMedAvg)
$paperMedAvgStr = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0:F2}", $paperMedAvg)
$speedupStr = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0:F2}", $speedup)
$ratioStr = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0:F3}", $ratio)

$summaryMarkdown = @"

### Raw Measured Runs
| Server | Repeat | Seed | Ticks | Median MSPT | P95 MSPT | Mean MSPT | MCA Files | Chunks | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
"@

$repMap = @{}
foreach ($r in $allResults) {
    $srv = $r.server
    if (-not $repMap.ContainsKey($srv)) { $repMap[$srv] = 0 }
    $repMap[$srv]++
    $mMed = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0:F2}", $r.mspt_med)
    $mP95 = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0:F2}", $r.mspt_p95)
    $mMean = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0:F2}", $r.mspt_mean)
    $summaryMarkdown += "`n| $($r.server) | #$($repMap[$srv]) | $($r.seed) | $($r.ticks) | $mMed ms | $mP95 ms | $mMean ms | $($r.mca_files) | $($r.chunks_loaded) | $($r.notes) |"
}

if (-not $Pilot) {
    $summaryMarkdown += @"


### Comparison & Multipliers
| Configuration | Seed | Repeat Count | Baseline Median | Server Median | Multiplier (Speedup) | MSPT Ratio |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Vanilla (Baseline)** | $Seed | $($vanRows.Count) | $vanMedAvgStr ms | $vanMedAvgStr ms | **1.00x (baseline)** | 1.000x |
| **Paper** | $Seed | $($paperRows.Count) | $vanMedAvgStr ms | $paperMedAvgStr ms | **${speedupStr}x faster** | ${ratioStr}x |
| **Kestrel (flat-platform)** | $Seed | 0 | $vanMedAvgStr ms | N/A | Skipped (no worldgen) | N/A |
"@
}

Write-Host $summaryMarkdown

# 10. Write JSON results with full provenance manifest
$manifest = [ordered]@{
    schema        = 1
    createdUtc    = (Get-Date).ToUniversalTime().ToString('o')
    seed          = [long]$Seed
    repeats       = $Repeats
    legs          = $Legs
    staySeconds   = $Stay
    stepBlocks    = $Step
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
Write-Host "`n[gen-route] all raw JSON rows and manifest written to $OutJson"

# 11. Self re-read assert (manifest discipline mirroring corpus-gen.ps1)
Write-Host "`n[gen-route] verifying results JSON discipline (self re-read assert)..."
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
    if (-not $Pilot -and $paperExists -and ($m.paperJar.sha256 -ne $paperJarInfo.sha256)) {
        $problems += "paper jar sha256 mismatch: manifest=$($m.paperJar.sha256) actual=$($paperJarInfo.sha256)"
    }
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
