param(
    [string]$Apk = (Join-Path $PSScriptRoot 'app-debug.apk'),
    [string]$Adb,
    [string]$TvHost = $env:TV_HOST,
    [ValidateRange(1, 65535)][int]$Port = 5555,
    [switch]$MakeHome,
    [switch]$HomeOnly,
    [switch]$PlanOnly,
    [string]$BackupDirectory = (Join-Path $PSScriptRoot 'backups'),
    [string]$Python
)
$ErrorActionPreference = 'Stop'
if ($HomeOnly) { $MakeHome = $true }
if (-not $TvHost) { throw 'Indica -TvHost con la IP del televisor.' }
if (-not $HomeOnly -and -not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "No existe el APK: $Apk" }
if (-not $Adb) {
    $localAdb = Join-Path $PSScriptRoot 'adb.exe'
    $Adb = if (Test-Path -LiteralPath $localAdb) { $localAdb } else { 'adb' }
}
$adbCommand = (Get-Command $Adb -ErrorAction Stop).Source
if (-not $HomeOnly) { $Apk = (Resolve-Path -LiteralPath $Apk).Path }
$tool = Join-Path $PSScriptRoot 'tools\home_setup.py'
if (-not (Test-Path -LiteralPath $tool)) { $tool = Join-Path $PSScriptRoot '..\tools\home_setup.py' }
$pythonArguments = @()
if ($MakeHome -or $PlanOnly) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw 'Falta tools/home_setup.py del paquete completo.' }
    if (-not $Python) {
        if (Get-Command py -ErrorAction SilentlyContinue) { $Python = 'py'; $pythonArguments = @('-3') }
        else { $Python = 'python' }
    }
    $Python = (Get-Command $Python -ErrorAction Stop).Source
    $tool = (Resolve-Path -LiteralPath $tool).Path
    & $Python @pythonArguments -c 'import sys; assert sys.version_info >= (3,11), "Se requiere Python 3.11 o posterior"'
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo ejecutar Python 3.11 o posterior.' }
    New-Item -ItemType Directory -Force -Path $BackupDirectory | Out-Null
    $BackupDirectory = (Resolve-Path -LiteralPath $BackupDirectory).Path
}
$address = if ($TvHost.Contains(':') -and -not $TvHost.StartsWith('[')) { "[$TvHost]" } else { $TvHost }
$serial = "${address}:$Port"
& $adbCommand connect $serial
if ($LASTEXITCODE -ne 0) { throw 'No se pudo conectar ADB.' }
$state = & $adbCommand -s $serial get-state
if ($LASTEXITCODE -ne 0 -or ($state -join "`n").Trim() -ne 'device') {
    throw 'Acepta la autorización ADB en la TV y vuelve a ejecutar.'
}
if (-not $HomeOnly) {
$result = & $adbCommand -s $serial install -r $Apk
if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch '(?m)^Success\s*$') {
    throw "No se instaló el APK; HOME sigue intacto. Conserva el error de firma si aparece: $result"
}
}
$result = & $adbCommand -s $serial shell am start -W -n 'com.adlesstv.launcher/.MainActivity'
if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch '(?m)^Status: ok\s*$' -or
    ($result -join "`n") -match '(?im)^Error:') { throw "El launcher no abrió; HOME sigue intacto: $result" }
if (-not ($MakeHome -or $PlanOnly)) {
    Write-Output 'Vista previa abierta. Para configurar HOME ejecuta este instalador con -MakeHome.'
    exit 0
}
$id = [guid]::NewGuid().ToString('N')
$plan = Join-Path $BackupDirectory "home-plan-$id.json"
$snapshot = Join-Path $BackupDirectory "home-$id.json"
$connection = @('--adb', $adbCommand, '--host', $TvHost, '--port', "$Port")
& $Python @pythonArguments $tool @connection plan --output $plan
if ($LASTEXITCODE -ne 0) { throw 'No se pudo preparar el reemplazo. HOME y paquetes siguen intactos.' }
if ($PlanOnly) { Write-Output "Solo plan: $plan"; exit 0 }
# Write runnable recovery commands; quote each argument as a PowerShell literal.
function Save-Helper([string]$Path, [string[]]$Arguments) {
    $quoted = @($Python) + $pythonArguments + @($tool) + $connection + $Arguments |
        ForEach-Object { "'" + $_.Replace("'", "''") + "'" }
    $line = '& ' + ($quoted -join ' ') + "`r`nif (`$LASTEXITCODE -ne 0) { exit `$LASTEXITCODE }`r`n"
    [System.IO.File]::WriteAllText($Path, $line, (New-Object System.Text.UTF8Encoding($true)))
}
$verifyScript = Join-Path $BackupDirectory "Verificar-HOME-$id.ps1"
$restoreScript = Join-Path $BackupDirectory "Restaurar-HOME-$id.ps1"
Save-Helper $verifyScript @('verify', $snapshot)
Save-Helper $restoreScript @('restore', $snapshot)
& $Python @pythonArguments $tool @connection apply --plan $plan --snapshot $snapshot
if ($LASTEXITCODE -ne 0) { throw "Reemplazo incompleto. Conserva $snapshot y ejecuta $restoreScript cuando vuelva ADB." }
Write-Output "Respaldo HOME: $snapshot"
Write-Output 'Reinicia la TV; verifica antes de abrir otra app. Para restaurar usa restore con este mismo respaldo.'
Write-Output "Despues de reiniciar ejecuta: $verifyScript"
Write-Output "Para volver al estado anterior ejecuta: $restoreScript"
