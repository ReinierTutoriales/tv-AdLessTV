param(
    [string]$TvHost = $env:TV_HOST,
    [switch]$BuildOnly,
    [switch]$Release,
    [string]$Python = 'python',
    [string]$HomeSnapshot,
    [string]$ReplaceStock
)
$ErrorActionPreference = 'Stop'
if ($ReplaceStock -and -not $HomeSnapshot) { throw '-ReplaceStock requiere -HomeSnapshot.' }
if ($BuildOnly -and $HomeSnapshot) { throw '-BuildOnly no se combina con -HomeSnapshot.' }
if ($HomeSnapshot -and -not $Release) { throw 'Para activar HOME instala un release firmado con -Release.' }
if ($HomeSnapshot -and (Test-Path -LiteralPath $HomeSnapshot)) {
    throw 'El respaldo HOME ya existe; usa una ruta nueva antes de instalar.'
}
$root = $PSScriptRoot
if ($Release -and -not (Test-Path -LiteralPath (Join-Path $root 'keystore.properties') -PathType Leaf)) {
    throw 'Falta launcher/keystore.properties para firmar el release.'
}
$variant = if ($Release) { ':app:assembleRelease' } else { ':app:assembleDebug' }
& (Join-Path $root 'gradlew.bat') '-p' $root $variant
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$apk = if ($Release) { Join-Path $root 'app\build\outputs\apk\release\app-release.apk' }
       else { Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk' }
if (-not (Test-Path -LiteralPath $apk -PathType Leaf) -or (Get-Item -LiteralPath $apk).Length -le 0) {
    throw "No se creó el APK esperado: $apk"
}
if ($BuildOnly) { Write-Output "APK: $apk"; exit 0 }
if (-not $TvHost) { throw 'Define TV_HOST con la IP de tu TV.' }
$client = Join-Path $root '..\tools\tv.py'
& $Python $client '--host' $TvHost '--push' $apk '/data/local/tmp/adlesstv-preview.apk'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$installCode = 1
try {
    $result = & $Python $client '--host' $TvHost 'pm install -r /data/local/tmp/adlesstv-preview.apk'
    $installCode = $LASTEXITCODE
} finally {
    try { & $Python $client '--host' $TvHost 'rm -f /data/local/tmp/adlesstv-preview.apk' | Out-Null }
    catch { Write-Warning 'No se pudo borrar el APK temporal de la TV; revisa /data/local/tmp cuando vuelva ADB.' }
}
if ($installCode -ne 0 -or ($result -join "`n") -notmatch '(?m)^Success\s*$' ) { throw "Instalación fallida: $result" }
$result = & $Python $client '--host' $TvHost 'am start -W -n com.adlesstv.launcher/.MainActivity'
if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch '(?m)^Status: ok\s*$' -or
    ($result -join "`n") -match '(?im)^Error:') { throw "El lanzador no inició: $result" }
if ($HomeSnapshot) {
    $parent = Split-Path -Parent $HomeSnapshot
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $homeTool = Join-Path $root '..\tools\home.py'
    $homeArgs = @('--host', $TvHost, '--activate', $HomeSnapshot)
    if ($ReplaceStock) { $homeArgs += @('--stock-package', $ReplaceStock) }
    & $Python $homeTool @homeArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Output "Reinicia la TV y verifica con: tools/home.py --verify-reboot $HomeSnapshot"
} else {
    Write-Output 'Vista previa instalada. Para elegir HOME usa -HomeSnapshot <respaldo-nuevo.json>.'
}
