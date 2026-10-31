[CmdletBinding()]
param([switch]$Dev, [switch]$BusinessOnly, [switch]$MySql, [switch]$TargetEnvironment, [string]$LanHttpsIp)
. (Join-Path $PSScriptRoot 'common.ps1')
Import-ProjectEnvironment
Assert-ProjectSecrets
if ($TargetEnvironment) {
    . (Join-Path $PSScriptRoot 'target-env.ps1')
    Set-TargetEnvironment
    Assert-TargetEnvironment -BusinessOnly:$BusinessOnly
}
if ($BusinessOnly -and -not $Dev) { throw '-BusinessOnly is only supported with -Dev.' }
if ($MySql -and -not $Dev) { throw '-MySql selects the development database. Production already uses DB_URL.' }
if ($MySql) {
    if (-not $env:DB_URL -or -not $env:DB_URL.StartsWith('jdbc:mysql://')) { throw '-MySql requires a MySQL JDBC URL in DB_URL.' }
    if ([string]::IsNullOrWhiteSpace($env:DB_USERNAME)) { throw 'Configure DB_USERNAME with the dedicated MySQL account.' }
    if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD) -or $env:DB_PASSWORD -match '^(CHANGE_ME|REPLACE_|GENERATE_WITH_)') { throw 'Set DB_PASSWORD to the actual MySQL account password before using -MySql.' }
}
$jar = Join-Path $script:ProjectRoot 'backend/target/face-attendance-1.0.0.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build the backend before starting.' }
if ($Dev) {
    $env:SPRING_PROFILES_ACTIVE = 'dev'
    $env:COOKIE_SECURE = 'false'
    $env:APP_ALLOWED_ORIGIN = 'http://localhost:5173,http://127.0.0.1:5173'
} elseif ($env:COOKIE_SECURE -ne 'true') { throw 'Production HTTPS startup requires COOKIE_SECURE=true.' }
if (-not $Dev -and $env:SPRING_PROFILES_ACTIVE -match '(^|,)\s*dev\s*(,|$)') { throw 'Production startup must not activate the H2 dev profile. Set SPRING_PROFILES_ACTIVE=default.' }
$ports = @(8080)
if (-not $BusinessOnly) { $ports += 8000 }
if ($Dev) { $ports += 5173 } else { $ports += 443 }
if ($LanHttpsIp) { $ports += 5174 }
foreach ($port in $ports) { if (Test-LocalPort $port) { throw "Port $port is occupied. Refusing to start or kill the existing listener." } }
$java = Resolve-Java
$node = $null
$python = $null
$nginx = $null
if (-not $BusinessOnly) { $python = Resolve-ProjectExecutable $env:PYTHON_EXE }
if ($Dev) {
    $node = Resolve-Node
    $vite = Join-Path $script:ProjectRoot 'frontend/node_modules/vite/bin/vite.js'
    if (-not (Test-Path -LiteralPath $vite)) { throw 'Run pnpm install in frontend first.' }
} else {
    $nginx = Resolve-ProjectExecutable $env:NGINX_EXE
    if (-not (Test-Path -LiteralPath (Join-Path $script:ProjectRoot 'frontend/dist/index.html'))) { throw 'Build the frontend before production startup.' }
    New-Item -ItemType Directory -Path $script:RuntimeRoot -Force | Out-Null
    & $nginx -t -p ($script:ProjectRoot.Replace('\', '/')) -c deploy/nginx.conf
    if ($LASTEXITCODE -ne 0) { throw 'Nginx configuration or certificate validation failed.' }
}
$originalDatasource = @{}
$lanEnvironment = @{}
if ($LanHttpsIp) {
    $parsedIp = $null
    if (-not $Dev -or -not [Net.IPAddress]::TryParse($LanHttpsIp, [ref]$parsedIp) -or $parsedIp.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) {
        throw '-LanHttpsIp requires -Dev and an IPv4 LAN address.'
    }
    $LanHttpsIp = $parsedIp.ToString()
    $certRoot = Join-Path $script:ProjectRoot 'deploy/certs/lan'
    if (-not (Test-Path -LiteralPath (Join-Path $certRoot 'server.pem')) -or -not (Test-Path -LiteralPath (Join-Path $certRoot 'server-key.pem'))) {
        throw 'Missing deploy/certs/lan/server.pem or server-key.pem. Prepare a certificate for the selected LAN IP first.'
    }
    if ($env:SPRING_APPLICATION_JSON) { throw 'An existing SPRING_APPLICATION_JSON is configured; use a fresh terminal and inspect it before LAN startup.' }
}
try {
    if ($LanHttpsIp) {
        $origin = "https://${LanHttpsIp}:5173"
        $overrides = @{
            FACE_LAN_HTTPS = 'true'
            FACE_LAN_CERT = Join-Path $certRoot 'server.pem'
            FACE_LAN_KEY = Join-Path $certRoot 'server-key.pem'
            FACE_LOCAL_HTTP = 'false'
            # JSON properties override application-dev.yml's HTTP-only cookie defaults.
            # Keep the H2 profile while explicitly trusting only the configured browser origins.
            SPRING_APPLICATION_JSON = (@{
                app = @{ 'secure-cookie' = $true; 'allowed-origins' = "$origin,https://localhost:5173,https://127.0.0.1:5173,http://localhost:5174,http://127.0.0.1:5174" }
                server = @{ servlet = @{ session = @{ cookie = @{ secure = $true } } } }
            } | ConvertTo-Json -Depth 8 -Compress)
        }
        foreach ($name in $overrides.Keys) {
            $lanEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            [Environment]::SetEnvironmentVariable($name, $overrides[$name], 'Process')
        }
    }
    if ($MySql) {
        # Environment overrides the H2 values in application-dev.yml. Keep credentials
        # out of process arguments, and restore the caller environment after startup.
        $mysqlDatasource = @{
            SPRING_DATASOURCE_URL = $env:DB_URL
            SPRING_DATASOURCE_USERNAME = $env:DB_USERNAME
            SPRING_DATASOURCE_PASSWORD = $env:DB_PASSWORD
        }
        foreach ($name in $mysqlDatasource.Keys) {
            $originalDatasource[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            [Environment]::SetEnvironmentVariable($name, $mysqlDatasource[$name], 'Process')
        }
        Write-Host 'Development database: MySQL, using DB_URL / DB_USERNAME / DB_PASSWORD from .env.'
    }
    Start-OwnedProcess 'backend' $java @('-jar', $jar) (Join-Path $script:ProjectRoot 'backend')
    Wait-LocalPort 8080
    if (-not $BusinessOnly) {
        Start-OwnedProcess 'algorithm' $python @('-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', '8000', '--workers', '1', '--ws-max-size', '204800', '--ws-max-queue', '2') (Join-Path $script:ProjectRoot 'algorithm')
        Wait-LocalPort 8000
    }
    if ($Dev) {
        $frontendHost = if ($LanHttpsIp) { '0.0.0.0' } else { '127.0.0.1' }
        Start-OwnedProcess 'frontend' $node @($vite, '--host', $frontendHost, '--port', '5173', '--strictPort') (Join-Path $script:ProjectRoot 'frontend')
        Wait-LocalPort 5173
        if ($LanHttpsIp) {
            $env:FACE_LAN_HTTPS = 'false'
            $env:FACE_LOCAL_HTTP = 'true'
            Start-OwnedProcess 'frontend-local' $node @($vite, '--host', '127.0.0.1', '--port', '5174', '--strictPort') (Join-Path $script:ProjectRoot 'frontend')
            Wait-LocalPort 5174
            Write-Host 'Desktop management URL: http://localhost:5174 (loopback only).'
        }
        if ($LanHttpsIp) { Write-Host "LAN HTTPS URL: https://${LanHttpsIp}:5173 (trusted local network only)." }
        else { Write-Host 'Development URL: http://localhost:5173 (local computer only).' }
    } else {
        Start-OwnedProcess 'nginx' $nginx @('-p', $script:ProjectRoot.Replace('\', '/'), '-c', 'deploy/nginx.conf') $script:ProjectRoot
        Wait-LocalPort 443
        Write-Host 'HTTPS URL: https://localhost; LAN access requires a matching certificate and APP_ALLOWED_ORIGIN.'
    }
} catch {
    Write-Warning 'Startup did not finish. Inspect .runtime logs; run scripts/stop.ps1 to stop any processes already started by this project.'
    throw
} finally {
    foreach ($name in $lanEnvironment.Keys) {
        if ($null -eq $lanEnvironment[$name]) { Remove-Item -LiteralPath ('Env:' + $name) -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($name, $lanEnvironment[$name], 'Process') }
    }
    foreach ($name in $originalDatasource.Keys) {
        if ($null -eq $originalDatasource[$name]) {
            # Explicit removal also works on PowerShell/.NET versions that preserve
            # an empty environment variable when a null string argument is coerced.
            Remove-Item -LiteralPath ('Env:' + $name) -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable($name, $originalDatasource[$name], 'Process')
        }
    }
}
Write-Host 'Listeners are up. Confirm model/gallery readiness in the application before face enrollment or punching.'
if ($BusinessOnly) { Write-Warning 'Business-only mode: no algorithm process was started. Face enrollment and punching must fail closed.' }

