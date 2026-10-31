#!/usr/bin/env python3
"""
Aurora's one painting: a polar night over a frozen lake, a dark massif at the left and snow ridges at the right,
with one ribbon of oxygen green winding across the sky. Tones measured from real footage of the aurora in Alaska
(docs/design-studio/13-the-ribbon.md); the land is generated here, never traced from a photograph.

Writes to brand/artwork/:
  aurora_night.png  the scene without light; AuroraSky draws the ribbons live on top
  aurora_mask.png   red: open sky, green: open water, so live light never crosses the land
  aurora_still.png  the scene with the light painted in, for Android 12 and below

Geometry shared with AuroraSky in Sky.kt: 1440x1324, horizon at 0.44. ribbons() below is RibbonSource in Sky.kt,
line for line: change one, change both. Needs numpy.
"""
import os
import sys
import zlib
import struct
import numpy as np

OUT = os.path.join(os.path.dirname(__file__), "..", "artwork")
HORIZON = 0.44
STILL_MOMENT = 260.0
# Same as Palette in Color.kt and brand/tokens/aurora.tokens.json.
PAGE = "#080A0F"
DEEP, BODY, EDGE, TOP = "#0B5A2A", "#17A850", "#4BEA55", "#3D3A9E"


def hexc(h):
    return np.array([int(h[i:i + 2], 16) / 255 for i in (1, 3, 5)])


def write_png(path, rgb):
    a = (np.clip(rgb, 0, 1) * 255 + 0.5).astype(np.uint8)
    h, w = a.shape[:2]
    raw = b"".join(b"\0" + a[y].tobytes() for y in range(h))
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d))
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))


def smooth(a, b, x):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


def mix(a, b, t):
    return a * (1 - t) + b * t


def fract(x):
    return x - np.floor(x)


def hash2(x, y):
    x, y = fract(x * 123.34), fract(y * 456.21)
    k = x * (x + 45.32) + y * (y + 45.32)
    return fract((x + k) * (y + k))


def noise(x, y):
    """Value noise, as NoiseSource in Sky.kt."""
    x, y = np.asarray(x, dtype=np.float64), np.asarray(y, dtype=np.float64)
    i, j = np.floor(x), np.floor(y)
    f, g = x - i, y - j
    u, v = f * f * (3 - 2 * f), g * g * (3 - 2 * g)
    return mix(mix(hash2(i, j), hash2(i + 1, j), u), mix(hash2(i, j + 1), hash2(i + 1, j + 1), u), v)


def fbm(x, y, octaves=4):
    out, amp, total = 0.0, 1.0, 0.0
    for k in range(octaves):
        out = out + amp * noise(x * 2 ** k + k * 7.3, y * 2 ** k + k * 3.1)
        total += amp
        amp *= 0.5
    return out / total


def ribbon(x0, y0, t, seed, base, slope, tall):
    """One ribbon. Returns (light, height up the ray, 0 to 1). RibbonSource's ribbon(), line for line."""
    bend = noise(x0 * 1.6 + seed, y0 * 3.2 + t * 0.030) - 0.5
    sway = noise(x0 * 2.1 + seed + 5.0, t * 0.026) - 0.5
    # The folds: x depends on height, so the band can double back on itself.
    x = x0 + 0.20 * bend + 0.018 * np.sin(y0 * 15.0 + x0 * 3.5 + t * 0.045 + seed)
    # A slow wave travels the length of the band.
    c = base + slope * (x - 0.5) + 0.050 * np.sin(x * 4.6 + t * 0.055 + seed) + 0.016 * np.sin(x * 11.0 - t * 0.085 + seed * 2.0) + 0.085 * sway
    d = y0 - c
    # Bright knots wander along it, and it thins to nearly nothing between them.
    knot = 0.30 + 0.70 * smooth(0.25, 0.85, noise(x * 2.4 - t * 0.050 + seed, seed + 1.0))
    wide = noise(x * 11.0 + t * 0.040, seed + 2.0)
    fine = noise(x * 34.0 - t * 0.070, seed + 3.0)
    rays = (0.80 * wide + 0.20 * fine) ** 1.4
    # Soft hem underneath, the band itself, then rays rising from it.
    thick = 0.026 + 0.024 * smooth(0.30, 1.0, knot)
    core = 0.62 * np.exp(-(d / thick) ** 2) + 0.30 * np.exp(-(d / (thick * 2.6)) ** 2)
    up = -d
    reach = tall * (0.40 + 0.95 * noise(x * 5.5 + t * 0.022, seed + 4.0))
    above = np.exp(-np.maximum(up, 0) / reach) * (0.10 + 0.90 * rays)
    below = np.exp(-(np.maximum(-up, 0) / 0.040) ** 2)
    return (core + above * below) * knot, np.clip(up / (reach * 2.4), 0, 1)


def ribbons(x, y, t, reach=0.5):
    """The whole sky's light at (x, y) in the painting, 0 to 1 across each axis. RibbonSource's ribbons()."""
    tall = 0.15 + 0.08 * reach
    near, near_up = ribbon(x, y, t, 11.7, 0.245, -0.070, tall)
    far, far_up = ribbon(x, y, t * 0.8, 3.1, 0.130, 0.030, tall * 0.8)
    # The wash of light low in the sky, and the thin band that always sits on the far horizon.
    veil = 0.11 * smooth(0.00, 0.38, y) * (0.50 + 0.70 * noise(x * 1.9 - t * 0.012, y * 3.0 + 9.0))
    band = 0.07 * np.exp(-np.abs(y - (HORIZON - 0.012)) / 0.030)
    far = far * 0.36
    # A soft bank of light over the massif, so the left of the sky is never empty, whatever the ribbon is doing.
    bank = 0.34 * (1 - smooth(0.12, 0.66, x)) * np.exp(-((y - 0.17) / 0.105) ** 2) * (0.55 + 0.75 * noise(x * 3.0 + t * 0.018, y * 4.0 + 2.0))
    # A low arc over the snow ridges, with its own rays, so the lower right of the sky is lit too.
    lane = y - (0.315 + 0.05 * (x - 0.5) + 0.018 * np.sin(x * 5.3 - t * 0.04 + 1.0))
    rise = np.maximum(-lane, 0) / 0.085
    arc = 0.52 * smooth(0.22, 0.64, x) * (0.50 + 0.80 * noise(x * 9.0 + t * 0.03, np.full_like(x, 6.0))) * np.where(lane > 0, np.exp(-(lane / 0.030) ** 2), np.exp(-rise))
    light = near + far
    return light + veil + band + bank + arc, (near * near_up + far * far_up) / np.maximum(light, 1e-4)


def tint(light, up):
    """Deep green where it is faint, oxygen green in the band. It never burns white: this is light to rest under."""
    c = mix(hexc(DEEP), hexc(BODY), smooth(0.10, 0.45, light)[..., None])
    c = mix(c, hexc(EDGE), smooth(0.40, 1.20, light)[..., None])
    return mix(c, hexc(TOP), (0.70 * smooth(0.45, 1.0, up))[..., None])


def alpha(light, k=1.0):
    return (1 - np.exp(-light * k * 1.4)) * 0.70


def ridge_line(u, seed, jag, scale):
    """A mountain skyline: u 0..1 across, height in painting units."""
    a = 1 - 2 * np.abs(fbm(u * scale + seed, np.full_like(u, seed), 5) - 0.5)
    b = fbm(u * scale * 5 + seed, np.full_like(u, seed + 3.0), 4)
    return a + jag * (b - 0.5)


def land_shade(xx, yy, seed, lit):
    """Faces of a mountain: some turned to the moon, some away, each streaked with gullies down its own fall line."""
    towards = fbm((xx + yy * 0.9) * 30 + seed, (yy - xx * 0.3) * 5 + seed, 4)
    away = fbm((xx - yy * 0.8) * 26 + seed + 3, (yy + xx * 0.3) * 5 + seed, 4)
    face = smooth(0.42, 0.58, fbm(xx * 7 + seed, yy * 9 + seed + 7, 3))
    return lit * mix(0.10 + 0.18 * smooth(0.40, 0.70, away), 0.45 + 0.55 * smooth(0.35, 0.70, towards), face)


def scene(w, h, rng):
    yh = int(h * HORIZON)
    yy, xx = np.mgrid[0:h, 0:w].astype(float)
    u, v = xx / w, yy / h
    t = v / HORIZON
    # Aubergine black at the top, thinning to a green-black where the light pools.
    sky = mix(hexc("#07060D"), hexc("#0C0D14"), smooth(0, 0.45, t)[..., None])
    sky = mix(sky, hexc("#0D1A17"), smooth(0.45, 1, t)[..., None])
    # Stars: pin-sharp, many faint and a few bright, thinning toward the horizon.
    n = int(w * h / 1300)
    sx, sy = rng.integers(0, w, n), rng.integers(0, yh, n)
    mag = rng.uniform(0.05, 1, n) ** 6 * 1.1 * (1 - sy / yh) ** 0.5 + 0.035
    stars = np.zeros((h, w))
    stars[sy, sx] = mag
    k = np.array([0.10, 0.8, 0.10])
    stars = np.apply_along_axis(lambda c: np.convolve(c, k, "same"), 0, stars)
    stars = np.apply_along_axis(lambda c: np.convolve(c, k, "same"), 1, stars)
    img = sky + stars[..., None] * hexc("#E4EBF0")
    land_mask = np.zeros((h, w))
    x = u[0]
    # Far range: jagged, snow catching the moon, seen in the valley's V.
    far = HORIZON - 0.018 - 0.034 * ridge_line(x, 21.0, 0.75, 9.0)
    far_land = smooth(-0.0008, 0.0008, v - far[None, :]) * (v <= HORIZON + 0.002)
    far_tone = mix(hexc("#101317"), hexc("#3F454C"), land_shade(u, v, 5.0, 1.0)[..., None])
    img = mix(img, far_tone, far_land[..., None])
    land_mask = np.maximum(land_mask, far_land)
    # The right ridge: a long snow slope climbing to the edge.
    right = HORIZON - 0.004 - smooth(0.50, 1.04, x) ** 1.2 * (0.105 + 0.028 * ridge_line(x, 8.0, 0.35, 5.0))
    land = smooth(-0.0009, 0.0009, v - right[None, :]) * (v <= HORIZON + 0.002)
    tone = mix(hexc("#0B0D11"), hexc("#565C64"), land_shade(u, v, 11.0, 0.95)[..., None] * smooth(0.58, 0.86, u)[..., None])
    img = mix(img, tone, land[..., None])
    land_mask = np.maximum(land_mask, land)
    # The left massif: nearly black, it holds the frame and the light goes behind it.
    left = HORIZON - 0.002 - (1 - smooth(-0.02, 0.56, x)) ** 1.15 * (0.145 + 0.034 * ridge_line(x, 14.0, 0.55, 5.0))
    land = smooth(-0.0009, 0.0009, v - left[None, :]) * (v <= HORIZON + 0.002)
    tone = mix(hexc("#060709"), hexc("#101215"), land_shade(u, v, 17.0, 0.45)[..., None])
    img = mix(img, tone, land[..., None])
    land_mask = np.maximum(land_mask, land)
    # Low black foothills along the shore, so no range ends in a straight line.
    foot = HORIZON - 0.0035 - 0.0065 * ridge_line(x, 31.0, 0.9, 16.0)
    land = smooth(-0.0009, 0.0009, v - foot[None, :]) * (v <= HORIZON + 0.002)
    img = mix(img, hexc("#050608"), land[..., None])
    land_mask = np.maximum(land_mask, land)
    # The lake: frozen and dark, a soft mirror broken by long ripples.
    rows = np.arange(yh, h)
    dist = (rows - yh).astype(float)
    ripple = fbm(u[yh:] * 7, v[yh:] * 190, 3) - 0.5
    src_y = np.clip(2 * yh - rows, 0, yh - 1)
    src_x = np.clip(xx[yh:] + ripple * (1 + dist[:, None] * 0.05) * 22 * w / 1440, 0, w - 1).astype(int)
    water = img[src_y[:, None], src_x]
    img[yh:] = water * (0.13 * np.exp(-dist / (h * 0.25)))[:, None, None] + hexc("#06080C")
    # The shore: a thin black line where land meets ice.
    shore = np.exp(-np.abs(v - HORIZON) / 0.0016)
    img *= (1 - 0.5 * shore)[..., None]
    # Fade the bottom into the page.
    fade = smooth(0.60, 0.985, v) ** 1.2
    img = mix(img, hexc(PAGE), fade[..., None])
    mask = np.zeros((h, w, 3))
    mask[..., 0] = (v < HORIZON) * (1 - land_mask)
    mask[..., 1] = (v > HORIZON) * (1 - fade)
    # Grain, so long dark gradients never band.
    img += rng.standard_normal((h, w, 1)) * 0.0035
    return img, mask


def lit(img, mask, w, h, t=STILL_MOMENT, reach=0.5, k=1.0):
    """The scene with the light on it, as AuroraSky's main() draws it."""
    yy, xx = np.mgrid[0:h, 0:w].astype(float)
    x, y = xx / w, yy / h
    light, up = ribbons(x, y, t, reach)
    a = (alpha(light, k) * mask[..., 0])[..., None]
    out = img * (1 - a) + tint(light, up) * a
    # Its reflection: dim, smeared along the ripples.
    depth = y - HORIZON
    wobble = noise(x * 14.0, y * 160.0) - 0.5
    mx, my = x + wobble * 0.010 * (1 + depth * 8), HORIZON - depth * 1.5
    light, up = ribbons(mx, my, t, reach)
    a = (alpha(light, k) * 0.34 * np.exp(-depth / 0.20) * mask[..., 1])[..., None]
    return out * (1 - a) + tint(light, up) * a


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    w = int(sys.argv[1]) if len(sys.argv) > 1 else 1440
    h = int(w * 0.92)
    rng = np.random.default_rng(11)
    img, mask = scene(w, h, rng)
    if len(sys.argv) > 2:
        # Preview: aurora_art.py WIDTH OUTDIR t1 t2 ... renders the sky at each moment, nothing else.
        for t in sys.argv[3:]:
            write_png(os.path.join(sys.argv[2], f"sky_{t}.png"), lit(img, mask, w, h, float(t)))
        sys.exit()
    write_png(os.path.join(OUT, "aurora_night.png"), img)
    q = mask[: h - h % 4, : w - w % 4].reshape(h // 4, 4, w // 4, 4, 3).mean((1, 3))
    write_png(os.path.join(OUT, "aurora_mask.png"), q)
    write_png(os.path.join(OUT, "aurora_still.png"), lit(img, mask, w, h))
