param(
    [string]$TvHost = $env:TV_HOST,
    [switch]$BuildOnly,
    [string]$Python = 'python',
    [string]$HomeSnapshot,
    [string]$ReplaceStock
)
$ErrorActionPreference = 'Stop'
if ($ReplaceStock -and -not $HomeSnapshot) { throw '-ReplaceStock requiere -HomeSnapshot.' }
if ($BuildOnly -and ($HomeSnapshot -or $ReplaceStock)) { throw '-BuildOnly no se combina con -HomeSnapshot.' }
$root = $PSScriptRoot
& (Join-Path $root 'gradlew.bat') '-p' $root ':app:assembleDebug'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path -LiteralPath $apk -PathType Leaf) -or (Get-Item -LiteralPath $apk).Length -eq 0) { throw "El APK compilado no existe o está vacío: $apk" }
if ($BuildOnly) { Write-Output "APK: $apk"; exit 0 }
if (-not $TvHost) { throw 'Define TV_HOST con la IP de tu TV.' }
$client = Join-Path $root '..\tools\tv.py'
$installCode = 1
try {
    & $Python $client '--host' $TvHost '--push' $apk '/data/local/tmp/adlesstv-preview.apk'
    if ($LASTEXITCODE -ne 0) { throw 'Falló la transferencia del APK a la TV.' }
    $result = & $Python $client '--host' $TvHost 'pm install -r /data/local/tmp/adlesstv-preview.apk'
    $installCode = $LASTEXITCODE
} finally {
    try { & $Python $client '--host' $TvHost 'rm -f /data/local/tmp/adlesstv-preview.apk' | Out-Null }
    catch { Write-Warning 'No se pudo borrar el APK temporal de la TV; revisa /data/local/tmp cuando vuelva ADB.' }
}
if ($installCode -ne 0 -or $result -notmatch 'Success') { throw "Instalación fallida: $result" }
$result = & $Python $client '--host' $TvHost 'am start -W -n com.adlesstv.launcher/.MainActivity'
if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch '(?m)^Status: ok\s*$' -or
    ($result -join "`n") -match '(?im)^Error:') { throw "El lanzador no inició: $result" }
if ($HomeSnapshot) {
    $parent = Split-Path -Parent $HomeSnapshot
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $homeTool = Join-Path $root '..\tools\setup.py'
    $homeArgs = @('--host', $TvHost, '--activate', $HomeSnapshot)
    if ($ReplaceStock) { $homeArgs += @('--stock-package', $ReplaceStock) }
    & $Python $homeTool @homeArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Output "Reinicia la TV y verifica con: python tools/setup.py --host $TvHost --verify-reboot $HomeSnapshot"
} else {
    Write-Output 'Vista previa instalada. Para elegir HOME usa -HomeSnapshot <respaldo-nuevo.json>.'
}
