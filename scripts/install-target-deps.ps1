[CmdletBinding()]
param([switch]$BusinessOnly, [string]$PythonBase, [string]$AlgorithmLock)
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'target-env.ps1')
. (Join-Path $PSScriptRoot 'target-python-install.ps1')
if (Test-Path -LiteralPath (Join-Path $script:ProjectRoot '.env')) { Import-ProjectEnvironment }
Set-TargetEnvironment
Assert-TargetEnvironment -BusinessOnly -ToolsOnly
Push-Location (Join-Path $script:ProjectRoot 'frontend')
try { Invoke-TargetPnpm @('install', '--frozen-lockfile') } finally { Pop-Location }
$node = Resolve-Node
Write-Host (Get-TargetNativeOutput $node @((Join-Path $PSScriptRoot 'check-target-frontend.cjs')))
if (-not $BusinessOnly) {
    $target = Get-TargetEnvironment
    if (-not (Test-Path -LiteralPath $env:PYTHON_EXE -PathType Leaf)) {
        if (-not $PythonBase) { $PythonBase = Join-Path $script:ProjectRoot $target.paths.pythonBase }
        $base = Resolve-ProjectExecutable $PythonBase
        $probe = Get-TargetNativeOutput $base @('-c', 'import sys,struct,platform,json; print(json.dumps(dict(major=sys.version_info.major,minor=sys.version_info.minor,bits=struct.calcsize(chr(80))*8,platform=sys.platform,machine=platform.machine())))') | ConvertFrom-Json
        if ($probe.major -ne 3 -or $probe.minor -ne 11 -or $probe.bits -ne 64 -or $probe.platform -ne 'win32' -or $probe.machine -notin @('AMD64', 'x86_64')) { throw 'PythonBase must be Python 3.11 Windows x64.' }
        $venv = Join-Path $script:ProjectRoot 'algorithm/.venv-runtime'
        if (Test-Path -LiteralPath $venv) { throw 'Incomplete .venv-runtime exists. Inspect it before manually recreating; this installer will not overwrite it.' }
        Write-Host (Get-TargetNativeOutput $base @('-m', 'venv', $venv))
    }
    $python = Resolve-ProjectExecutable $env:PYTHON_EXE
    Write-Host (Get-TargetNativeOutput $python @((Join-Path $PSScriptRoot 'check-target-python.py'), '--tools-only'))
    if (-not $AlgorithmLock) {
        $AlgorithmLock = Join-Path $script:ProjectRoot 'algorithm/requirements-win-py311.lock.txt'
        if (-not (Test-Path -LiteralPath $AlgorithmLock -PathType Leaf)) {
            $AlgorithmLock = Join-Path $script:ProjectRoot 'algorithm/requirements.txt'
            Write-Warning 'No validated Python 3.11 full lock exists; using pinned direct dependencies. Record all transitive versions after validation.'
        }
    }
    $requirements = (Resolve-Path -LiteralPath $AlgorithmLock).Path
    Push-Location (Join-Path $script:ProjectRoot 'algorithm')
    try {
        Install-TargetPythonDependencies $python $requirements (Join-Path $script:ProjectRoot 'algorithm/build-constraints-win-py311.txt') (Join-Path $script:ProjectRoot 'algorithm/wheelhouse')
        Write-Host (Get-TargetNativeOutput $python @((Join-Path $PSScriptRoot 'check-target-python.py'), '--skip-models'))
    } finally { Pop-Location }
}
Write-Host 'Target dependencies installed. Prepare model files, then run check-target-env.ps1 before full startup.'
