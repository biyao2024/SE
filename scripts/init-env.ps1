[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'common.ps1')
$target = Join-Path $script:ProjectRoot '.env'
if (Test-Path -LiteralPath $target) { throw '.env already exists; refusing to replace encryption keys or credentials.' }
function New-RandomSecret([int]$Count) {
    $bytes = New-Object byte[] $Count
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes)
}
$content = [IO.File]::ReadAllText((Join-Path $script:ProjectRoot '.env.example'))
$content = $content.Replace('FACE_ENCRYPTION_KEY=GENERATE_WITH_INIT_ENV', ('FACE_ENCRYPTION_KEY=' + (New-RandomSecret 32)))
$content = $content.Replace('INTERNAL_API_KEY=GENERATE_WITH_INIT_ENV', ('INTERNAL_API_KEY=' + (New-RandomSecret 48)))
$content = $content.Replace('BOOTSTRAP_ADMIN_PASSWORD=GENERATE_WITH_INIT_ENV', ('BOOTSTRAP_ADMIN_PASSWORD=Aa1!' + (New-RandomSecret 24)))
[IO.File]::WriteAllText($target, $content, (New-Object Text.UTF8Encoding($false)))
$acl = New-Object Security.AccessControl.FileSecurity
$acl.SetOwner([Security.Principal.WindowsIdentity]::GetCurrent().User)
$acl.SetAccessRuleProtection($true, $false)
$acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule([Security.Principal.WindowsIdentity]::GetCurrent().User, 'FullControl', 'Allow')))
Set-Acl -LiteralPath $target -AclObject $acl
Write-Host 'Created .env with independent random secrets and current-user-only permissions.'
Write-Host 'Edit DB_PASSWORD, APP_ALLOWED_ORIGIN and NGINX_EXE. Read the bootstrap password directly from .env; it is not printed.'

