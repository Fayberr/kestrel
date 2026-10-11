# Kestrel release zip: one-time setup (Windows).
#
# Downloads the two things this zip cannot contain and writes the classpath file
# the launcher reads:
#   1. Temurin JDK 25, but only when the machine has no Java 25 of its own.
#      Pinned by sha256 (Adoptium's own published checksum for that release).
#   2. Mojang's pinned 26.2 server bundler, pinned by sha1, then the nested
#      server jar, libraries and datapack are extracted from it exactly the way
#      the repository's scripts/setup-env.sh does on Linux.
#
# Neither file can be redistributed inside the zip, which is why the first run
# needs network access once. Idempotent: re-running reuses anything already
# correct and only re-extracts what is missing.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$runtime = Join-Path $here 'runtime'
$version = '26.2'
$serverSha1 = '823e2250d24b3ddac457a60c92a6a941943fcd6a'
$serverUrl = "https://piston-data.mojang.com/v1/objects/$serverSha1/server.jar"
$jdkSha256 = '00c847d804f4a78e9f04f2683faf14fed898535b177b7fc704486cb0284e9283'
$jdkUrl = 'https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1.zip'

New-Item -ItemType Directory -Force -Path $runtime | Out-Null

function Test-Java25([string]$exe) {
    if (-not $exe -or -not (Test-Path $exe)) { return $false }
    $out = & $exe -version 2>&1 | Out-String
    return $out -match '"25'
}

function Get-RemoteFile([string]$url, [string]$dest) {
    Write-Host "[setup] downloading $url"
    Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing
}

function Expand-ZipEntries([string]$zipPath, [string[]]$prefixes, [string]$dest) {
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    $zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        foreach ($entry in $zip.Entries) {
            foreach ($prefix in $prefixes) {
                if ($entry.FullName.StartsWith($prefix)) {
                    $target = Join-Path $dest $entry.FullName
                    if ($entry.FullName.EndsWith('/')) {
                        New-Item -ItemType Directory -Force -Path $target | Out-Null
                    } else {
                        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
                        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
                    }
                    break
                }
            }
        }
    } finally {
        $zip.Dispose()
    }
}

# 1. JDK, only when none is already usable.
$bundledJava = Join-Path $runtime 'jdk\bin\java.exe'
$javaHomeJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { $null }
$pathJava = (Get-Command java.exe -ErrorAction SilentlyContinue).Source

if (Test-Java25 $bundledJava) {
    Write-Host '[setup] bundled JDK already present'
} elseif (Test-Java25 $javaHomeJava) {
    Write-Host "[setup] using the Java 25 in JAVA_HOME ($env:JAVA_HOME)"
} elseif (Test-Java25 $pathJava) {
    Write-Host "[setup] using the Java 25 already on PATH ($pathJava)"
} else {
    Write-Host '[setup] no Java 25 found, downloading Temurin 25.0.4.1+1, about 200 MB'
    $tmpJdk = Join-Path $env:TEMP 'kestrel-jdk25.zip'
    Get-RemoteFile $jdkUrl $tmpJdk
    $gotJdk = (Get-FileHash $tmpJdk -Algorithm SHA256).Hash.ToLower()
    if ($gotJdk -ne $jdkSha256) {
        throw "[setup] JDK sha256 mismatch: got $gotJdk, expected $jdkSha256"
    }
    $stage = Join-Path $runtime 'jdk-stage'
    if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
    Expand-Archive -Path $tmpJdk -DestinationPath $stage -Force
    $inner = Get-ChildItem $stage -Directory | Select-Object -First 1
    $jdkTarget = Join-Path $runtime 'jdk'
    New-Item -ItemType Directory -Force -Path $jdkTarget | Out-Null
    Move-Item -Path (Join-Path $inner.FullName '*') -Destination $jdkTarget -Force
    Remove-Item -Recurse -Force $stage
    Remove-Item -Force $tmpJdk
    & (Join-Path $jdkTarget 'bin\java.exe') -version
}

# 2. Vanilla server jar, libraries and datapack.
$bundler = Join-Path $runtime 'server-bundler.jar'
$needBundler = $true
if (Test-Path $bundler) {
    if ((Get-FileHash $bundler -Algorithm SHA1).Hash.ToLower() -eq $serverSha1) {
        $needBundler = $false
    }
}
if ($needBundler) {
    Write-Host '[setup] downloading the pinned 26.2 server bundler, about 60 MB'
    Get-RemoteFile $serverUrl $bundler
}
$gotBundler = (Get-FileHash $bundler -Algorithm SHA1).Hash.ToLower()
if ($gotBundler -ne $serverSha1) {
    throw "[setup] server bundler sha1 mismatch: got $gotBundler, expected $serverSha1"
}

Write-Host '[setup] extracting the server jar and libraries'
$vinner = Join-Path $runtime 'vinner'
if (Test-Path $vinner) { Remove-Item -Recurse -Force $vinner }
Expand-ZipEntries $bundler @('META-INF/versions/', 'META-INF/libraries/') $vinner

Write-Host '[setup] extracting the vanilla datapack'
$nested = Join-Path $vinner "META-INF\versions\${version}\server-${version}.jar"
$packdump = Join-Path $runtime 'packdump'
if (Test-Path $packdump) { Remove-Item -Recurse -Force $packdump }
Expand-ZipEntries $nested @('data/') $packdump

New-Item -ItemType Directory -Force -Path (Join-Path $runtime 'play') | Out-Null

$libs = (Get-ChildItem (Join-Path $vinner 'META-INF\libraries') -Recurse -Filter *.jar |
    Sort-Object FullName | ForEach-Object { $_.FullName }) -join ';'
$cp = (Join-Path $here 'classes') + ';' + $nested + ';' + $libs
Set-Content -Path (Join-Path $runtime 'cp.txt') -Value $cp -Encoding ASCII

Write-Host '[setup] done. Start the server with start.bat'
Write-Host "[setup] classpath entries: $((($cp -split ';').Count))"
