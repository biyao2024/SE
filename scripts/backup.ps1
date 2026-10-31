[CmdletBinding()]
param([switch]$PruneExpired)
. (Join-Path $PSScriptRoot 'common.ps1')
Import-ProjectEnvironment
$dump = Resolve-ProjectExecutable $env:MYSQLDUMP_EXE
$backupRoot = [IO.Path]::GetFullPath((Join-Path $script:ProjectRoot 'backups'))
if ($backupRoot -ne [IO.Path]::Combine($script:ProjectRoot, 'backups')) { throw 'Invalid backup directory.' }
New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
if ((Get-Item -LiteralPath $backupRoot).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Backup directory must not be a junction or symbolic link.' }
$destination = Join-Path $backupRoot ('face-attendance-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.sql')
$options = $null
try {
    $options = New-MySqlOptionsFile
    & $dump "--defaults-extra-file=$options" --single-transaction --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 "--result-file=$destination" $env:DB_NAME
    if ($LASTEXITCODE -ne 0) {
        if (Test-Path -LiteralPath $destination) { Remove-Item -LiteralPath $destination }
        throw 'Backup failed; incomplete output removed.'
    }
    if ((Get-Item -LiteralPath $destination).Length -eq 0) { throw 'Backup is empty.' }
    Write-Host "Database backup created: $destination"
    Write-Host 'Keep a separate protected copy of FACE_ENCRYPTION_KEY. This backup does not contain .env.'
    if ($PruneExpired) {
        foreach ($file in Get-ChildItem -LiteralPath $backupRoot -File) {
            if ($file.Name -cmatch '^face-attendance-\d{8}-\d{6}-\d{3}\.sql$' -and $file.LastWriteTimeUtc -lt [DateTime]::UtcNow.AddDays(-7)) {
                if ($file.DirectoryName -ne $backupRoot -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Unsafe backup pruning target.' }
                Remove-Item -LiteralPath $file.FullName
                Write-Host "Expired backup removed: $($file.Name)"
            }
        }
    }
} finally { if ($options -and (Test-Path -LiteralPath $options)) { Remove-Item -LiteralPath $options } }

