[CmdletBinding()]
param([switch]$Dev, [switch]$BusinessOnly, [switch]$TargetEnvironment, [switch]$Build, [switch]$ToolsOnly)
. (Join-Path $PSScriptRoot 'common.ps1')
if ($TargetEnvironment) {
    & (Join-Path $PSScriptRoot 'check-target-env.ps1') -BusinessOnly:$BusinessOnly -Build:$Build -ToolsOnly:$ToolsOnly
    return
}
if ($BusinessOnly -or $Build -or $ToolsOnly) { throw '-BusinessOnly, -Build and -ToolsOnly on check-env.ps1 require -TargetEnvironment.' }
Import-ProjectEnvironment
Assert-ProjectSecrets
$failed = 0
function Check([string]$Name, [scriptblock]$Action) {
    try { & $Action; Write-Host "[OK] $Name" } catch { $script:failed++; Write-Warning "[FAIL] $Name : $($_.Exception.Message)" }
}
Check 'Java 21' {
    $java = Resolve-Java
    $version = (& $java -version 2>&1 | Out-String)
    if ($version -notmatch 'version "(\d+)' -or [int]$Matches[1] -ne 21) { throw 'Set JAVA_HOME to Java 21; other major versions have not been validated for this project.' }
}
Check 'Node.js 22.12+' {
    $node = Resolve-Node
    $version = [version]((& $node --version).TrimStart('v'))
    if ($version -lt [version]'22.12.0') { throw 'Use Node.js 22.12+ LTS.' }
}
Check 'Maven' { $null = Resolve-ProjectExecutable $env:MAVEN_EXE }
Check 'pnpm' { $null = Resolve-ProjectExecutable $env:PNPM_EXE }
Check 'Backend artifact' { if (-not (Test-Path -LiteralPath (Join-Path $script:ProjectRoot 'backend/target/face-attendance-1.0.0.jar'))) { throw 'Run scripts/build.ps1.' } }
Check 'Python 3.11 virtual environment' {
    $python = Resolve-ProjectExecutable $env:PYTHON_EXE
    $version = & $python -c 'import sys; print(str(sys.version_info.major) + "." + str(sys.version_info.minor))'
    if ($version -ne '3.11') { throw 'Use Python 3.11 for the full algorithm dependency set.' }
    & $python -c 'import fastapi, uvicorn, cv2, numpy, onnxruntime, insightface, mediapipe'
    if ($LASTEXITCODE -ne 0) { throw 'Algorithm packages are incomplete.' }
}
Check 'Model manifest and task file' {
    foreach ($file in @('manifest.json', 'face_landmarker.task')) {
        if (-not (Test-Path -LiteralPath (Join-Path $env:MODEL_DIR $file) -PathType Leaf)) { throw "Missing $file. Follow algorithm/Readme.md." }
    }
}
foreach ($port in @(8080, 8000, $(if ($Dev) { 5173 } else { 443 }))) {
    if (Test-LocalPort $port) { Write-Warning "Port $port is occupied; scripts/start.ps1 will refuse to replace its process." } else { Write-Host "[OK] Port $port is free" }
}
if (-not $Dev) {
    Check 'MySQL connection port' { if (-not (Test-LocalPort ([int]$env:DB_PORT))) { throw 'Start and configure MySQL separately.' } }
    Check 'Nginx' { $null = Resolve-ProjectExecutable $env:NGINX_EXE }
    Check 'HTTPS certificate' {
        foreach ($file in @('deploy/certs/localhost.pem', 'deploy/certs/localhost-key.pem', 'frontend/dist/index.html')) {
            if (-not (Test-Path -LiteralPath (Join-Path $script:ProjectRoot $file))) { throw "Missing $file" }
        }
    }
    Check 'Production secure cookie' { if ($env:COOKIE_SECURE -ne 'true') { throw 'Set COOKIE_SECURE=true for HTTPS deployment.' } }
    Check 'Production database profile' { if ($env:SPRING_PROFILES_ACTIVE -match '(^|,)\s*dev\s*(,|$)') { throw 'Use SPRING_PROFILES_ACTIVE=default for MySQL deployment.' } }
}
if ($failed -gt 0) { Write-Warning "$failed checks failed. Development business-only mode may still work; full face attendance is not ready."; exit 1 }
Write-Host 'Static environment checks passed. Model readiness, database credentials, camera trust and recognition accuracy still require runtime verification.'

