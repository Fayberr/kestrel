# launch-bench.ps1 - self-detaching launcher. Run ONCE via SSH; it re-spawns
# itself detached and the SSH session can close immediately.
# Usage: launch-bench.ps1 [-Only x] [-Servers y] [-Flags z] [-BotOverride n]
#   [-RepeatOverride n] [-StayOverride n]
#
# WI-8 hardening: refuses to start when a bench_run child is already alive
# (CIM probe, no lockfile: the SSH parent PID is recycled after disconnect,
# so only the child can prove liveness), redirects child stdout to
# bench_run.<timestamp>.log, and prints DETACHED only after the child is
# confirmed alive. The results file stays untouched here (bench_run.ps1
# owns it); use --poll to watch progress.
# Requires PowerShell 7 (pwsh) on the bench PC; 5.1 lacks the job/timeout
# cmdlets the generated bench script relies on.
param([string]$Only = "", [string]$Servers = "", [string]$Flags = "",
      [string]$BotOverride = "", [string]$RepeatOverride = "", [string]$StayOverride = "")
$benchDir = 'C:\Users\bench\bench'
if (-not $env:BENCH_DETACHED) {
  $running = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match '^(powershell|pwsh)(\.exe)?$' -and $_.CommandLine -match 'bench_run\.ps1'
  })
  if ($running.Count -gt 0) {
    Write-Output ("REFUSED (bench_run already running pid={0})" -f ($running | ForEach-Object { $_.ProcessId }) -join ',')
    exit 1
  }
  $env:BENCH_DETACHED = "1"
  $args2 = @()
  foreach ($p in @("Only", "Servers", "Flags", "BotOverride", "RepeatOverride", "StayOverride")) {
    $v = (Get-Variable $p -ValueOnly)
    if ($v -ne "") { $args2 += "-$p"; $args2 += $v }
  }
  $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
  $childLog = Join-Path $benchDir ("bench_run.{0}.log" -f $stamp)
  $child = Start-Process -FilePath "pwsh.exe" -ArgumentList (@("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $benchDir 'launch-bench.ps1')) + $args2) -WindowStyle Hidden -RedirectStandardOutput $childLog -PassThru
  Start-Sleep -Seconds 2
  try { $ok = (Get-Process -Id $child.Id -ErrorAction Stop) -ne $null } catch { $ok = $false }
  if (-not $ok) {
    Write-Output "LAUNCH-FAILED (child died; see $childLog)"
    exit 1
  }
  Write-Output ("DETACHED pid={0} log={1}" -f $child.Id, $childLog)
  return
}
& (Join-Path $benchDir 'bench_run.ps1') -Only $Only -Servers $Servers -Flags $Flags -BotOverride $BotOverride -RepeatOverride $RepeatOverride -StayOverride $StayOverride
