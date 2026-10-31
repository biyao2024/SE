[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$LanIp)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'start.ps1') -Dev -TargetEnvironment -LanHttpsIp $LanIp
