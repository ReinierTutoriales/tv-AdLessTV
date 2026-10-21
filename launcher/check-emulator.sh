#!/usr/bin/env bash
# Run both suites even if one fails; all mutations are restricted to a disposable emulator.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == '1' ]] || exit 1
# "Boot completed" can precede a stable system_server on new images: require the
# package and input services to answer several times in a row before testing.
stable=0
for _ in $(seq 1 90); do
  if adb shell pm path android 2>/dev/null | grep -q '^package:' && adb shell service check input 2>/dev/null | grep -q ': found'; then
    stable=$((stable + 1)); [[ "$stable" -ge 5 ]] && break
  else
    stable=0
  fi
  sleep 2
done
[[ "$stable" -ge 5 ]] || { echo 'El emulador no estabilizó sus servicios del sistema.' >&2; exit 1; }
status=0
bash "$ROOT/check-ui.sh" || status=1
if [[ -s "$ROOT/app/build/outputs/apk/debug/app-debug.apk" ]]; then
  adb install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
  python "$ROOT/check-home.py" || status=1
else
  status=1
fi
exit "$status"
