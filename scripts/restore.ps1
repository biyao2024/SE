[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$BackupFile, [switch]$ConfirmOverwrite)
. (Join-Path $PSScriptRoot 'common.ps1')
if (-not $ConfirmOverwrite) { throw 'Restore replaces data in the configured database. Stop services, make a fresh backup, and rerun with -ConfirmOverwrite.' }
Import-ProjectEnvironment
$mysql = Resolve-ProjectExecutable $env:MYSQL_EXE
$source = [IO.Path]::GetFullPath($BackupFile)
if (-not (Test-Path -LiteralPath $source -PathType Leaf) -or [IO.Path]::GetExtension($source) -ne '.sql') { throw 'Provide an existing trusted .sql backup file.' }
if (Test-LocalPort 8080) { throw 'Stop the backend before restoring the database.' }
$options = $null
$process = $null
try {
    $options = New-MySqlOptionsFile
    # Standard-input redirection keeps passwords and SQL content out of command-line arguments.
    $process = Start-Process -FilePath $mysql -ArgumentList @((Quote-ProcessArgument "--defaults-extra-file=$options"), '--default-character-set=utf8mb4', (Quote-ProcessArgument $env:DB_NAME)) -RedirectStandardInput $source -NoNewWindow -PassThru -Wait
    if ($process.ExitCode -ne 0) { throw 'Restore failed; inspect the database before restarting.' }
    Write-Host 'Restore completed. Use the original FACE_ENCRYPTION_KEY and verify accounts, reports, and face templates before reopening access.'
} finally { if ($options -and (Test-Path -LiteralPath $options)) { Remove-Item -LiteralPath $options } }

