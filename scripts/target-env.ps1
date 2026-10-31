# Explicit target profile. Dot-source common.ps1 before this file.
# Paths are project-relative; no workstation/user names are stored here.
function Get-TargetEnvironment {
    return (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'target-environment.json') -Raw -Encoding UTF8 | ConvertFrom-Json)
}

function Set-TargetEnvironment {
    $target = Get-TargetEnvironment
    $jdk = Join-Path $script:ProjectRoot $target.paths.javaHome
    $node = Join-Path $script:ProjectRoot $target.paths.nodeHome
    $pnpm = Join-Path $script:ProjectRoot $target.paths.pnpm
    # Bundled tools take precedence over obsolete tool paths imported from .env.
    # If absent, existing user configuration is permitted only after exact checks.
    if (Test-Path -LiteralPath (Join-Path $jdk 'bin/java.exe') -PathType Leaf) { $env:JAVA_HOME = $jdk }
    if (Test-Path -LiteralPath (Join-Path $node 'node.exe') -PathType Leaf) { $env:NODE_HOME = $node }
    if (Test-Path -LiteralPath $pnpm -PathType Leaf) { $env:PNPM_EXE = $pnpm }
    if (-not $env:PNPM_EXE) { $env:PNPM_EXE = 'pnpm.cmd' }
    $env:PYTHON_EXE = Join-Path $script:ProjectRoot $target.paths.python
    $env:MODEL_DIR = Join-Path $script:ProjectRoot $target.paths.models
    $env:INSIGHTFACE_MODEL = 'buffalo_l'
    if ($env:JAVA_HOME) { $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH }
    if ($env:NODE_HOME) { $env:PATH = $env:NODE_HOME + ';' + $env:PATH }
}

function Get-TargetNativeOutput([string]$Executable, [string[]]$Arguments) {
    # Java prints version information to stderr; capture it without the Windows
    # PowerShell 5.1 NativeCommandError behavior. Only use this for native .exe.
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $Executable
    $info.Arguments = (($Arguments | ForEach-Object { Quote-ProcessArgument $_ }) -join ' ')
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    try {
        $null = $process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $output = $stdout.Result + $stderr.Result
        if ($process.ExitCode -ne 0) { throw "Tool check failed ($($process.ExitCode)): $Executable`n$output" }
        return $output.Trim()
    } finally { $process.Dispose() }
}

function Invoke-TargetPnpm([string[]]$Commands) {
    $pnpm = Resolve-ProjectExecutable $env:PNPM_EXE
    if ([IO.Path]::GetExtension($pnpm) -in @('.cjs', '.js', '.mjs')) {
        $node = Resolve-Node
        & $node $pnpm @Commands
    } else { & $pnpm @Commands }
    if ($LASTEXITCODE -ne 0) { throw 'Target pnpm command failed.' }
}

function Assert-TargetEnvironment([switch]$BusinessOnly, [switch]$Build, [switch]$ToolsOnly) {
    $target = Get-TargetEnvironment
    if (-not [Environment]::Is64BitOperatingSystem -or $env:OS -ne 'Windows_NT') { throw 'Target profile requires Windows x64.' }
    $java = Resolve-Java
    $javaVersion = Get-TargetNativeOutput $java @('-XshowSettings:properties', '-version')
    if ($javaVersion -notmatch 'java\.specification\.version\s*=\s*21\s' -or $javaVersion -notmatch 'sun\.arch\.data\.model\s*=\s*64\s' -or $javaVersion -notmatch 'os\.arch\s*=\s*(amd64|x86_64)\s') {
        throw 'Target profile requires JDK 21 x64. Set JAVA_HOME or install .toolchain/jdk.'
    }
    $javac = Join-Path (Split-Path $java -Parent) 'javac.exe'
    if (-not (Test-Path -LiteralPath $javac -PathType Leaf)) { throw 'Use a full JDK 21 (javac.exe missing).' }
    $compilerVersion = Get-TargetNativeOutput $javac @('-version')
    if ($compilerVersion -notmatch '^javac 21(\.|\s|$)') { throw 'javac must also be version 21.' }
    Write-Host "[OK] JDK 21 x64: $java"

    $node = Resolve-Node
    $nodeInfo = Get-TargetNativeOutput $node @('-p', 'JSON.stringify({version:process.versions.node,arch:process.arch,platform:process.platform})') | ConvertFrom-Json
    if ($nodeInfo.version -ne $target.nodeVersion -or $nodeInfo.arch -ne 'x64' -or $nodeInfo.platform -ne 'win32') {
        throw "Target requires Node $($target.nodeVersion) win32/x64; found $($nodeInfo.version)/$($nodeInfo.platform)/$($nodeInfo.arch)."
    }
    Write-Host "[OK] Node $($target.nodeVersion) x64: $node"
    $pnpmVersion = ((Invoke-TargetPnpm @('--version')) | Out-String).Trim()
    if ($pnpmVersion -ne $target.pnpmVersion) { throw "Target requires pnpm $($target.pnpmVersion); found $pnpmVersion." }
    Write-Host "[OK] pnpm $pnpmVersion"
    if ($Build) {
        $maven = Resolve-ProjectExecutable $env:MAVEN_EXE
        $mavenVersion = (& $maven --version | Out-String)
        if ($LASTEXITCODE -ne 0 -or $mavenVersion -notmatch 'Apache Maven 3\.9\.') { throw 'Target source build requires Maven 3.9.x.' }
        Write-Host '[OK] Maven 3.9.x (source build only)'
    }
    if (-not $ToolsOnly) {
        if (-not $Build -and -not (Test-Path -LiteralPath (Join-Path $script:ProjectRoot 'backend/target/face-attendance-1.0.0.jar') -PathType Leaf)) { throw 'Missing backend JAR; build it or obtain the matching artifact.' }
        $null = Get-TargetNativeOutput $node @((Join-Path $PSScriptRoot 'check-target-frontend.cjs'))
        Write-Host '[OK] Installed frontend dependencies match the target manifest.'
    }
    if (-not $BusinessOnly) {
        $python = Resolve-ProjectExecutable $env:PYTHON_EXE
        $pythonArguments = @((Join-Path $PSScriptRoot 'check-target-python.py'))
        if ($ToolsOnly) { $pythonArguments += '--tools-only' }
        $result = Get-TargetNativeOutput $python $pythonArguments
        Write-Host $result
    }
    Write-Host '[OK] Target environment checks passed. Camera, enrollment and calibrated accuracy require real acceptance tests.'
}
