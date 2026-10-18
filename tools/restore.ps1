param(
    [Parameter(Mandatory=$true)][string]$Snapshot,
    [string]$TvHost = $env:TV_HOST,
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
$arguments = @((Join-Path $PSScriptRoot 'debloat.py'))
if ($TvHost) { $arguments += @('--host', $TvHost) }
$arguments += @('restore', $Snapshot)
& $Python @arguments
exit $LASTEXITCODE
