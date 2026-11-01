#!/usr/bin/env bash
# Helpers for the checks in docs/PROOF_CHECKLIST.md. Needs one phone or emulator on adb.
# Each command changes only what its name says. Use a phone whose Aurora data can be lost.
set -euo pipefail
app=app.aurora

case "${1:-}" in
  install)
    ./gradlew :app:installPhone
    ;;
  opens) # opens <package> [count]: opens a blocked app repeatedly. Read the timings in Settings, Shield diagnostics.
    pkg=${2:?package name of a blocked app}
    for _ in $(seq "${3:-20}"); do
      adb shell input keyevent KEYCODE_HOME
      sleep 1
      adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null
      sleep 2
    done
    ;;
  force-stop)
    adb shell am force-stop "$app"
    echo "Aurora was force-stopped. Open a blocked app and note whether the block appears."
    ;;
  reboot)
    adb reboot
    adb wait-for-device
    echo "Phone restarted. Unlock it, then open a blocked app and note whether the block appears."
    ;;
  battery-saver) # battery-saver on|off
    adb shell settings put global low_power "$([ "${2:-on}" = on ] && echo 1 || echo 0)"
    ;;
  font) # font <scale>: 2.0 for the 200% pass, 1.0 to restore
    adb shell settings put system font_scale "${2:-1.0}"
    ;;
  shot) # shot <name>: saves a store screenshot in store-screenshots/
    mkdir -p store-screenshots
    adb exec-out screencap -p > "store-screenshots/${2:?name}.png"
    ;;
  profile)
    ./gradlew :benchmark:connectedPhoneAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.aurora.benchmark.StartupProfile
    found=$(find benchmark/build -name '*baseline-prof.txt' | head -1)
    [ -n "$found" ] || { echo "No profile was written."; exit 1; }
    cp "$found" app/src/main/baseline-prof.txt
    echo "Updated app/src/main/baseline-prof.txt from $found"
    ;;
  speed)
    ./gradlew :benchmark:connectedPhoneAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.aurora.benchmark.StartupBenchmark
    python3 scripts/check-startup.py
    ;;
  *)
    echo "Usage: $0 install | opens <package> [count] | force-stop | reboot | battery-saver on|off | font <scale> | shot <name> | profile | speed"
    exit 2
    ;;
esac
