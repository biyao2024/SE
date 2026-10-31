[CmdletBinding()]
param([ValidateSet('Business', 'Full', 'Stop')][string]$Mode = 'Business')
$ErrorActionPreference = 'Stop'
try {
    Set-Location (Join-Path $PSScriptRoot '..')
    if ($Mode -eq 'Stop') {
        & (Join-Path $PSScriptRoot 'stop.ps1')
    } else {
        if (-not (Test-Path -LiteralPath '.env' -PathType Leaf)) {
            throw 'Missing .env. Run scripts/init-env.ps1 once on this computer; see docs/target-environment instructions.'
        }
        Write-Host "Selected mode: $Mode (target environment; H2 local database)."
        & (Join-Path $PSScriptRoot 'start.ps1') -Dev -BusinessOnly:($Mode -eq 'Business') -TargetEnvironment
        Start-Process 'http://localhost:5173'
    }
    exit 0
} catch {
    Write-Host ("FAILED: " + $_.Exception.Message) -ForegroundColor Red
    Write-Host 'Inspect .runtime logs. Run the stop entry before retrying a partially completed startup.'
    exit 1
}
