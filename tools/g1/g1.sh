#!/usr/bin/env bash
# G1 device harness, host half. Drives a phone over adb and records every open it causes, including
# misses and timeouts, so the statistics never count only the successes. The device half is
# app/src/debug/java/app/aurora/spike/G1.kt. Method and definitions: docs/ENFORCEMENT_FEASIBILITY.md
# section 10. Needs only adb and a POSIX userland. Select a phone with ANDROID_SERIAL when several
# are connected.
#
#   g1.sh device                        record the phone and build under test
#   g1.sh launches <pkg> [n] [warm|cold] scripted opens of a protected app (default 50, warm)
#   g1.sh rapid <pkg> <other-pkg> [n]   switch quickly between a protected and an unprotected app
#   g1.sh manual <label> [n]            guided opens you do by hand (recents, notification, split-screen...)
#   g1.sh kill                          kill aurora's process, time the service restart
#   g1.sh revoke                        guided: turn the service off and on in Settings
#   g1.sh forcestop                     force-stop aurora and check whether Android disabled the service
#   g1.sh reboot                        reboot and time the service's return (needs G1_ALLOW_REBOOT=1)
#   g1.sh note <text>                   add an observation to the record
#   g1.sh report                        statistics over everything recorded for this phone
set -euo pipefail

APP=app.aurora
SERVICE=app.aurora.spike.SpikeShieldService
CSV=/sdcard/Android/data/$APP/files/g1.csv
SETTLE=${G1_SETTLE:-3.5}   # the 2 s on-device timeout starts after the window event, which can trail am start by
                           # ~0.5 s on a slow device; 2.5 s once lost a timeout row (section 10.6)
GAP=${G1_GAP:-1.5}         # rest on the home screen between opens
RAPID=${G1_RAPID:-0.4}    # rapid: seconds between switches. Below the time a window takes to appear, opens
                           # are superseded before they show and count as misses (section 10.6)

die() { echo "g1: $*" >&2; exit 1; }
dev() { adb shell "$@" | tr -d '\r'; }
uptime_s() { dev "cut -d' ' -f1 /proc/uptime"; }   # CLOCK_BOOTTIME, the clock of elapsedRealtime
rows() { dev "cat $CSV 2>/dev/null" | wc -l | tr -d ' '; }
new_rows() { dev "cat $CSV 2>/dev/null" | tail -n +"$(($1 + 1))"; }
shield_in_front() { case $(dev "dumpsys window | grep mCurrentFocus") in *SpikeShieldActivity*) echo 1 ;; *) echo 0 ;; esac; }
event() { echo "$(date -u +%FT%TZ),$*" >> "$OUT/events.csv"; echo "  event: $*"; }

serial=$(adb get-serialno) || die "no device. Connect one phone, or set ANDROID_SERIAL."
OUT=${G1_OUT:-g1-results}/$serial
mkdir -p "$OUT"
RESULTS=$OUT/launches.csv
[ -f "$RESULTS" ] || echo "scenario,i,outcome,detect_ms,present_ms,exposure_ms,shield_in_front,trial" > "$RESULTS"
[ -f "$OUT/events.csv" ] || echo "time,kind,detail" > "$OUT/events.csv"

need_service() {
  case $(dev "settings get secure enabled_accessibility_services") in *"$SERVICE"*) ;; *)
    die "the 'aurora spike shield' service is off. Turn it on yourself (docs/ENFORCEMENT_FEASIBILITY.md section 10), then retry." ;; esac
}

# Turns the device rows written since <before> into one result row per expected open.
# A missing row is a miss; rows beyond <expected> are extra Shields (kept, but not counted as opens).
record() { # scenario first-index before in-front pkg(empty = any) expected
  new_rows "$3" | awk -F, -v s="$1" -v i0="$2" -v front="$4" -v pkg="$5" -v want="${6:-1}" '
    $1 == "trial" && (pkg == "" || $3 == pkg) {
      k++; out = (k <= want) ? $8 : "extra"
      if ($8 == "ok") printf "%s,%d,%s,%.1f,%.1f,%.1f,%s,%s\n", s, i0+k-1, out, $5-$4, $7-$5, $7-$4, front, $2
      else            printf "%s,%d,%s,,,,%s,%s\n", s, i0+k-1, out, front, $2 }
    END { for (j = k+1; j <= want; j++) printf "%s,%d,missed,,,,%s,\n", s, i0+j-1, front }' |
    tee -a "$RESULTS" | sed 's/^/  /'
}

launch_component() {
  local c
  c=$(dev "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $1" | tail -1)
  case $c in */*) echo "$c" ;; *) die "$1 has no launcher activity" ;; esac
}

wait_connected() { # before timeout-s; prints the elapsedRealtime (s) of the first connect after <before>
  local t=0 line
  while [ "$t" -lt "$(($2 * 2))" ]; do
    line=$(new_rows "$1" | grep -m1 '^svc,connected' || true)
    [ -n "$line" ] && { echo "$line" | awk -F, '{printf "%.2f", $3/1000}'; return; }
    sleep 0.5; t=$((t + 1))
  done
  echo none
}

pct() { # sorted numbers on stdin; 999999 stands for a timeout or miss and prints as ">2000"
  awk 'function f(x) { return x >= 999999 ? ">2000" : sprintf("%.1f", x) }
       function q(p,  k) { k = int(p * NR); if (k < p * NR) k++; return f(v[k < 1 ? 1 : k]) }
       { v[NR] = $1 }
       END { if (NR) printf "n=%d median=%s p90=%s p95=%s max=%s", NR, q(.5), q(.9), q(.95), f(v[NR]); else printf "n=0" }'
}

report() {
  local s
  for s in $(awk -F, 'NR > 1 { print $1 }' "$RESULTS" | sort -u) ALL; do
    awk -F, -v s="$s" 'NR > 1 && (s == "ALL" || $1 == s) && $3 != "extra" {
        n++; c[$3]++; if ($3 == "ok" && $6 <= 300) fast++; if ($7 == "0") hidden++ }
      END { if (!n) exit
        printf "%s: opens=%d ok=%d timeout=%d missed=%d shield-not-in-front=%d within-300ms=%d (%.1f%% of all opens)\n",
          s, n, c["ok"], c["timeout"], c["missed"], hidden, fast, 100 * fast / n }' "$RESULTS"
    # Exposure is over all opens: a timeout or miss is slower than any measured value, so dropping it
    # would flatter the percentiles. Detect and present exist only for ok opens.
    printf '  %-8s ' exposure
    awk -F, -v s="$s" 'NR > 1 && (s == "ALL" || $1 == s) && $3 != "extra" { print ($3 == "ok") ? $6 : 999999 }' "$RESULTS" | sort -n | pct
    echo
    for col in 4:detect 5:present; do
      printf '  %-8s ' "${col#*:}"
      awk -F, -v s="$s" -v c="${col%%:*}" 'NR > 1 && (s == "ALL" || $1 == s) && $3 == "ok" { print $c }' "$RESULTS" | sort -n | pct
      echo " (ok opens only)"
    done
  done
  echo "extra Shields (same open shielded twice): $(awk -F, '$3 == "extra"' "$RESULTS" | wc -l | tr -d ' ')"
  echo "files: $RESULTS $OUT/events.csv $OUT/device.txt"
}

case ${1:-} in
device)
  {
    for p in ro.product.manufacturer ro.product.brand ro.product.model ro.product.device \
      ro.build.version.release ro.build.version.sdk ro.build.version.security_patch ro.build.type ro.build.fingerprint; do
      echo "$p=$(dev getprop "$p")"
    done
    echo "refresh: $(dev "dumpsys display | grep -o -E 'renderFrameRate [0-9.]+|fps=[0-9.]+'" | head -1)"
    echo "aurora: $(dev "dumpsys package $APP | grep -E 'versionName|pkgFlags'" | sort -u | tr -s " \n" " ")"
    echo "service enabled: $(dev "settings get secure enabled_accessibility_services" | grep -c "$SERVICE" || true)"
    echo "recorded: $(date -u +%FT%TZ)"
  } | tee "$OUT/device.txt"
  ;;
launches)
  pkg=${2:?package of a protected app}; n=${3:-50}; mode=${4:-warm}
  [ "$pkg" != "$APP" ] || die "pass the protected app, not aurora"
  need_service
  comp=$(launch_component "$pkg")
  dev "input keyevent KEYCODE_HOME"; sleep "$GAP"
  for i in $(seq 1 "$n"); do
    if [ "$mode" = cold ]; then dev "am force-stop $pkg"; sleep 0.5; fi
    before=$(rows)
    dev "am start -n $comp" > /dev/null
    sleep "$SETTLE"
    record "launch-$mode" "$i" "$before" "$(shield_in_front)" "$pkg" 1
    dev "input keyevent KEYCODE_HOME"; sleep "$GAP"
  done
  report
  ;;
rapid)
  pkg=${2:?protected package}; other=${3:?unprotected package}; n=${4:-20}
  need_service
  a=$(launch_component "$pkg"); b=$(launch_component "$other")
  before=$(rows)
  for _ in $(seq 1 "$n"); do
    dev "am start -n $a" > /dev/null; sleep "$RAPID"
    dev "am start -n $b" > /dev/null; sleep "$RAPID"
  done
  dev "am start -n $a" > /dev/null; sleep "$SETTLE"
  record "rapid-${RAPID}s" 1 "$before" "$(shield_in_front)" "$pkg" "$((n + 1))"
  echo "  (shield_in_front is checked once, after the last open. A miss here can be an open that was
  superseded before its window showed, or one that followed another Clock event without a fresh open)"
  dev "input keyevent KEYCODE_HOME"
  ;;
manual)
  label=${2:?label, for example recents, notification, split-screen, pip, lock-unlock, talkback, launcher-tap}
  n=${3:-5}
  need_service
  for i in $(seq 1 "$n"); do
    before=$(rows)
    printf '[%s %d/%d] Open the protected app that way now, then press Enter. ' "$label" "$i" "$n"; read -r _
    sleep "$SETTLE"
    record "$label" "$i" "$before" "$(shield_in_front)" "" 1
    printf 'Go back to the home screen, then press Enter. '; read -r _
  done
  ;;
kill)
  need_service
  pid=$(dev "pidof $APP") || die "aurora is not running"
  before=$(rows); t0=$(uptime_s)
  if dev "run-as $APP kill -9 $pid" > /dev/null 2>&1; then how=kill-9; else dev "am crash $APP" > /dev/null; how=am-crash; fi
  c=$(wait_connected "$before" 60)
  event "kill,method=$how killed_at_s=$t0 connected_at_s=$c restart_s=$(awk -v a="$t0" -v b="$c" 'BEGIN { print (b == "none") ? "none-within-60s" : b - a }')"
  ;;
revoke)
  before=$(rows)
  printf 'Turn OFF "aurora spike shield" in Settings > Accessibility, then press Enter. '; read -r _
  sleep 1
  event "revoke,unbind_row=$(new_rows "$before" | grep -c '^svc,unbind' || true) still_listed=$(dev 'settings get secure enabled_accessibility_services' | grep -c "$SERVICE" || true)"
  before=$(rows); t0=$(uptime_s)
  printf 'Read Android'"'"'s dialog, then turn it back ON and press Enter. '; read -r _
  event "reenable,asked_at_s=$t0 connected_at_s=$(wait_connected "$before" 30)"
  ;;
forcestop)
  need_service
  printf 'This force-stops aurora. Android may turn the service off; you then turn it back on yourself. Continue? [y/N] '
  read -r ok; [ "$ok" = y ] || exit 0
  dev "am force-stop $APP"; sleep 1
  event "forcestop,still_listed=$(dev 'settings get secure enabled_accessibility_services' | grep -c "$SERVICE" || true)"
  ;;
reboot)
  [ "${G1_ALLOW_REBOOT:-}" = 1 ] || die "rebooting needs G1_ALLOW_REBOOT=1"
  need_service
  before=$(rows)
  adb reboot; adb wait-for-device
  until [ "$(dev 'getprop sys.boot_completed')" = 1 ]; do sleep 0.5; done
  boot=$(uptime_s)
  printf 'Booted. Unlock the phone now, then press Enter. '; read -r _
  unlock=$(uptime_s)
  c=$(wait_connected "$before" 180)
  event "reboot,boot_completed_s=$boot unlock_s=$unlock connected_s=$c after_unlock_s=$(awk -v a="$unlock" -v b="$c" 'BEGIN { print (b == "none") ? "none-within-180s" : b - a }')"
  ;;
note) shift; event "note,$*" ;;
report) report ;;
*) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
