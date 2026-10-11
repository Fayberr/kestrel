param(
    [Parameter(Mandatory=$true)][string]$SourceZip,
    [Parameter(Mandatory=$true)][string]$OutDir,
    [int]$Port=25691,
    [long]$Seed=4242424242,
    [double]$RouteY=200.0
)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$PSNativeCommandUseErrorActionPreference=$false

$java='C:\Program Files\Java\jdk-25.0.4.1+1\bin\java.exe'
$bundler='C:\Users\bench\kestrel\vanilla-boot-test\server.jar'
$src=Join-Path $OutDir 'source'
$srv=Join-Path $OutDir 'server'
$capture=Join-Path $OutDir 'vanilla-route.json'
$stdout=Join-Path $OutDir 'vanilla-server.log'
$stderr=Join-Path $OutDir 'vanilla-server.err'
$probeOut=Join-Path $OutDir 'vanilla-probe.log'
$probeErr=Join-Path $OutDir 'vanilla-probe.err'
$status=Join-Path $OutDir 'vanilla-status.txt'

function Write-Ascii([string]$path,[string[]]$lines) {
    [IO.File]::WriteAllLines($path,$lines,[Text.Encoding]::ASCII)
}

if(!(Test-Path $java)){ throw "missing java: $java" }
if(!(Test-Path $bundler)){ throw "missing vanilla bundler: $bundler" }
if(!(Test-Path $SourceZip)){ throw "missing source zip: $SourceZip" }
if(Test-Path $OutDir){ Remove-Item -Recurse -Force $OutDir }
New-Item -ItemType Directory -Force -Path $src,$srv | Out-Null
Expand-Archive -Path $SourceZip -DestinationPath $src -Force
$probe=Join-Path $src 'harness\tools\live-route-probe.py'
if(!(Test-Path $probe)){ throw 'live-route-probe.py missing from source archive' }

Set-Content -Encoding ascii -Path (Join-Path $srv 'eula.txt') -Value 'eula=true'
@"
server-port=$Port
online-mode=false
enforce-secure-profile=false
max-players=2
view-distance=2
simulation-distance=2
spawn-monsters=false
spawn-animals=false
gamemode=creative
force-gamemode=true
difficulty=peaceful
allow-flight=true
level-seed=$Seed
generate-structures=true
max-tick-time=-1
sync-chunk-writes=true
"@ | Set-Content -Encoding ascii -Path (Join-Path $srv 'server.properties')

$psi=[Diagnostics.ProcessStartInfo]::new()
$psi.FileName=$java
$psi.WorkingDirectory=$srv
$psi.UseShellExecute=$false
$psi.CreateNoWindow=$true
$psi.RedirectStandardInput=$true
$psi.RedirectStandardOutput=$true
$psi.RedirectStandardError=$true
foreach($arg in @('-Xms512M','-Xmx2G','-jar',$bundler,'--nogui')) {
    [void]$psi.ArgumentList.Add($arg)
}
$server=[Diagnostics.Process]::new()
$server.StartInfo=$psi
[void]$server.Start()
$outTask=$server.StandardOutput.ReadToEndAsync()
$errTask=$server.StandardError.ReadToEndAsync()

$probeExit=-1
try {
    $ready=$false
    for($i=0;$i -lt 360;$i++) {
        if($server.HasExited){ break }
        if(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
            $ready=$true
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if(!$ready){ throw "vanilla server did not listen on $Port" }

    # Vanilla 26.2 renamed the old spawnRadius gamerule to the
    # namespaced minecraft:respawn_radius key. Keep radius zero so the
    # final route starts at the exact same X/Z origin as Kestrel.
    $server.StandardInput.WriteLine('gamerule minecraft:respawn_radius 0')
    $spawnY=[int][math]::Floor($RouteY)
    $server.StandardInput.WriteLine("setworldspawn 0 $spawnY 0")
    $server.StandardInput.Flush()
    Start-Sleep -Seconds 2

    $args=@(
        $probe,'capture',
        '--host','127.0.0.1','--port',"$Port",
        '--name','VanFinalRoute',
        '--out',$capture,
        '--origin-x','0.5','--origin-z','0.5',
        '--route-y',"$RouteY",
        '--dx','64','--dz','64',
        '--score-radius','2','--view-distance','2',
        '--step','0.45','--interval','0.05',
        '--settle','20','--tail','20','--join-timeout','120',
        '--ascend-to-route-y'
    )
    $p=Start-Process -FilePath 'py.exe' -ArgumentList $args -Wait -PassThru -NoNewWindow -RedirectStandardOutput $probeOut -RedirectStandardError $probeErr
    $probeExit=$p.ExitCode
    if($probeExit -ne 0){ throw "vanilla route probe exit=$probeExit" }

    Write-Ascii $status @(
        'RESULT=PASS',
        "SEED=$Seed",
        "PORT=$Port",
        "ROUTE_Y=$RouteY",
        "PROBE_EXIT=$probeExit",
        "CAPTURE=$capture"
    )
} catch {
    Write-Ascii $status @(
        'RESULT=FAIL',
        "PROBE_EXIT=$probeExit",
        ('ERROR=' + ($_.Exception.Message -replace '[^\x20-\x7E]','?'))
    )
    throw
} finally {
    if($server -and !$server.HasExited) {
        try {
            $server.StandardInput.WriteLine('stop')
            $server.StandardInput.Flush()
        } catch {}
        if(-not $server.WaitForExit(30000)) {
            Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
            [void]$server.WaitForExit(10000)
        }
    }
    try { [IO.File]::WriteAllText($stdout,$outTask.GetAwaiter().GetResult(),[Text.Encoding]::UTF8) } catch {}
    try { [IO.File]::WriteAllText($stderr,$errTask.GetAwaiter().GetResult(),[Text.Encoding]::UTF8) } catch {}
}

Get-Content $status
Get-Content $probeOut
