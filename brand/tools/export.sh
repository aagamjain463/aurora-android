#!/bin/sh
# Renders First Light and exports the Android drawables. Needs numpy and cwebp.
set -e
cd "$(dirname "$0")/../.."
PY=${PY:-/Applications/Blender.app/Contents/Resources/5.2/python/bin/python3.13}
"$PY" -I brand/tools/first_light.py 1440
for f in first_light_night first_light_day first_light_night_centered first_light_day_centered; do
  cwebp -quiet -q 82 -m 6 -sharp_yuv "brand/artwork/$f.png" -o "app/src/main/res/drawable-nodpi/$f.webp"
done
ls -la app/src/main/res/drawable-nodpi/
