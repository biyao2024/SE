[CmdletBinding()]
param([switch]$BusinessOnly, [switch]$Build, [switch]$ToolsOnly)
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'target-env.ps1')
# Tool/dependency validation does not need credentials or an existing .env.
if (Test-Path -LiteralPath (Join-Path $script:ProjectRoot '.env')) { Import-ProjectEnvironment }
Set-TargetEnvironment
Assert-TargetEnvironment -BusinessOnly:$BusinessOnly -Build:$Build -ToolsOnly:$ToolsOnly
