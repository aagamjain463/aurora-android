#!/bin/sh
# Renders the Aurora painting and exports the Android drawables. Needs numpy and cwebp.
set -e
cd "$(dirname "$0")/../.."
PY=${PY:-$(ls /Applications/Blender.app/Contents/Resources/*/python/bin/python3.13 | head -1)}
"$PY" -I brand/tools/aurora_art.py 1440
for f in aurora_night aurora_still; do
  cwebp -quiet -q 92 -m 6 -sharp_yuv "brand/artwork/$f.png" -o "app/src/main/res/drawable-nodpi/$f.webp"
done
cwebp -quiet -lossless "brand/artwork/aurora_mask.png" -o "app/src/main/res/drawable-nodpi/aurora_mask.webp"
ls -la app/src/main/res/drawable-nodpi/
