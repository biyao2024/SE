[CmdletBinding()]
param([string]$Maven, [string]$Pnpm, [string]$MavenSettings, [switch]$SkipTests, [switch]$TargetEnvironment)
. (Join-Path $PSScriptRoot 'common.ps1')
Import-ProjectEnvironment
if ($TargetEnvironment) {
    . (Join-Path $PSScriptRoot 'target-env.ps1')
    Set-TargetEnvironment
    if ($Maven) { $env:MAVEN_EXE = $Maven }
    if ($Pnpm) { $env:PNPM_EXE = $Pnpm }
    # Dependency installation occurs below; check tools before building.
    Assert-TargetEnvironment -BusinessOnly -Build -ToolsOnly
}
if (-not $Maven) { $Maven = $env:MAVEN_EXE }
if (-not $Pnpm) { $Pnpm = $env:PNPM_EXE }
$mavenPath = Resolve-ProjectExecutable $Maven
$pnpmPath = Resolve-ProjectExecutable $Pnpm
if (-not $MavenSettings) { $MavenSettings = Join-Path $script:ProjectRoot 'backend/maven-settings.xml' }
$settingsPath = (Resolve-Path -LiteralPath $MavenSettings).Path
function Invoke-Pnpm([string[]]$Commands) {
    if ([IO.Path]::GetExtension($pnpmPath) -in @('.cjs', '.js', '.mjs')) {
        $node = Resolve-Node
        & $node $pnpmPath @Commands
    } else { & $pnpmPath @Commands }
    if ($LASTEXITCODE -ne 0) { throw 'pnpm command failed.' }
}
if ($env:JAVA_HOME) { $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH }
if ($env:NODE_HOME) { $env:PATH = $env:NODE_HOME + ';' + $env:PATH }
Push-Location (Join-Path $script:ProjectRoot 'backend')
try {
    $arguments = @('-gs', $settingsPath, '-s', $settingsPath, 'clean', 'package', '--no-transfer-progress')
    if ($SkipTests) { $arguments += '-DskipTests' }
    & $mavenPath @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
} finally { Pop-Location }
Push-Location (Join-Path $script:ProjectRoot 'frontend')
try {
    Invoke-Pnpm @('install', '--frozen-lockfile')
    if ($TargetEnvironment) { Assert-TargetEnvironment -BusinessOnly -Build }
    if (-not $SkipTests) { Invoke-Pnpm @('test') }
    Invoke-Pnpm @('build')
} finally { Pop-Location }
Write-Host 'Build complete. Run algorithm tests separately in its Python environment.'

