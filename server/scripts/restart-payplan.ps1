# Restarts the Pay & Plan sync server.
# The server normally runs as SYSTEM, started by the PayAndPlanServer scheduled task,
# so a plain user session cannot stop it: this script needs to run elevated.
#
#   powershell -ExecutionPolicy Bypass -File "C:\tmp\Pay&Plan\server\scripts\restart-payplan.ps1"
#
# Only server code (server\src\*.js) needs this. The web app is served straight from
# disk, so changes under webapp\ are live as soon as the file is saved.

$ErrorActionPreference = 'Continue'

if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
        ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'This script must run elevated (Run as administrator).'
}

Write-Host 'stopping the scheduled task...'
schtasks /End /TN PayAndPlanServer *> $null

# whatever is still holding the port goes, whoever owns it
Get-NetTCPConnection -LocalPort 8099 -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique |
    ForEach-Object {
        Write-Host "killing pid $_"
        Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue
    }

Start-Sleep -Seconds 3
Write-Host 'starting it again...'
schtasks /Run /TN PayAndPlanServer *> $null

for ($i = 0; $i -lt 15; $i++) {
    Start-Sleep -Seconds 2
    try {
        $health = Invoke-RestMethod 'http://127.0.0.1:8099/api/health' -TimeoutSec 3
        Write-Host "server is up: $($health | ConvertTo-Json -Compress)" -ForegroundColor Green
        $ok = $true
        break
    } catch { }
}
if (-not $ok) { Write-Warning 'no answer yet, check C:\ProgramData\PayAndPlan\server.log' }

$owner = Get-NetTCPConnection -LocalPort 8099 -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique
Write-Host "listening pid: $owner"
Start-Sleep -Seconds 4
