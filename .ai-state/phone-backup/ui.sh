#!/bin/zsh
# usage: ui.sh dump | ui.sh tap "<text regex>" | ui.sh shot name
export ANDROID_SERIAL=${ANDROID_SERIAL:-ayeqz595b6g6u4ey}
S=/private/tmp/claude-501/-Users-aagamjain-Desktop-aurora-android/359120f1-4007-4c9e-b0df-ef4af98b1ee4/scratchpad
case $1 in
dump) adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml > $S/ui.xml; python3 - $S/ui.xml <<'PY'
import sys,re
x=open(sys.argv[1]).read()
for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?clickable="(\w+)"[^>]*?bounds="([^"]*)"',x):
    t,d,c,b=m.groups()
    if t or d: print(f"{t!r} {d!r} click={c} {b}")
PY
;;
tap) adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml > $S/ui.xml; python3 - $S/ui.xml "$2" <<'PY' | read X Y; [ -n "$X" ] && adb shell input tap $X $Y && echo "tapped $X $Y" || echo "not found: $2"
import sys,re
x=open(sys.argv[1]).read(); pat=re.compile(sys.argv[2])
for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x):
    t,d,a,b,c,e=m.groups()
    if pat.search(t) or pat.search(d):
        print((int(a)+int(c))//2,(int(b)+int(e))//2); break
PY
;;
shot) adb exec-out screencap -p > $S/$2.png; echo $S/$2.png;;
esac
