#!/bin/sh
# Renders the Play Store screenshots and feature graphic from the verified dark baselines, so store art
# always shows the real interface. Needs Google Chrome. Output: brand/store/out/.
set -e
cd "$(dirname "$0")/../.."
CHROME=${CHROME:-"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"}
OUT=brand/store/out
mkdir -p "$OUT"
shot() { # name baseline eyebrow lead tail
  url="file://$PWD/brand/store/frame.html?shot=$2&eyebrow=$(printf %s "$3" | sed 's/ /%20/g')&lead=$(printf %s "$4" | sed 's/ /%20/g')&tail=$(printf %s "$5" | sed 's/ /%20/g')"
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --allow-file-access-from-files --virtual-time-budget=3000 \
    --window-size=1080,1920 --screenshot="$PWD/$OUT/$1.png" "$url" 2>/dev/null
}
# The phone already carries the promise, so the caption says the descriptor instead of repeating it.
shot 01-home home_Dark "Aurora" "Phone use," "on your terms."
shot 02-intent shield_intent_Dark "Intentional App Opening" "A pause before" "you open."
shot 03-shield library_shield_Dark "Shield" "Protection that" "holds."
shot 04-focus focus_running_Dark "Focus" "One thing," "fully."
shot 05-track track_Dark "Track" "See your" "real progress."
"$CHROME" --headless=new --disable-gpu --hide-scrollbars --allow-file-access-from-files --virtual-time-budget=3000 \
  --window-size=1024,500 --screenshot="$PWD/$OUT/feature-graphic.png" "file://$PWD/brand/store/feature.html" 2>/dev/null
ls -la "$OUT"
