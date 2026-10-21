#!/usr/bin/env bash
# Run both suites even if one fails; all mutations are restricted to a disposable emulator.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == '1' ]] || exit 1
diagnostics() {
  local result=$?
  if [[ "$result" -ne 0 ]]; then
    local output="$ROOT/../ui-results/emulator-diagnostics"
    mkdir -p "$output"
    adb shell getprop > "$output/properties.txt" 2>&1 || true
    adb shell df -k /data /sdcard > "$output/storage.txt" 2>&1 || true
    adb logcat -d -t 3000 > "$output/logcat.txt" 2>&1 || true
    if [[ -n "${ANDROID_HOME:-}" ]]; then
      local image="$ANDROID_HOME/system-images/android-37.0/google_apis/x86_64/source.properties"
      if [[ -f "$image" ]]; then cp "$image" "$output/system-image.properties"; fi
    fi
  fi
}
trap diagnostics EXIT

# Boot-completed and package/input IPC can succeed while settings is still
# starting (or has crashed). The experimental API 37 image also intermittently
# loses its emulated /sdcard mount; test evidence uses ADB's /data/local/tmp.
readiness="$ROOT/../ui-results/emulator-readiness.txt"
mkdir -p "$(dirname "$readiness")"
failure() {
  printf '%s %s\n' "$(date -u +%T)" "$1" >> "$readiness"
  return 1
}
ready() {
  local response
  response="$(adb shell pm path android 2>&1)" || failure "package: $response" || return 1
  [[ "$response" == *package:* ]] || failure "package: $response" || return 1
  response="$(adb shell service check input 2>&1)" || failure "input: $response" || return 1
  [[ "$response" == *': found'* ]] || failure "input: $response" || return 1
  response="$(adb shell settings get global device_provisioned 2>&1)" || failure "settings: $response" || return 1
  response="${response//$'\r'/}"
  [[ "$response" =~ ^(0|1|null)$ ]] || failure "settings: $response" || return 1
  response="$(adb shell 'touch /data/local/tmp/.adlesstv-ready && rm /data/local/tmp/.adlesstv-ready' 2>&1)" || failure "storage: $response" || return 1
}
api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
required=5
[[ "$api" == 37 ]] && required=12
stable=0
for _ in $(seq 1 90); do
  if ready; then
    stable=$((stable + 1)); [[ "$stable" -ge "$required" ]] && break
  else
    stable=0
  fi
  sleep 2
done
[[ "$stable" -ge "$required" ]] || {
  echo 'El emulador no estabilizó package, input, settings y /data/local/tmp; se guardaron diagnósticos.' >&2
  exit 1
}
status=0
bash "$ROOT/check-ui.sh" || status=1
if [[ -s "$ROOT/app/build/outputs/apk/debug/app-debug.apk" ]]; then
  adb install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
  python "$ROOT/check-home.py" || status=1
else
  status=1
fi
exit "$status"
