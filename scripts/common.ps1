Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:ProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$script:RuntimeRoot = Join-Path $script:ProjectRoot '.runtime'

function Import-ProjectEnvironment {
    $path = Join-Path $script:ProjectRoot '.env'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw 'Missing .env. Run .\scripts\init-env.ps1 first, then configure the database and origins.'
    }
    foreach ($line in [IO.File]::ReadAllLines($path)) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
        if ($trimmed -notmatch '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { throw 'Invalid .env line; expected NAME=value.' }
        $name = $Matches[1]
        $value = $Matches[2].Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
    if ($env:MODEL_DIR -and -not [IO.Path]::IsPathRooted($env:MODEL_DIR)) {
        $env:MODEL_DIR = [IO.Path]::GetFullPath((Join-Path $script:ProjectRoot $env:MODEL_DIR))
    }
}

function Resolve-ProjectExecutable([string]$Name) {
    if ([string]::IsNullOrWhiteSpace($Name)) { throw 'Executable is not configured.' }
    if ([IO.Path]::IsPathRooted($Name)) {
        if (-not (Test-Path -LiteralPath $Name -PathType Leaf)) { throw "Executable not found: $Name" }
        return [IO.Path]::GetFullPath($Name)
    }
    $local = Join-Path $script:ProjectRoot $Name
    if (Test-Path -LiteralPath $local -PathType Leaf) { return [IO.Path]::GetFullPath($local) }
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $command) { throw "Executable not found: $Name" }
    return $command.Source
}

function Resolve-Java {
    if ($env:JAVA_HOME) { return Resolve-ProjectExecutable (Join-Path $env:JAVA_HOME 'bin/java.exe') }
    return Resolve-ProjectExecutable 'java.exe'
}

function Resolve-Node {
    if ($env:NODE_HOME) { return Resolve-ProjectExecutable (Join-Path $env:NODE_HOME 'node.exe') }
    return Resolve-ProjectExecutable 'node.exe'
}

function Assert-ProjectSecrets {
    try { $key = [Convert]::FromBase64String($env:FACE_ENCRYPTION_KEY) } catch { throw 'FACE_ENCRYPTION_KEY must be Base64 of 32 bytes.' }
    if ($key.Length -ne 32) { throw 'FACE_ENCRYPTION_KEY must contain exactly 32 decoded bytes.' }
    if (-not $env:INTERNAL_API_KEY -or $env:INTERNAL_API_KEY.Length -lt 32) { throw 'INTERNAL_API_KEY must contain at least 32 characters.' }
    if (-not $env:BOOTSTRAP_ADMIN_PASSWORD -or $env:BOOTSTRAP_ADMIN_PASSWORD.Length -lt 12) { throw 'BOOTSTRAP_ADMIN_PASSWORD must contain at least 12 characters.' }
}

function Test-LocalPort([int]$Port) {
    $client = New-Object Net.Sockets.TcpClient
    try {
        $pending = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $pending.AsyncWaitHandle.WaitOne(500)) { return $false }
        $client.EndConnect($pending)
        return $true
    } catch { return $false } finally { $client.Dispose() }
}

function Wait-LocalPort([int]$Port, [int]$Seconds = 45) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-LocalPort $Port) { return }
        Start-Sleep -Milliseconds 500
    }
    throw "Port $Port did not become ready. Inspect .runtime logs."
}

function Quote-ProcessArgument([string]$Value) {
    if ($Value.Contains('"') -or $Value.EndsWith('\')) { throw 'Unsupported process argument: quote or trailing backslash.' }
    return '"' + $Value + '"'
}

function Start-OwnedProcess([string]$Name, [string]$Executable, [string[]]$Arguments, [string]$WorkingDirectory) {
    New-Item -ItemType Directory -Path $script:RuntimeRoot -Force | Out-Null
    $recordPath = Join-Path $script:RuntimeRoot ($Name + '.process.json')
    if (Test-Path -LiteralPath $recordPath) { throw "A process record already exists for $Name. Run stop.ps1 before starting again." }
    $argumentString = (($Arguments | ForEach-Object { Quote-ProcessArgument $_ }) -join ' ')
    $process = Start-Process -FilePath $Executable -ArgumentList $argumentString -WorkingDirectory $WorkingDirectory -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $script:RuntimeRoot ($Name + '.stdout.log')) -RedirectStandardError (Join-Path $script:RuntimeRoot ($Name + '.stderr.log'))
    Start-Sleep -Milliseconds 250
    $process.Refresh()
    if ($process.HasExited) { throw "$Name exited during startup. Inspect .runtime logs." }
    [ordered]@{ Name = $Name; Id = $process.Id; StartTimeUtcTicks = $process.StartTime.ToUniversalTime().Ticks.ToString(); Executable = $process.Path; ProjectRoot = $script:ProjectRoot } |
        ConvertTo-Json | Set-Content -LiteralPath $recordPath -Encoding UTF8
    Write-Host "$Name started (PID $($process.Id))."
}

function Get-OwnedProcess([string]$RecordPath) {
    $record = Get-Content -Raw -LiteralPath $RecordPath | ConvertFrom-Json
    if ($record.ProjectRoot -ne $script:ProjectRoot) { throw "Process record belongs to another project: $RecordPath" }
    $process = Get-Process -Id $record.Id -ErrorAction SilentlyContinue
    if ($null -eq $process) { return $null }
    if ($process.StartTime.ToUniversalTime().Ticks -ne [long]$record.StartTimeUtcTicks -or $process.Path -ne $record.Executable) {
        throw "PID identity changed; refusing to stop PID $($record.Id). Inspect the stale process record manually."
    }
    return $process
}

function New-MySqlOptionsFile {
    foreach ($name in @('DB_USERNAME', 'DB_PASSWORD', 'DB_HOST', 'DB_PORT', 'DB_NAME')) {
        if (-not [Environment]::GetEnvironmentVariable($name)) { throw "$name is required for database backup/restore." }
    }
    if ($env:DB_NAME -notmatch '^[A-Za-z0-9_]+$' -or $env:DB_PORT -notmatch '^\d+$') { throw 'Invalid database name or port.' }
    New-Item -ItemType Directory -Path $script:RuntimeRoot -Force | Out-Null
    $path = Join-Path $script:RuntimeRoot ('mysql-' + [guid]::NewGuid().ToString('N') + '.cnf')
    # Restrict before writing the password. The defaults file is deleted in finally.
    [IO.File]::WriteAllText($path, '')
    $acl = New-Object Security.AccessControl.FileSecurity
    $acl.SetOwner([Security.Principal.WindowsIdentity]::GetCurrent().User)
    $acl.SetAccessRuleProtection($true, $false)
    $rule = New-Object Security.AccessControl.FileSystemAccessRule([Security.Principal.WindowsIdentity]::GetCurrent().User, 'FullControl', 'Allow')
    $acl.AddAccessRule($rule)
    Set-Acl -LiteralPath $path -AclObject $acl
    function Escape-MySqlValue([string]$Value) {
        if ($Value.Contains("`r") -or $Value.Contains("`n")) { throw 'Database connection values must not contain line breaks.' }
        return $Value.Replace('\', '\\').Replace('"', '\"')
    }
    $text = "[client]`nuser=" + '"' + (Escape-MySqlValue $env:DB_USERNAME) + '"' + "`npassword=" + '"' + (Escape-MySqlValue $env:DB_PASSWORD) + '"' + "`nhost=" + '"' + (Escape-MySqlValue $env:DB_HOST) + '"' + "`nport=$($env:DB_PORT)`n"
    [IO.File]::WriteAllText($path, $text, (New-Object Text.UTF8Encoding($false)))
    return $path
}

