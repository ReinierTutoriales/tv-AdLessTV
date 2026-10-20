#!/usr/bin/env bash
# Build a preview, or explicitly select HOME with a recoverable snapshot.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BUILD_ONLY=0
RELEASE=0
HOME_SNAPSHOT=''
STOCK_PACKAGE=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --build-only) BUILD_ONLY=1; shift ;;
    --release) RELEASE=1; shift ;;
    --make-home|--replace-stock)
      [[ $# -ge 2 && -n "$2" && "$2" != --* ]] || { echo "Falta el valor de $1" >&2; exit 2; }
      if [[ "$1" == --make-home ]]; then HOME_SNAPSHOT="$2"; else STOCK_PACKAGE="$2"; fi
      shift 2 ;;
    *) echo "Opción desconocida: $1" >&2; exit 2 ;;
  esac
done
if [[ -n "$STOCK_PACKAGE" && -z "$HOME_SNAPSHOT" ]]; then
  echo '--replace-stock requiere --make-home con un respaldo nuevo.' >&2; exit 2
fi
if [[ "$BUILD_ONLY" == 1 && -n "$HOME_SNAPSHOT" ]]; then
  echo '--build-only no se combina con --make-home.' >&2; exit 2
fi
if [[ -n "$HOME_SNAPSHOT" && "$RELEASE" != 1 ]]; then
  echo 'Para activar HOME instala un release firmado con --release.' >&2; exit 2
fi
if [[ "$RELEASE" == 1 ]]; then
  [[ -f "$ROOT/keystore.properties" ]] || {
    echo 'Falta launcher/keystore.properties para firmar el release.' >&2; exit 2;
  }
  "$ROOT/gradlew" -p "$ROOT" :app:assembleRelease
  APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
else
  "$ROOT/gradlew" -p "$ROOT" :app:assembleDebug
  APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
fi
[[ -s "$APK" ]] || { echo "No se creó el APK esperado: $APK" >&2; exit 1; }
if [[ "$BUILD_ONLY" == 1 ]]; then printf 'APK: %s\n' "$APK"; exit 0; fi
: "${TV_HOST:?Define TV_HOST con la IP de tu TV}"
PY="${PYTHON:-python3}"
export MSYS_NO_PATHCONV=1
"$PY" "$ROOT/../tools/tv.py" --push "$APK" /data/local/tmp/adlesstv-preview.apk
if result="$("$PY" "$ROOT/../tools/tv.py" 'pm install -r /data/local/tmp/adlesstv-preview.apk')"; then
  install_status=0
else
  install_status=$?
fi
# The APK is staged in /data/local/tmp; keep no extra copy on an 8 GB stick.
"$PY" "$ROOT/../tools/tv.py" 'rm -f /data/local/tmp/adlesstv-preview.apk' >/dev/null || true
if [[ "$install_status" != 0 || "$result" != *Success* ]]; then
  printf 'Instalación fallida: %s\n' "$result" >&2; exit 1
fi
result="$("$PY" "$ROOT/../tools/tv.py" 'am start -W -n com.adlesstv.launcher/.MainActivity')"
if [[ ! "$result" =~ (^|$'\n')Status:\ ok($|$'\n') || "$result" =~ (^|$'\n')Error: ]]; then
  printf 'El lanzador no inició: %s\n' "$result" >&2; exit 1
fi
if [[ -n "$HOME_SNAPSHOT" ]]; then
  mkdir -p "$(dirname -- "$HOME_SNAPSHOT")"
  home_args=(--activate "$HOME_SNAPSHOT")
  if [[ -n "$STOCK_PACKAGE" ]]; then home_args+=(--stock-package "$STOCK_PACKAGE"); fi
  "$PY" "$ROOT/../tools/home.py" "${home_args[@]}"
  printf 'Reinicia la TV y verifica con: tools/home.py --verify-reboot %s\n' "$HOME_SNAPSHOT"
else printf 'Vista previa instalada. Para elegir HOME usa --make-home <respaldo-nuevo.json>.\n'; fi
