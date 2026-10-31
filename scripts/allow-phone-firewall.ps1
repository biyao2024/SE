[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$LanIp)
$ErrorActionPreference = 'Stop'
$parsedIp = $null
if (-not [Net.IPAddress]::TryParse($LanIp, [ref]$parsedIp) -or $parsedIp.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { throw 'LanIp must be an IPv4 address.' }
$LanIp = $parsedIp.ToString()
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Open Windows PowerShell as administrator, then run this script.'
}
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$ruleName = 'FaceAttendance2-LAN-HTTPS-5173'
$program = Join-Path $projectRoot '.toolchain/node/node.exe'
if (-not (Test-Path -LiteralPath $program -PathType Leaf)) { throw 'Bundled Node executable is missing.' }
if (Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue) {
    throw 'The named rule already exists. Inspect it in Windows Firewall before changing or recreating it.'
}
New-NetFirewallRule -Name $ruleName -DisplayName "Face Attendance 2 LAN HTTPS ($LanIp)" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 5173 -LocalAddress $LanIp -RemoteAddress LocalSubnet -Program $program -Profile Any | Select-Object Name,Enabled,Action
Write-Host "Allowed only the configured Node program at ${LanIp}:5173 from the local subnet."
