#!/usr/bin/env python3
"""Builds the FIRST LIGHT opening's assets from the approved rasters. Nothing here redraws the logo.

The reveal map says, for every pixel of logo_full.webp, when the light reaches it (0 to 1 of the formation).
It is measured along the ribbons' centre lines, traced from brand/final/aurora-final-logo.png, so the logo's own
pixels appear along its own curves. The Android 12+ splash icon is the same reveal, played from the system's
splash before the app has drawn anything; ui/components/Unveil.kt carries it on from wherever it has got to.

Run after brand/tools/final_logo.py: python3 brand/tools/first_light.py. Needs numpy and Pillow.
"""
import os
import numpy as np
from PIL import Image, ImageFilter

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
NODPI = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")

MASTER = 1254
# From final_logo.py: logo_full's crop, and the splash canvas (288dp at 5.625 master px per dp around this centre).
FULL_BOX = (142, 190, 1122, 1034)
CENTRE = (627, 572)
CANVAS = 1620
WORD_TOP = 858
MAP_SCALE = 4  # master px per map px

# Ribbon centre lines in master px, in the order the light travels, with (start, length) of each ribbon's journey.
RIBBONS = [
    # The A itself: up the left leg, over the crown, down the right leg.
    ((0.00, 0.58), [(205, 812), (290, 792), (360, 710), (430, 585), (510, 420), (585, 290), (640, 248), (695, 290), (750, 425), (815, 585), (880, 720), (950, 805), (1040, 822)]),
    # The second light, crossing through it from the far left tip.
    ((0.24, 0.42), [(170, 722), (290, 665), (400, 615), (550, 562), (700, 572), (830, 597), (960, 650), (1112, 742)]),
    # The low band under the crossbar.
    ((0.30, 0.40), [(240, 826), (400, 826), (480, 770), (560, 682), (625, 656), (690, 690), (750, 788), (850, 828), (985, 830)]),
]
SPREAD = 0.20 / 100  # how much later, per master px off the centre line, the light fans out to a pixel
LATEST = 0.75  # nothing starts after this, so the frame at the formation's end is the raster, untouched
EDGE, AGE = 0.12, 0.25  # match UnveilSource in Unveil.kt


def along(points, n=400):
    pts = np.array(points, float)
    seg = np.r_[0, np.cumsum(np.hypot(*np.diff(pts, axis=0).T))]
    # Catmull-Rom through the points, so the journey has no corners.
    s = np.linspace(0, seg[-1], n)
    idx = np.clip(np.searchsorted(seg, s, side="right") - 1, 0, len(pts) - 2)
    u = (s - seg[idx]) / (seg[idx + 1] - seg[idx])[..., None].squeeze()
    p0 = pts[np.clip(idx - 1, 0, None)]; p1 = pts[idx]; p2 = pts[idx + 1]; p3 = pts[np.clip(idx + 2, None, len(pts) - 1)]
    u = u[:, None]
    out = 0.5 * (2 * p1 + (p2 - p0) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u**2 + (3 * p1 - p0 - 3 * p2 + p3) * u**3)
    return out, s / seg[-1]


def reveal_map():
    n = MASTER // MAP_SCALE
    y, x = np.mgrid[0:n, 0:n].astype(np.float32) * MAP_SCALE
    t = np.full((n, n), 9.0, np.float32)
    for (start, length), points in RIBBONS:
        line, s = along(points)
        # The journey eases, so the light sets off slowly and arrives calmly.
        when = start + length * (s * s * (3 - 2 * s)) ** 0.8
        for (lx, ly), w in zip(line[::4], when[::4]):
            t = np.minimum(t, w + np.hypot(x - lx, y - ly) * SPREAD)
    # The wordmark comes last, left to right; only the master's gap separates it from the symbol.
    word = (y > WORD_TOP - 8) & (x > 200) & (x < 1040)
    t[word] = 0.58 + (x[word] - 222) / 800 * 0.14
    t = t.clip(0, LATEST)
    blurred = Image.fromarray((t / LATEST * 255).astype(np.uint8), "L").filter(ImageFilter.GaussianBlur(1.5))
    return np.asarray(blurred).astype(np.float32) / 255 * LATEST


def smoothstep(a, b, v):
    k = ((v - a) / (b - a)).clip(0, 1)
    return k * k * (3 - 2 * k)


def frame(symbol, t, p):
    """The same sums as UnveilSource, offline, in premultiplied light."""
    a = smoothstep(t, t + EDGE, np.float32(p))[..., None]
    age = (((p - t) / AGE).clip(0, 1))[..., None]
    tint = np.array([0.18, 0.78, 0.55], np.float32) * (1 - age) + age
    front = np.exp(-(((p - t - 0.03) / 0.04) ** 2))[..., None] * (1 - age)
    rgb = symbol[..., :3] * tint + symbol[..., 3:] * front * np.array([0.55, 1.0, 0.75], np.float32) * 0.35
    alpha = symbol[..., 3:]
    out = np.dstack([rgb.clip(0, 1) * a, alpha * a])
    keep = out[..., 3:] > 1e-3
    out[..., :3] = np.where(keep, out[..., :3] / out[..., 3:].clip(1e-3), 0).clip(0, 1)
    return Image.fromarray((out * 255 + 0.5).astype(np.uint8), "RGBA")


def main():
    t = reveal_map()
    # The map for logo_full: its crop of the master.
    l, top, r, b = (v // MAP_SCALE for v in FULL_BOX)
    Image.fromarray((t[top:b, l:r] / LATEST * 255 + 0.5).astype(np.uint8), "L").save(os.path.join(NODPI, "first_light_reveal.png"), optimize=True)

    # The splash icon: the reveal on the system's 288dp canvas, one second of it, ending on the approved symbol.
    size = 432
    source = Image.open(os.path.join(NODPI, "splash_symbol.webp")).convert("RGBA").resize((size, size), Image.LANCZOS)
    symbol = np.asarray(source).astype(np.float32) / 255
    symbol[..., :3] *= symbol[..., 3:]
    canvas = np.full((CANVAS // MAP_SCALE, CANVAS // MAP_SCALE), LATEST, np.float32)
    ox, oy = (CANVAS // 2 - CENTRE[0]) // MAP_SCALE, (CANVAS // 2 - CENTRE[1]) // MAP_SCALE
    canvas[oy:oy + t.shape[0], ox:ox + t.shape[1]] = t
    t_big = np.asarray(Image.fromarray(canvas).resize((size, size), Image.BILINEAR))
    frames = [frame(symbol, t_big, p) for p in np.linspace(0.03, 1.0, 20)]
    frames[0].save(os.path.join(NODPI, "splash_first_light.webp"), save_all=True, append_images=frames[1:], duration=50, loop=1, quality=72, alpha_quality=80, method=6, exact=True)
    print("frames", len(frames), "bytes", os.path.getsize(os.path.join(NODPI, "splash_first_light.webp")))


if __name__ == "__main__":
    main()
