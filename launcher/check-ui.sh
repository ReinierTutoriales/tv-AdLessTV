#!/usr/bin/env bash
# Only for a disposable Android emulator. Never changes display settings on a physical TV.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
RESULTS="${UI_RESULTS:-$ROOT/../ui-results}"
if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != '1' ]]; then
  echo 'Las pruebas de tamaño y fuente requieren un emulador Android.' >&2
  exit 1
fi
mkdir -p "$RESULTS"
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell settings put global device_provisioned 1
adb shell settings put secure user_setup_complete 1
finish() {
  adb logcat -d -s AndroidRuntime > "$RESULTS/android-runtime.txt" || true
  adb shell wm size reset || true
  adb shell wm density reset || true
  adb shell settings put system font_scale 1.0 || true
}
trap finish EXIT
run_case() {
  local label="$1" resolution="$2" font_scale="$3" status=0
  local output="$RESULTS/$label"
  mkdir -p "$output"
  adb shell wm size "$resolution"
  adb shell wm density 160
  adb shell settings put system font_scale "$font_scale"
  adb shell rm -rf /data/local/tmp/AdLessTV-ui
  adb shell dumpsys gfxinfo com.adlesstv.launcher reset > /dev/null || true
  "$ROOT/gradlew" -p "$ROOT" --no-daemon :app:connectedDebugAndroidTest || status=$?
  adb pull /data/local/tmp/AdLessTV-ui/. "$output/" || status=1
  if [[ "$status" -ne 0 ]]; then return "$status"; fi
  adb install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
  adb shell am force-stop com.adlesstv.launcher
  adb shell am start -W -n com.adlesstv.launcher/.MainActivity > "$output/cold-start.txt"
  adb shell dumpsys gfxinfo com.adlesstv.launcher framestats > "$output/frames-after-start.txt"
  adb shell dumpsys meminfo com.adlesstv.launcher > "$output/memory-after-start.txt"
}
run_case 960x540 960x540 1.0
run_case 720x480-large-text 720x480 1.3
