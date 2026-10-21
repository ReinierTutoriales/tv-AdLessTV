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
# Preflight before touching the TV: a clear error beats a half-finished install.
$minSdk = 23
$gradle = Join-Path $root 'app\build.gradle'
if (Test-Path -LiteralPath $gradle) {
    $match = Select-String -LiteralPath $gradle -Pattern '^\s*minSdk\s+(\d+)' | Select-Object -First 1
    if ($match) { $minSdk = [int]$match.Matches[0].Groups[1].Value }
}
$tvSdkText = (& $Python $client '--host' $TvHost 'getprop ro.build.version.sdk' | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $tvSdkText -notmatch '^\d+$') { throw "No se pudo leer la versión de Android de la TV: $tvSdkText" }
if ([int]$tvSdkText -lt $minSdk) { throw "La TV tiene API $tvSdkText y el lanzador requiere API $minSdk o superior." }
$features = (& $Python $client '--host' $TvHost 'pm list features' | Out-String)
if ($features -notmatch 'android\.software\.leanback') { throw 'El dispositivo no declara android.software.leanback; el lanzador es solo para Android TV.' }
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
if ($installCode -ne 0 -or $result -notmatch 'Success') {
    if ($result -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures') {
        throw "Instalación fallida: $result`nLa versión instalada está firmada con otra clave. No se desinstala automáticamente: si AdLessTV es HOME, restaura primero el HOME anterior con tools/home.py --restore <respaldo>."
    }
    throw "Instalación fallida: $result"
}
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
