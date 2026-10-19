# Windows PowerShell 5.1 y PowerShell 7. Simula salvo que se use -Apply.
param(
    [Parameter(Mandatory=$true)][string]$List,
    [string]$TvHost = $env:TV_HOST,
    [switch]$Apply,
    [string]$Snapshot,
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
$arguments = @((Join-Path $PSScriptRoot 'debloat.py'))
if ($TvHost) { $arguments += @('--host', $TvHost) }
$arguments += @('disable', $List)
if ($Apply) { $arguments += '--apply' }
if ($Snapshot) { $arguments += @('--snapshot', $Snapshot) }
& $Python @arguments
exit $LASTEXITCODE
