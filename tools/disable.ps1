# Windows PowerShell 5.1 y PowerShell 7. Simula salvo que se use -Apply.
param(
    [string]$List,
    [string]$Profile,
    [string]$TvHost = $env:TV_HOST,
    [switch]$Apply,
    [string]$Snapshot,
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
if ([bool]$List -eq [bool]$Profile) { throw 'Indica -List o -Profile, uno solo.' }
$arguments = @((Join-Path $PSScriptRoot 'debloat.py'))
if ($TvHost) { $arguments += @('--host', $TvHost) }
$arguments += 'disable'
if ($List) { $arguments += $List } else { $arguments += @('--profile', $Profile) }
if ($Apply) { $arguments += '--apply' }
if ($Snapshot) { $arguments += @('--snapshot', $Snapshot) }
& $Python @arguments
exit $LASTEXITCODE
