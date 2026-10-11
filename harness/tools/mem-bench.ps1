<#
.SYNOPSIS
  G2 step-2 memory measurement (preresearch/g2-memory-design.md section 1):
  boots Kestrel on one world variant, joins one idle bot at view distance 10,
  and captures RSS + jcmd histogram/NMT/heap_info + the server's census and
  MemProbe lines. Analysis is harness/tools/g2-mem-analyze.py (run on the
  output root); this script only collects.

.DESCRIPTION
  Bench shape (design 1.2): scenario `single` (1 bot, behavior idle), stay
  120 s, sampling `quality` (settle 10 s, 4 RSS samples 5 s apart), repeats 3,
  flag set `tuned` (-Xms128M -Xmx1G -XX:+UseSerialGC) plus
  -XX:NativeMemoryTracking=summary and -Xlog:gc* to a file (the fallback
  record if jcmd cannot attach, design 1.4).

  Variants (design 1.3):
    W0  fresh flat world (KestrelServer's 17x17 stone), new directory per rep.
    W1  -World <corpus> -ImportSquare cx,cz,10   (441 chunks, the gated number)
    W2  -World <corpus> -ImportSquare cx,cz,15   (961 chunks, slope)
    SYN -World <synthetic>                        (apparatus validation)
  Import runs pass --import (read-only: no save of any kind), --spawn at the
  square center (never findSpawn, design 0.4 item 4) and write an offline
  census (g2-region.py) once per campaign for the exact cross-check.

  Per rep, in order: preflight (no java/kjava alive, else poll every 5 min up
  to -WaitMinutes, then abort); boot; jcmd GC.heap_info right after the
  import/load MemProbe line (load-peak marker); idle settle + RSS idle
  samples; bot join; wait for "stream complete" (end of the streaming
  window, design 0.4 item 3); RSS load samples; jcmd GC.class_histogram,
  VM.native_memory summary, GC.heap_info with the bot still connected; stop.

  Output: <OutRoot>/<Variant>-<Flags>-r<N>/{server.log, server.err, bot.log,
  gc.log, heap-postload.txt, histogram.txt, nmt.txt, heapinfo.txt, run.json}
  and <OutRoot>/campaign.json (+ offline-census-<Variant>.txt).

  Classes: point -Classes at a PRIVATE staging build, not the shared
  C:\Users\bench\kestrel\core\build (other agents run from it).

.EXAMPLE
  # Apparatus validation on the synthetic world (Pi: g2-region.py synth).
  pwsh -File mem-bench.ps1 -Variant SYN -World C:\Users\bench\bench\g2mem\synth -Spawn 8,80,8 -Repeats 1

.EXAMPLE
  # W1 / W2 on the real corpus (center chunk 0,0, spawn above it).
  pwsh -File mem-bench.ps1 -Variant W1 -World C:\Users\bench\bench\g2corpus\world -ImportSquare 0,0,10 -Spawn 8,200,8
  pwsh -File mem-bench.ps1 -Variant W2 -World C:\Users\bench\bench\g2corpus\world -ImportSquare 0,0,15 -Spawn 8,200,8
#>
param(
    [Parameter(Mandatory = $true)] [ValidateSet('W0', 'W1', 'W2', 'SYN')] [string] $Variant,
    [string] $World = '',
    [string] $ImportSquare = '',
    [string] $Spawn = '',
    [ValidateSet('tuned', 'default')] [string] $Flags = 'tuned',
    [int] $Repeats = 3,
    [int] $StaySeconds = 120,
    [int] $SettleSeconds = 10,
    [int] $RssSamples = 4,
    [int] $RssGapSeconds = 5,
    [int] $ReadyTimeoutSeconds = 300,
    [int] $StreamTimeoutSeconds = 90,
    [int] $WaitMinutes = 60,
    [int] $Port = 25643,  # unique: 25573 = kbench, 25575 = corpus pilot + mspt-live
    [long] $Seed = 4242424242,
    [string] $OutRoot = '',
    [string] $Classes = 'C:\Users\bench\kestrel-bench\core\build\classes\java\main',
    [string] $VanillaJar = 'C:\Users\bench\kestrel\vanilla\server-26.2.jar',
    [string] $VanillaLibs = 'C:\Users\bench\kestrel\vanilla-boot-test\libraries',
    [string] $Datapack = 'C:\Users\bench\kestrel\datapack-src\data',
    [string] $Java = 'C:\Users\bench\kestrel\kjava.exe',
    [string] $Jcmd = 'C:\Program Files\Java\jdk-25.0.4.1+1\bin\jcmd.exe',
    [string] $Python = 'python',
    [string] $ToolsDir = $PSScriptRoot,
    [string] $GitSha = 'unknown'
)

$ErrorActionPreference = 'Stop'

$FlagSets = @{
    tuned   = @('-Xms128M', '-Xmx1G', '-XX:+UseSerialGC')
    default = @('-Xmx2G')
}

if ($Variant -ne 'W0') {
    if (-not $World) { throw "-World is required for $Variant" }
    if (-not (Test-Path (Join-Path $World 'region'))) { throw "no region dir under $World" }
    if (-not $Spawn) { throw "-Spawn x,y,z is required for imports (findSpawn hangs on them)" }
}
if (-not $Spawn) { $Spawn = '0,1,0' }
foreach ($p in @($Classes, $VanillaJar, $VanillaLibs, $Datapack, $Java)) {
    if (-not (Test-Path $p)) { throw "missing: $p" }
}
if (-not $OutRoot) {
    $OutRoot = Join-Path 'C:\Users\bench\bench\g2mem' (Get-Date -Format 'yyyyMMdd-HHmmss')
}
New-Item -ItemType Directory -Force -Path $OutRoot | Out-Null

function Say($m) {
    $line = '[{0}] {1}' -f (Get-Date -Format 'HH:mm:ss'), $m
    [Console]::WriteLine($line)
    Add-Content (Join-Path $OutRoot 'mem-bench.log') $line
}

function Get-JavaProcs {
    @(Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -match '^(kjava|java)$' })
}

# Never boot over another agent's live server (corpus-gen, MSPT runs).
function Wait-Free {
    $deadline = (Get-Date).AddMinutes($WaitMinutes)
    while ($true) {
        $procs = Get-JavaProcs
        if ($procs.Count -eq 0) { return }
        $ids = ($procs | ForEach-Object { "$($_.ProcessName):$($_.Id)" }) -join ','
        if ((Get-Date) -ge $deadline) { throw "java still running after $WaitMinutes min ($ids), aborting" }
        Say "busy: $ids alive, polling again in 5 min"
        Start-Sleep -Seconds 300
    }
}

function Wait-LogLine($path, $pattern, $timeoutS, $proc) {
    for ($i = 0; $i -lt $timeoutS; $i++) {
        if (Test-Path $path) {
            $m = Select-String -Path $path -Pattern $pattern | Select-Object -First 1
            if ($m) { return $m.Line }
        }
        if ($proc -and $proc.HasExited) { return $null }
        Start-Sleep -Seconds 1
    }
    return $null
}

function Invoke-Jcmd($procId, $command, $outFile) {
    try {
        $args2 = @("$procId") + ($command -split ' ')
        & $Jcmd @args2 *> $outFile
        return $LASTEXITCODE
    } catch {
        Set-Content $outFile "jcmd failed: $($_.Exception.Message)"
        return -1
    }
}

function Sample-Rss($proc) {
    $vals = @()
    for ($i = 0; $i -lt $RssSamples; $i++) {
        $proc.Refresh()
        $vals += [math]::Round($proc.WorkingSet64 / 1MB, 1)
        if ($i -lt $RssSamples - 1) { Start-Sleep -Seconds $RssGapSeconds }
    }
    return , $vals
}

# Classpath once: core classes, nested server jar, every bundler library.
$cpParts = @($Classes, $VanillaJar) + @(Get-ChildItem -Path $VanillaLibs -Recurse -Filter *.jar | ForEach-Object { $_.FullName })
$classpath = $cpParts -join ';'

$campaign = [ordered]@{
    schema = 1; kind = 'g2-mem-bench'; createdUtc = (Get-Date).ToUniversalTime().ToString('o')
    variant = $Variant; world = $World; importSquare = $ImportSquare; spawn = $Spawn
    flags = $Flags; jvmFlags = ($FlagSets[$Flags] -join ' '); repeats = $Repeats
    staySeconds = $StaySeconds; settleSeconds = $SettleSeconds; rssSamples = $RssSamples
    rssGapSeconds = $RssGapSeconds; seed = $Seed; gitSha = $GitSha; host = $env:COMPUTERNAME
    java = $Java; jcmd = $Jcmd; classes = $Classes
}
$campaignPath = Join-Path $OutRoot 'campaign.json'
$campaigns = @()
if (Test-Path $campaignPath) { $campaigns = @(Get-Content $campaignPath -Raw | ConvertFrom-Json) }
$campaigns += [pscustomobject]$campaign
ConvertTo-Json -InputObject @($campaigns) -Depth 5 | Set-Content $campaignPath

if ($Variant -ne 'W0') {
    $offline = Join-Path $OutRoot "offline-census-$Variant.txt"
    $cargs = @((Join-Path $ToolsDir 'g2-region.py'), 'census', $World)
    if ($ImportSquare) { $cargs += @('--square', $ImportSquare) }
    & $Python @cargs *> $offline
    Say "offline census ($LASTEXITCODE): $(Get-Content $offline -Raw)"
}

for ($rep = 1; $rep -le $Repeats; $rep++) {
    $tag = "$Variant-$Flags-r$rep"
    $runDir = Join-Path $OutRoot $tag
    New-Item -ItemType Directory -Force -Path $runDir | Out-Null
    $run = [ordered]@{ schema = 1; tag = $tag; variant = $Variant; flags = $Flags; rep = $rep }
    $proc = $null; $bot = $null
    try {
        Wait-Free
        $worldDir = $World
        if ($Variant -eq 'W0') {
            $worldDir = Join-Path $runDir 'world'
            New-Item -ItemType Directory -Force -Path (Join-Path $worldDir 'region') | Out-Null
        }
        $serverLog = Join-Path $runDir 'server.log'
        $jvm = @($FlagSets[$Flags]) + @(
            '-XX:NativeMemoryTracking=summary',
            "-Xlog:gc*:file=$(Join-Path $runDir 'gc.log'):time,uptime,level,tags",
            "-Dkestrel.datapack=$Datapack",
            '-cp', $classpath, 'dev.kestrel.net.KestrelServer', $worldDir, "$Port", "$Seed",
            "--spawn=$Spawn", '--no-save', '--mem-probe=30')
        if ($Variant -ne 'W0') {
            $jvm += '--import'
            if ($ImportSquare) { $jvm += "--import-square=$ImportSquare" }
            $jvm += "--block-hash-csv=$(Join-Path $runDir 'blocks-hash.csv')"
        }
        # @argfile: the bundler classpath is far past cmd.exe's line limit.
        $argFile = Join-Path $runDir 'java.args'
        ($jvm | ForEach-Object { '"' + ($_ -replace '\\', '\\') + '"' }) -join "`n" | Set-Content -Encoding ascii $argFile
        $run.jvmArgs = ($jvm | Where-Object { $_ -ne $classpath }) -join ' '

        Say "$tag : boot"
        $boot0 = Get-Date
        $proc = Start-Process -FilePath $Java -ArgumentList "@$argFile" -PassThru -WindowStyle Hidden `
            -RedirectStandardOutput $serverLog -RedirectStandardError (Join-Path $runDir 'server.err')
        $run.pid = $proc.Id

        $loadLine = Wait-LogLine $serverLog 'mem label=post-(import|load)' $ReadyTimeoutSeconds $proc
        if (-not $loadLine) { throw "no post-load MemProbe line (exited=$($proc.HasExited))" }
        $run.heapPostLoadRc = Invoke-Jcmd $proc.Id 'GC.heap_info' (Join-Path $runDir 'heap-postload.txt')

        $ready = Wait-LogLine $serverLog 'listening on port' $ReadyTimeoutSeconds $proc
        if (-not $ready) { throw "not ready (exited=$($proc.HasExited))" }
        $run.bootSeconds = [math]::Round(((Get-Date) - $boot0).TotalSeconds, 1)
        Say "$tag : ready in $($run.bootSeconds)s (pid $($proc.Id)), settle"

        Start-Sleep -Seconds $SettleSeconds
        $run.rssIdleMiB = Sample-Rss $proc

        $botLog = Join-Path $runDir 'bot.log'
        $bot = Start-Process -FilePath $Python -PassThru -WindowStyle Hidden `
            -ArgumentList @((Join-Path $ToolsDir 'load-bot.py'), '--port', "$Port", '--name', 'Bmem1',
                '--stay', "$StaySeconds", '--behavior', 'idle', '--seed', '1') `
            -RedirectStandardOutput $botLog -RedirectStandardError (Join-Path $runDir 'bot.err')
        $join0 = Get-Date
        $streamLine = Wait-LogLine $serverLog 'stream complete' $StreamTimeoutSeconds $proc
        $run.streamComplete = [bool]$streamLine
        $run.streamSeconds = [math]::Round(((Get-Date) - $join0).TotalSeconds, 1)
        if (-not $streamLine) { Say "$tag : WARN no 'stream complete' within $StreamTimeoutSeconds s" }
        Start-Sleep -Seconds 5
        $run.rssLoadMiB = Sample-Rss $proc
        $run.histogramRc = Invoke-Jcmd $proc.Id 'GC.class_histogram' (Join-Path $runDir 'histogram.txt')
        $run.nmtRc = Invoke-Jcmd $proc.Id 'VM.native_memory summary' (Join-Path $runDir 'nmt.txt')
        $run.heapInfoRc = Invoke-Jcmd $proc.Id 'GC.heap_info' (Join-Path $runDir 'heapinfo.txt')
        $run.botAliveAtCapture = -not $bot.HasExited
        $proc.Refresh()
        $run.rssAfterCaptureMiB = [math]::Round($proc.WorkingSet64 / 1MB, 1)
        $run.ok = $true
        Say ("$tag : rssIdle={0} rssLoad={1} jcmd hist/nmt/heap rc={2}/{3}/{4} stream={5}" -f `
            ($run.rssIdleMiB -join '/'), ($run.rssLoadMiB -join '/'), $run.histogramRc, $run.nmtRc, $run.heapInfoRc, $run.streamComplete)
    } catch {
        $run.ok = $false
        $run.error = $_.Exception.Message
        Say "$tag : ERROR $($run.error)"
    } finally {
        if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force }
        if ($bot -and -not $bot.HasExited) {
            if (-not $bot.WaitForExit(($StaySeconds + 30) * 1000)) { Stop-Process -Id $bot.Id -Force }
        }
        $run.finishedUtc = (Get-Date).ToUniversalTime().ToString('o')
        ConvertTo-Json -InputObject ([pscustomobject]$run) -Depth 5 | Set-Content (Join-Path $runDir 'run.json')
        Start-Sleep -Seconds 3
    }
}
Say "done: $OutRoot (analyze: python g2-mem-analyze.py $OutRoot)"
