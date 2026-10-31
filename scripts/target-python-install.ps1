# Shared dependency installation functions; dot-source common.ps1 and target-env.ps1.
function Invoke-TargetPip([string]$Python, [string[]]$Commands) {
    & $Python -m pip --disable-pip-version-check @Commands
    if ($LASTEXITCODE -ne 0) { throw 'Target pip command failed; inspect its output. Source builds require MSVC C++ Build Tools and Windows SDK.' }
}

function Get-VerifiedTargetWheels([string]$Directory) {
    if (-not (Test-Path -LiteralPath $Directory -PathType Container)) { return }
    $files = @(Get-ChildItem -LiteralPath $Directory -Filter '*.whl' -File)
    $manifestPath = Join-Path $Directory 'manifest.json'
    if (-not $files.Count -and -not (Test-Path -LiteralPath $manifestPath)) { return }
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw 'Local wheel files require a provenance/hash manifest.json.' }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($manifest.schemaVersion -ne 1 -or $manifest.platform -ne 'win_amd64' -or $manifest.pythonVersion -notmatch '^3\.11(?:\.|$)') { throw 'Wheel manifest must identify CPython 3.11 Windows x64.' }
    $entries = @($manifest.wheels)
    if ($entries.Count -ne 1 -or $files.Count -ne 1) { throw 'Expected exactly one verified InsightFace wheel in wheelhouse.' }
    $entry = $entries[0]
    if ($entry.filename -cne 'insightface-0.7.3-cp311-cp311-win_amd64.whl' -or $files[0].Name -cne $entry.filename) { throw 'Unexpected wheel package, version, ABI, platform or filename.' }
    $sourceUri = [uri]$entry.sourceUrl
    if ($sourceUri.Scheme -ne 'https' -or $sourceUri.Host -ne 'files.pythonhosted.org' -or -not $sourceUri.AbsolutePath.EndsWith('/insightface-0.7.3.tar.gz') -or $entry.sourceSha256 -notmatch '^[a-fA-F0-9]{64}$') { throw 'Wheel manifest must record the official PyPI InsightFace source archive and SHA-256.' }
    if ($entry.sha256 -notmatch '^[a-fA-F0-9]{64}$' -or (Get-FileHash -LiteralPath $files[0].FullName -Algorithm SHA256).Hash -ne $entry.sha256) { throw 'Local InsightFace wheel SHA-256 mismatch; refusing to install it.' }
    Write-Host '[OK] Local CPython 3.11 Windows x64 wheel SHA-256 verified; official source provenance recorded.'
    return $files[0].FullName
}

function Install-TargetPythonDependencies([string]$Python, [string]$Requirements, [string]$BuildConstraints, [string]$Wheelhouse) {
    $constraints = (Resolve-Path -LiteralPath $BuildConstraints).Path
    if ([IO.File]::ReadAllText($constraints) -notmatch '(?m)^\s*numpy==1\.26\.4\s*(?:#.*)?$') { throw 'Build isolation must pin numpy==1.26.4.' }
    $wheels = @(Get-VerifiedTargetWheels $Wheelhouse)
    $target = Get-TargetEnvironment
    $previousConstraint = [Environment]::GetEnvironmentVariable('PIP_CONSTRAINT', 'Process')
    try {
        # pip 24 splits environment option lists on whitespace. A local file URI
        # percent-encodes spaces and survives both top-level and build subprocesses.
        $env:PIP_CONSTRAINT = ([uri]$constraints).AbsoluteUri
        $pipVersion = Get-TargetNativeOutput $Python @('-c', "from importlib.metadata import version; print(version('pip'))")
        if ($pipVersion.Trim() -ne $target.pipVersion) { Invoke-TargetPip $Python @('install', ('pip==' + $target.pipVersion)) }
        if ($wheels.Count) {
            # Explicit local path prevents an unrelated cached wheel being selected.
            Invoke-TargetPip $Python @('install', '--force-reinstall', '--no-deps', $wheels[0])
        } else {
            Write-Warning 'No verified local wheel. Building official InsightFace source requires MSVC C++ Build Tools and Windows SDK.'
            # A cached wheel built against NumPy 2 must not silently be reused.
            Invoke-TargetPip $Python @('install', '--force-reinstall', '--no-deps', '--no-binary=insightface', '--no-cache-dir', 'insightface==0.7.3')
        }
        Invoke-TargetPip $Python @('install', '-r', $Requirements)
        Invoke-TargetPip $Python @('check')
    } finally {
        if ($null -eq $previousConstraint) { Remove-Item -LiteralPath 'Env:PIP_CONSTRAINT' -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable('PIP_CONSTRAINT', $previousConstraint, 'Process') }
    }
}
