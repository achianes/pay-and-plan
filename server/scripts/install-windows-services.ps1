# Pay & Plan - Windows services setup
# RUN THIS AS ADMINISTRATOR (right click -> Run with PowerShell, or from an elevated prompt):
#   powershell -ExecutionPolicy Bypass -File "C:\tmp\Pay&Plan\server\scripts\install-windows-services.ps1"
#
# It does two things:
#   1. makes the cloudflared tunnel a real always-on Windows service (it currently runs
#      as a plain user process, so it dies at logoff/reboot)
#   2. installs the Pay & Plan sync server as an always-on Windows service

$ErrorActionPreference = 'Stop'

$logDir = 'C:\ProgramData\PayAndPlan'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
Start-Transcript -Path (Join-Path $logDir 'install.log') -Force | Out-Null
trap { Write-Host "FAILED: $_" -ForegroundColor Red; Stop-Transcript | Out-Null; Start-Sleep -Seconds 20; break }

if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
        ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'This script must run elevated (Run as administrator).'
}

$projectRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$dataDir = 'C:\ProgramData\PayAndPlan'
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
New-Item -ItemType Directory -Force -Path 'C:\tmp\jt' | Out-Null

# ---------------------------------------------------------------- JWT secret
$secretFile = Join-Path $dataDir 'jwt.secret'
if (-not (Test-Path $secretFile)) {
    $bytes = New-Object byte[] 48
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    [Convert]::ToBase64String($bytes) | Set-Content -Path $secretFile -NoNewline -Encoding ASCII
    Write-Host "Generated a JWT secret in $secretFile"
}
$jwtSecret = (Get-Content $secretFile -Raw).Trim()

# the scheduled task cannot carry environment variables, so the server reads this file
$configJson = @{ port = 8099; dataDir = $dataDir; jwtSecret = $jwtSecret; maxUploadMb = 25 } |
    ConvertTo-Json
# WriteAllText with a plain UTF8Encoding: Set-Content -Encoding UTF8 adds a BOM,
# which JSON.parse on the node side refuses
[IO.File]::WriteAllText((Join-Path $dataDir 'config.json'), $configJson,
    (New-Object System.Text.UTF8Encoding $false))

# ---------------------------------------------------------------- 1. cloudflared
Write-Host "`n=== cloudflared ===" -ForegroundColor Cyan

$userCfg = Join-Path $env:USERPROFILE '.cloudflared'
$systemCfg = 'C:\Windows\System32\config\systemprofile\.cloudflared'

# the service runs as LocalSystem, so the config and the tunnel credentials must live there
New-Item -ItemType Directory -Force -Path $systemCfg | Out-Null
Copy-Item (Join-Path $userCfg 'config.yml') $systemCfg -Force
Get-ChildItem $userCfg -Filter '*.json' | ForEach-Object { Copy-Item $_.FullName $systemCfg -Force }
if (Test-Path (Join-Path $userCfg 'cert.pem')) {
    Copy-Item (Join-Path $userCfg 'cert.pem') $systemCfg -Force
}

# the credentials path inside config.yml points at the user profile: repoint it
$cfgPath = Join-Path $systemCfg 'config.yml'
(Get-Content $cfgPath -Raw) -replace [regex]::Escape($userCfg), $systemCfg |
    Set-Content $cfgPath -Encoding UTF8

$svc = Get-Service cloudflared -ErrorAction SilentlyContinue
if ($null -eq $svc) {
    & 'C:\Program Files (x86)\cloudflared\cloudflared.exe' --config $cfgPath service install
} else {
    sc.exe config cloudflared start= auto | Out-Null
}
sc.exe failure cloudflared reset= 86400 actions= restart/5000/restart/10000/restart/30000 | Out-Null

# stop the hand-started process so the service can take the tunnel over
Get-CimInstance Win32_Process -Filter "name='cloudflared.exe'" |
    Where-Object { $_.CommandLine -notlike '*system32*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

Start-Service cloudflared
Write-Host ("cloudflared service: " + (Get-Service cloudflared).Status)

# ---------------------------------------------------------------- 2. Pay & Plan server
Write-Host "`n=== Pay & Plan server ===" -ForegroundColor Cyan

$node = (Get-Command node).Source
$entry = Join-Path $projectRoot 'src\index.js'

# A scheduled task running as SYSTEM at boot is the least intrusive way to get a
# node process supervised on Windows without pulling in nssm or pm2.
$taskName = 'PayAndPlanServer'

Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue

# The launcher lives in ProgramData, not in the project folder: Task Scheduler splits
# the command on the "&" of "C:	mp\Pay&Plan", so the path it is given must not contain one.
# The launcher redirects node output into server.log, which is how failures become visible.
$launcher = Join-Path $dataDir 'start-payplan.cmd'
$action = New-ScheduledTaskAction -Execute $launcher -WorkingDirectory $dataDir
$trigger = New-ScheduledTaskTrigger -AtStartup
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -RestartCount 5 -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit (New-TimeSpan -Seconds 0)

Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger `
    -Principal $principal -Settings $settings -Force | Out-Null

$registered = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
if (-not $registered) { throw "the scheduled task was not registered" }
Write-Host "task registered: $($registered.TaskName) [$($registered.State)]"

# only now is it safe to hand the port over: drop the old per user startup shortcut,
# stop whoever is holding 8099 and let the task own it
$startupVbs = Join-Path ([Environment]::GetFolderPath('Startup')) 'PayAndPlanServer.vbs'
if (Test-Path $startupVbs) { Remove-Item $startupVbs -Force }

Get-NetTCPConnection -LocalPort 8099 -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique |
    ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 2

Start-ScheduledTask -TaskName $taskName

Start-Sleep -Seconds 8
try {
    $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8099/api/health' -TimeoutSec 5
    Write-Host "Pay & Plan server is up: $($health | ConvertTo-Json -Compress)" -ForegroundColor Green
} catch {
    Write-Warning "Server did not answer yet. Check $dataDir\server.log"
}

Write-Host "`nDone. Public address: https://pay.achianes.net" -ForegroundColor Green
Write-Host "If that name does not resolve yet, add the DNS record (see README)."
Get-ScheduledTaskInfo -TaskName $taskName |
    Select-Object LastRunTime, LastTaskResult | Format-List | Out-String | Write-Host
Get-Content (Join-Path $dataDir 'server.log') -Tail 5 | Write-Host
Stop-Transcript | Out-Null
Start-Sleep -Seconds 5
