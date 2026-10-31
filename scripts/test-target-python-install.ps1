# Installation contract tests with synthetic files and mocked pip execution.
[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'target-env.ps1')
. (Join-Path $PSScriptRoot 'target-python-install.ps1')
$fixture = Join-Path $script:ProjectRoot ('.runtime/target pip install space ' + [guid]::NewGuid().ToString('N'))
$wheelhouse = Join-Path $fixture 'wheelhouse'
New-Item -ItemType Directory -Path $wheelhouse -Force | Out-Null
$wheel = Join-Path $wheelhouse 'insightface-0.7.3-cp311-cp311-win_amd64.whl'
$constraints = Join-Path $fixture 'build constraints.txt'
$requirements = Join-Path $fixture 'requirements.txt'
[IO.File]::WriteAllText($requirements, 'insightface==0.7.3')
$original = [Environment]::GetEnvironmentVariable('PIP_CONSTRAINT', 'Process')
$script:results = New-Object Collections.Generic.List[object]
function Expect([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Expect-Rejected([scriptblock]$Action, [string]$Pattern) {
    try { & $Action; throw 'Expected rejection did not happen.' } catch { if ($_.Exception.Message -notmatch $Pattern) { throw } }
}
function Get-TargetNativeOutput([string]$Executable, [string[]]$Arguments) { return $script:pipVersion }
function Invoke-TargetPip([string]$Python, [string[]]$Commands) {
    $script:commands.Add([pscustomobject]@{ arguments = @($Commands); constraint = $env:PIP_CONSTRAINT })
    if ($script:failPip -or $script:failAt -eq $script:commands.Count) { throw 'Synthetic pip failure.' }
}
function Save-Manifest {
    $script:manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $wheelhouse 'manifest.json') -Encoding UTF8
}
function Reset-Fixture {
    [IO.File]::WriteAllText($constraints, "numpy==1.26.4`n")
    [IO.File]::WriteAllText($wheel, 'Synthetic inert wheel; never install.')
    $script:manifest = [ordered]@{
        schemaVersion=1; pythonVersion='3.11.9'; platform='win_amd64'
        wheels=@([ordered]@{filename=[IO.Path]::GetFileName($wheel); sha256=(Get-FileHash -LiteralPath $wheel -Algorithm SHA256).Hash; sourceUrl='https://files.pythonhosted.org/packages/example/insightface-0.7.3.tar.gz'; sourceSha256=('a'*64)})
    }
    Save-Manifest
    $script:commands = New-Object Collections.Generic.List[object]
    $script:pipVersion = '24.0'
    $script:failPip = $false
    $script:failAt = 0
    Remove-Item -LiteralPath 'Env:PIP_CONSTRAINT' -ErrorAction SilentlyContinue
}
function Run-Install { Install-TargetPythonDependencies 'synthetic-python.exe' $requirements $constraints $wheelhouse }
function Test-Contract([string]$Name, [scriptblock]$Action) {
    Reset-Fixture
    try { & $Action; $script:results.Add([pscustomobject]@{name=$Name;passed=$true}); Write-Host "PASS $Name" }
    catch { $script:results.Add([pscustomobject]@{name=$Name;passed=$false;reason=$_.Exception.Message}); Write-Host "FAIL $Name : $($_.Exception.Message)" }
}
try {
    Test-Contract 'Verified wheel installed explicitly before full requirements and pip check' {
        Run-Install
        Expect ($script:commands.Count -eq 3) 'Expected wheel, requirements and pip check.'
        Expect ($script:commands[0].arguments -contains $wheel) 'Verified wheel not selected.'
        Expect ($script:commands[0].arguments -contains '--force-reinstall') 'Existing incompatible wheel may be reused.'
        Expect ($script:commands[1].arguments -contains $requirements) 'Requirements missing.'
        Expect ($script:commands[2].arguments[0] -eq 'check') 'pip check missing.'
        Expect ($null -eq [Environment]::GetEnvironmentVariable('PIP_CONSTRAINT','Process')) 'Absent environment value not restored.'
    }
    Test-Contract 'Constraint URI percent-encodes spaces for build subprocess inheritance' {
        Run-Install
        foreach ($command in $script:commands) {
            $value = $command.constraint
            Expect ($value.StartsWith('file:///') -and $value.Contains('%20') -and -not $value.Contains(' ')) 'Unsafe constraint URI.'
            Expect (([uri]$value).LocalPath -eq $constraints) 'Constraint URI does not round-trip.'
        }
        Expect ($script:commands[0].arguments[-1] -eq $wheel) 'Wheel path with spaces was split.'
        Expect ($script:commands[1].arguments[-1] -eq $requirements) 'Requirements path with spaces was split.'
    }
    Test-Contract 'Pre-existing constraint restored after successful install' {
        $env:PIP_CONSTRAINT = 'synthetic-old-constraint'
        Run-Install
        Expect ($env:PIP_CONSTRAINT -eq 'synthetic-old-constraint') 'Existing value changed.'
    }
    Test-Contract 'Pre-existing constraint restored after pip failure' {
        $env:PIP_CONSTRAINT = 'synthetic-old-constraint'
        $script:failPip = $true
        Expect-Rejected { Run-Install } 'Synthetic pip failure'
        Expect ($env:PIP_CONSTRAINT -eq 'synthetic-old-constraint') 'Failure leaked constraint.'
    }
    Test-Contract 'Absent constraint removed after pip failure' {
        $script:failPip = $true
        Expect-Rejected { Run-Install } 'Synthetic pip failure'
        Expect ($null -eq [Environment]::GetEnvironmentVariable('PIP_CONSTRAINT','Process')) 'Failure left empty variable.'
    }
    foreach ($stage in @(2, 3)) {
        Test-Contract ('Constraint restored and installation stops after pip stage ' + $stage + ' fails') {
            $env:PIP_CONSTRAINT = 'synthetic-old-constraint'
            $script:failAt = $stage
            Expect-Rejected { Run-Install } 'Synthetic pip failure'
            Expect ($script:commands.Count -eq $stage) 'Installation continued after a failed pip command.'
            Expect ($env:PIP_CONSTRAINT -eq 'synthetic-old-constraint') 'Failure leaked constraint.'
        }
    }
    Test-Contract 'Different pip version is explicitly pinned before package installation' {
        $script:pipVersion = '26.2'
        Run-Install
        Expect ($script:commands[0].arguments -contains 'pip==24.0') 'pip version was not pinned.'
    }
    Test-Contract 'No wheel uses source build and bypasses incompatible cached wheels' {
        Install-TargetPythonDependencies 'synthetic-python.exe' $requirements $constraints (Join-Path $fixture 'absent-wheelhouse')
        Expect ($script:commands[0].arguments -contains '--no-binary=insightface') 'Source build not selected.'
        Expect ($script:commands[0].arguments -contains '--no-cache-dir') 'Old cached wheel could be reused.'
    }
    Test-Contract 'Tampered wheel rejected before invoking pip' {
        [IO.File]::AppendAllText($wheel, 'tampered')
        Expect-Rejected { Run-Install } 'SHA-256 mismatch'
        Expect ($script:commands.Count -eq 0) 'pip called before verification.'
    }
    Test-Contract 'Wrong wheel platform rejected' {
        $script:manifest.platform = 'linux_x86_64'
        Save-Manifest
        Expect-Rejected { Run-Install } 'CPython 3.11 Windows x64'
    }
    Test-Contract 'Non-official source provenance rejected' {
        $script:manifest.wheels[0].sourceUrl = 'https://example.invalid/insightface-0.7.3.tar.gz'
        Save-Manifest
        Expect-Rejected { Run-Install } 'official PyPI'
    }
    Test-Contract 'Wheel without manifest rejected' {
        Remove-Item -LiteralPath (Join-Path $wheelhouse 'manifest.json')
        Expect-Rejected { Run-Install } 'require a provenance/hash manifest'
    }
    Test-Contract 'Extra wheel rejected instead of silently preferring an unverified package' {
        [IO.File]::WriteAllText((Join-Path $wheelhouse 'unverified-package.whl'), 'Synthetic inert extra wheel.')
        try {
            Expect-Rejected { Run-Install } 'exactly one verified InsightFace wheel'
            Expect ($script:commands.Count -eq 0) 'pip called before wheelhouse validation.'
        } finally { Remove-Item -LiteralPath (Join-Path $wheelhouse 'unverified-package.whl') }
    }
    Test-Contract 'Wrong wheel ABI rejected before pip invocation' {
        $script:manifest.wheels[0].filename = 'insightface-0.7.3-cp312-cp312-win_amd64.whl'
        Save-Manifest
        Expect-Rejected { Run-Install } 'Unexpected wheel package, version, ABI'
        Expect ($script:commands.Count -eq 0) 'pip called before ABI validation.'
    }
    Test-Contract 'NumPy 2 build constraint rejected' {
        [IO.File]::WriteAllText($constraints, 'numpy==2.0.0')
        Expect-Rejected { Run-Install } 'must pin numpy==1.26.4'
    }
} finally {
    if ($null -eq $original) { Remove-Item -LiteralPath 'Env:PIP_CONSTRAINT' -ErrorAction SilentlyContinue }
    else { [Environment]::SetEnvironmentVariable('PIP_CONSTRAINT',$original,'Process') }
}
$failed=@($script:results|Where-Object {-not $_.passed}).Count
[ordered]@{testedAt=[DateTime]::UtcNow.ToString('o');powerShellVersion=$PSVersionTable.PSVersion.ToString();sourceSha256=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'target-python-install.ps1') -Algorithm SHA256).Hash;passed=$script:results.Count-$failed;total=$script:results.Count;scope='Synthetic files, mocked pip; no installations or services; argument arrays checked, no real pip subprocess';tests=@($script:results.ToArray())} |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $fixture 'report.json') -Encoding UTF8
Write-Host "RESULT: $($script:results.Count-$failed)/$($script:results.Count) passed. $fixture/report.json"
if($failed) {exit 1}
