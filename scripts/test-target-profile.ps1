# Isolated script contract checks: synthetic versions and inert executable files.
# Does not read .env, run services, invoke real Java/Python, or access the network.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$sourceScripts = $PSScriptRoot
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$fixture = Join-Path $project ('.runtime/target-profile-test-' + [guid]::NewGuid().ToString('N'))
foreach ($directory in @('scripts', '.toolchain/jdk/bin', '.toolchain/node', '.toolchain/pnpm/node_modules/pnpm/dist', 'algorithm/.venv-runtime/Scripts', 'backend/target')) {
    New-Item -ItemType Directory -Path (Join-Path $fixture $directory) -Force | Out-Null
}
foreach ($name in @('common.ps1', 'target-env.ps1', 'target-environment.json')) {
    Copy-Item -LiteralPath (Join-Path $sourceScripts $name) -Destination (Join-Path $fixture ('scripts/' + $name))
}
foreach ($file in @('.toolchain/jdk/bin/java.exe', '.toolchain/jdk/bin/javac.exe', '.toolchain/node/node.exe', '.toolchain/pnpm/node_modules/pnpm/dist/pnpm.mjs', 'algorithm/.venv-runtime/Scripts/python.exe', 'backend/target/face-attendance-1.0.0.jar')) {
    [IO.File]::WriteAllText((Join-Path $fixture $file), 'INERT TEST FIXTURE - NEVER EXECUTE')
}
. (Join-Path $fixture 'scripts/common.ps1')
. (Join-Path $fixture 'scripts/target-env.ps1')
$savedEnvironment = @{}
foreach ($name in @('JAVA_HOME', 'NODE_HOME', 'PNPM_EXE', 'PYTHON_EXE', 'MODEL_DIR', 'INSIGHTFACE_MODEL', 'PATH')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$script:results = New-Object Collections.Generic.List[object]
function Expect([bool]$Condition, [string]$Reason) { if (-not $Condition) { throw $Reason } }
function Expect-Rejected([scriptblock]$Action, [string]$Pattern) {
    try { & $Action; throw 'Expected rejection did not happen.' } catch {
        if ($_.Exception.Message -notmatch $Pattern) { throw }
    }
}
function Test-Contract([string]$Name, [scriptblock]$Action) {
    $script:javaProperties = "java.specification.version = 21`nsun.arch.data.model = 64`nos.arch = amd64`n"
    $script:compiler = 'javac 21.0.10'
    $script:nodeRecord = '{"version":"24.18.0","arch":"x64","platform":"win32"}'
    $script:pnpmVersion = '11.25.0'
    $script:pythonFailure = $false
    $script:frontendFailure = $false
    $script:pythonChecks = 0
    $script:frontendChecks = 0
    try {
        & $Action
        $script:results.Add([pscustomobject]@{ name = $Name; passed = $true })
        Write-Host "PASS $Name"
    } catch {
        $script:results.Add([pscustomobject]@{ name = $Name; passed = $false; reason = $_.Exception.Message })
        Write-Host "FAIL $Name : $($_.Exception.Message)"
    }
}
# These replace every external tool execution in Assert-TargetEnvironment.
function Get-TargetNativeOutput([string]$Executable, [string[]]$Arguments) {
    switch ([IO.Path]::GetFileName($Executable)) {
        'java.exe' { return $script:javaProperties }
        'javac.exe' { return $script:compiler }
        'node.exe' {
            if ($Arguments[0] -eq '-p') { return $script:nodeRecord }
            $script:frontendChecks++
            if ($script:frontendFailure) { throw 'Synthetic frontend dependency mismatch.' }
            return 'Synthetic frontend versions checked.'
        }
        'python.exe' {
            $script:pythonChecks++
            if ($script:pythonFailure) { throw 'Synthetic Python dependency mismatch.' }
            return 'Synthetic Python validated.'
        }
        default { throw "Unexpected executable would run: $Executable" }
    }
}
function Invoke-TargetPnpm([string[]]$Commands) {
    if ($Commands.Count -ne 1 -or $Commands[0] -ne '--version') { throw 'Test forbids pnpm mutations.' }
    return $script:pnpmVersion
}
try {
    Test-Contract 'Bundled tools override stale absolute paths without editing .env' {
        $env:JAVA_HOME = 'Z:\old-machine\jdk'
        $env:NODE_HOME = 'Z:\old-machine\node'
        $env:PNPM_EXE = 'Z:\old-machine\pnpm.cmd'
        $env:PYTHON_EXE = 'Z:\old-machine\.venv\Scripts\python.exe'
        Set-TargetEnvironment
        Expect ($env:JAVA_HOME -eq (Join-Path $fixture '.toolchain/jdk')) 'Java selection failed.'
        Expect ($env:NODE_HOME -eq (Join-Path $fixture '.toolchain/node')) 'Node selection failed.'
        Expect ($env:PNPM_EXE -eq (Join-Path $fixture '.toolchain/pnpm/node_modules/pnpm/dist/pnpm.mjs')) 'pnpm selection failed.'
        Expect ($env:PYTHON_EXE -eq (Join-Path $fixture 'algorithm/.venv-runtime/Scripts/python.exe')) 'Python selection failed.'
        Expect (-not (Test-Path -LiteralPath (Join-Path $fixture '.env'))) 'Target selection created .env.'
    }
    Test-Contract 'Business tool checks need neither Maven nor Python nor installed frontend' {
        Assert-TargetEnvironment -BusinessOnly -ToolsOnly
        Expect ($script:pythonChecks -eq 0 -and $script:frontendChecks -eq 0) 'Unexpected dependency check.'
    }
    Test-Contract 'Wrong Node patch version is rejected' {
        $script:nodeRecord = '{"version":"24.17.0","arch":"x64","platform":"win32"}'
        Expect-Rejected { Assert-TargetEnvironment -BusinessOnly -ToolsOnly } 'Target requires Node 24.18.0'
    }
    Test-Contract 'Wrong Node architecture is rejected' {
        $script:nodeRecord = '{"version":"24.18.0","arch":"arm64","platform":"win32"}'
        Expect-Rejected { Assert-TargetEnvironment -BusinessOnly -ToolsOnly } 'Target requires Node'
    }
    Test-Contract 'Wrong pnpm patch version is rejected' {
        $script:pnpmVersion = '11.24.0'
        Expect-Rejected { Assert-TargetEnvironment -BusinessOnly -ToolsOnly } 'Target requires pnpm 11.25.0'
    }
    Test-Contract 'Wrong Java major version is rejected' {
        $script:javaProperties = $script:javaProperties.Replace('version = 21', 'version = 17')
        Expect-Rejected { Assert-TargetEnvironment -BusinessOnly -ToolsOnly } 'requires JDK 21 x64'
    }
    Test-Contract '32-bit Java is rejected' {
        $script:javaProperties = $script:javaProperties.Replace('model = 64', 'model = 32')
        Expect-Rejected { Assert-TargetEnvironment -BusinessOnly -ToolsOnly } 'requires JDK 21 x64'
    }
    Test-Contract 'Compiler major version must match JDK 21' {
        $script:compiler = 'javac 17.0.1'
        Expect-Rejected { Assert-TargetEnvironment -BusinessOnly -ToolsOnly } 'javac must also be version 21'
    }
    Test-Contract 'Ready business profile checks frontend but skips algorithm' {
        Assert-TargetEnvironment -BusinessOnly
        Expect ($script:frontendChecks -eq 1 -and $script:pythonChecks -eq 0) 'Incorrect business check scope.'
    }
    Test-Contract 'Full profile invokes Python dependency and model checks' {
        Assert-TargetEnvironment
        Expect ($script:frontendChecks -eq 1 -and $script:pythonChecks -eq 1) 'Incorrect full check scope.'
    }
    Test-Contract 'Frontend version mismatch prevents startup validation' {
        $script:frontendFailure = $true
        Expect-Rejected { Assert-TargetEnvironment -BusinessOnly } 'Synthetic frontend dependency mismatch'
    }
    Test-Contract 'Full profile rejects Python validation failure' {
        $script:pythonFailure = $true
        Expect-Rejected { Assert-TargetEnvironment } 'Synthetic Python dependency mismatch'
    }
    Test-Contract 'ToolsOnly checks omit frontend and model installation checks' {
        Assert-TargetEnvironment -ToolsOnly
        Expect ($script:frontendChecks -eq 0 -and $script:pythonChecks -eq 1) 'Incorrect tools-only check scope.'
    }
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        if ($null -eq $savedEnvironment[$name]) { Remove-Item -LiteralPath ('Env:' + $name) -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
    }
}
$failed = @($script:results | Where-Object { -not $_.passed }).Count
$report = [ordered]@{
    testedAt = [DateTime]::UtcNow.ToString('o')
    powerShellVersion = $PSVersionTable.PSVersion.ToString()
    sourceSha256 = (Get-FileHash -LiteralPath (Join-Path $sourceScripts 'target-env.ps1') -Algorithm SHA256).Hash
    passed = $script:results.Count - $failed
    total = $script:results.Count
    scope = 'Isolated mocks only; no real services, credentials, package installs or network.'
    tests = @($script:results.ToArray())
}
$report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $fixture 'report.json') -Encoding UTF8
Write-Host "RESULT: $($report.passed)/$($report.total) passed. Report: $fixture/report.json"
if ($failed -gt 0) { exit 1 }
