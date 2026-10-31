[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'common.ps1')
foreach ($name in @('nginx', 'frontend-local', 'frontend', 'algorithm', 'backend')) {
    $path = Join-Path $script:RuntimeRoot ($name + '.process.json')
    if (-not (Test-Path -LiteralPath $path)) { continue }
    $process = Get-OwnedProcess $path
    if ($null -ne $process) {
        if ($name -eq 'nginx') {
            & $process.Path -s quit -p ($script:ProjectRoot.Replace('\', '/')) -c deploy/nginx.conf
            if ($LASTEXITCODE -ne 0) { throw 'Nginx graceful stop failed; process record retained.' }
            if (-not $process.WaitForExit(10000)) { throw 'Nginx is still shutting down; process record retained. Retry later.' }
        } else {
            Stop-Process -InputObject $process
            if (-not $process.WaitForExit(10000)) { throw "$name did not exit; process record retained." }
        }
        Write-Host "$name stopped."
    }
    Remove-Item -LiteralPath $path
}
Write-Host 'Owned project processes have stopped. MySQL is managed separately.'

