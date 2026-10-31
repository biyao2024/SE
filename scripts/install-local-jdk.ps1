# Install an already downloaded official Windows JDK archive without system changes.
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Archive, [Parameter(Mandatory=$true)][string]$ChecksumFile, [Parameter(Mandatory=$true)][string]$SourceUrl)
. (Join-Path $PSScriptRoot 'common.ps1')
$archivePath = (Resolve-Path -LiteralPath $Archive).Path
$checksumPath = (Resolve-Path -LiteralPath $ChecksumFile).Path
$expected = ([IO.File]::ReadAllText($checksumPath).Trim() -split '\s+')[0].ToLowerInvariant()
if ($expected -notmatch '^[a-f0-9]{64}$') { throw 'Invalid official SHA-256 file.' }
$actual = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $expected) { throw 'JDK archive SHA-256 does not match; do not extract this file.' }
$toolchain = [IO.Path]::GetFullPath((Join-Path $script:ProjectRoot '.toolchain'))
$destination = [IO.Path]::GetFullPath((Join-Path $toolchain 'jdk'))
if (Test-Path -LiteralPath $destination) { throw '.toolchain/jdk already exists; refusing to overwrite existing tools.' }
$staging = [IO.Path]::GetFullPath((Join-Path $toolchain ('jdk-extract-' + [guid]::NewGuid().ToString('N'))))
New-Item -ItemType Directory -Path $staging -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($archivePath)
try {
    foreach ($entry in $zip.Entries) {
        $entryPath = [IO.Path]::GetFullPath((Join-Path $staging $entry.FullName))
        if (-not $entryPath.StartsWith($staging + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Archive entry escapes the staging directory.' }
    }
} finally { $zip.Dispose() }
[IO.Compression.ZipFile]::ExtractToDirectory($archivePath, $staging)
$folders = @(Get-ChildItem -LiteralPath $staging -Directory)
if ($folders.Count -ne 1) { throw 'Expected one top-level JDK directory.' }
$source = [IO.Path]::GetFullPath($folders[0].FullName)
if (-not $source.StartsWith($staging + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or -not $destination.StartsWith($toolchain + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected move target.' }
foreach ($binary in @('java.exe', 'javac.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $source ('bin/' + $binary)) -PathType Leaf)) { throw 'The archive is not a complete Windows JDK.' }
}
Move-Item -LiteralPath $source -Destination $destination
# Only remove the now-empty staging directory; never recursively delete tool paths.
Remove-Item -LiteralPath $staging
[ordered]@{ sourceUrl = $SourceUrl; archive = [IO.Path]::GetFileName($archivePath); sha256 = $actual; installedAtUtc = [DateTime]::UtcNow.ToString('o'); destination = '.toolchain/jdk' } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $toolchain 'jdk-installation.json') -Encoding UTF8
Write-Host 'Verified archive SHA-256 and installed .toolchain/jdk. Run target checks to verify runtime version.'
