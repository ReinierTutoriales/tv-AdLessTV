#!/usr/bin/env bash
# Run both suites even if one fails; all mutations are restricted to a disposable emulator.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == '1' ]] || exit 1
status=0
bash "$ROOT/check-ui.sh" || status=1
if [[ -s "$ROOT/app/build/outputs/apk/debug/app-debug.apk" ]]; then
  adb install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
  python "$ROOT/check-home.py" || status=1
else
  status=1
fi
exit "$status"
