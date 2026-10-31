#!/usr/bin/env python3
"""Cuts every raster logo asset from the founder-approved master, brand/final/aurora-final-logo.png.

The master is only ever read. Light is lifted off its background with colour-to-alpha against a smooth
fit of that background, so a cutout composited on the fitted background gives the master back, glow included.
Needs numpy, scipy, Pillow and cwebp. Run from anywhere: python3 brand/tools/final_logo.py
"""
import os, subprocess
import numpy as np
from PIL import Image
from scipy import ndimage

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
FINAL = os.path.join(ROOT, "brand", "final")
RES = os.path.join(ROOT, "app", "src", "main", "res")

# Master coordinates. The gap between symbol and wordmark is where the two are parted.
SYMBOL_BOX = (142, 190, 1122, 862)
WORD_BOX = (222, 856, 1022, 1034)
FULL_BOX = (142, 190, 1122, 1034)
PART_Y = 858
# The launcher foreground: this master point sits at the icon's centre, at this many master px per dp.
ICON_CENTRE = (627, 572)
ICON_PX_PER_DP = 15.0
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def background(a):
    """A degree-5 fit of the master's background, from the pixels well clear of the logo's light."""
    h, w, _ = a.shape
    light = ndimage.binary_dilation(a.max(2) > 40 / 255, iterations=70)
    y, x = np.mgrid[0:h, 0:w]
    x, y = x / w - 0.5, y / h - 0.5
    basis = np.stack([x**i * y**j for i in range(6) for j in range(6 - i)], -1)
    keep = ~light
    return np.dstack([basis @ np.linalg.lstsq(basis[keep], a[..., c][keep], rcond=None)[0] for c in range(3)]).clip(0, 1)


def lift(a, b):
    """Colour-to-alpha: the least alpha that explains each pixel as light over the background."""
    alpha = ((a - b).clip(0, None) / (1 - b).clip(1e-3)).max(2)
    # Sensor-level noise in the background must not become a visible veil around the logo.
    alpha = ((alpha - 0.012) / (0.05 - 0.012)).clip(0, 1) ** 2 * (3 - 2 * ((alpha - 0.012) / (0.05 - 0.012)).clip(0, 1)) * alpha
    rgb = np.where(alpha[..., None] > 1e-4, (a - (1 - alpha[..., None]) * b) / alpha[..., None].clip(1e-4), 0).clip(0, 1)
    return np.dstack([rgb, alpha])


def fade_box(rgba, box, feather):
    """Takes alpha to nothing at the edges of a crop, so no glow ends in a straight line."""
    l, t, r, btm = box
    h, w = rgba.shape[:2]
    y, x = np.mgrid[0:h, 0:w].astype(float)
    edge = np.minimum.reduce([x - l, r - 1 - x, y - t, btm - 1 - y]) / feather
    out = rgba.copy()
    out[..., 3] *= edge.clip(0, 1)
    return out[t:btm, l:r]


def part(rgba, top_part):
    """Keeps what lies above or below the gap between symbol and wordmark, with a soft seam."""
    y = np.arange(rgba.shape[0], dtype=float)[:, None]
    ramp = (PART_Y + 6 - y) / 12 if top_part else (y - PART_Y + 6) / 12
    out = rgba.copy()
    out[..., 3] *= ramp.clip(0, 1)
    return out


def image(rgba):
    return Image.fromarray((rgba * 255 + 0.5).astype(np.uint8), "RGBA")


def premultiplied_resize(img, size):
    """Lanczos in premultiplied space, so transparent pixels never bleed a dark fringe into the edges."""
    a = np.asarray(img).astype(np.float32) / 255
    a[..., :3] *= a[..., 3:]
    bands = [Image.fromarray(a[..., c]).resize(size, Image.LANCZOS) for c in range(4)]
    o = np.dstack([np.asarray(b) for b in bands]).clip(0, 1)
    o[..., :3] = np.where(o[..., 3:] > 1e-4, o[..., :3] / o[..., 3:].clip(1e-4), 0).clip(0, 1)
    return image(o)


def webp(img, path, quality=95):
    tmp = path + ".png"
    img.save(tmp)
    subprocess.check_call(["cwebp", "-quiet", "-q", str(quality), "-alpha_q", "100", "-exact", "-m", "6", tmp, "-o", path])
    os.remove(tmp)


def width(img, w):
    return premultiplied_resize(img, (w, round(img.height * w / img.width)))


def launcher_foreground(rgba, dp_px):
    """The 108dp adaptive foreground at [dp_px] px per dp, from master pixels only."""
    span = 108 * ICON_PX_PER_DP
    cx, cy = ICON_CENTRE
    box = (cx - span / 2, cy - span / 2, cx + span / 2, cy + span / 2)
    canvas = np.zeros((round(span), round(span), 4), np.float32)
    h, w = rgba.shape[:2]
    ox, oy = round(box[0]), round(box[1])
    x0, y0, x1, y1 = max(ox, 0), max(oy, 0), min(ox + canvas.shape[1], w), min(oy + canvas.shape[0], h)
    canvas[y0 - oy:y1 - oy, x0 - ox:x1 - ox] = rgba[y0:y1, x0:x1]
    size = round(108 * dp_px)
    return premultiplied_resize(image(canvas), (size, size))


def main():
    master = Image.open(os.path.join(FINAL, "aurora-final-logo.png")).convert("RGB")
    a = np.asarray(master).astype(np.float64) / 255
    b = background(a)
    rgba = lift(a, b)
    symbol = part(rgba, True)
    word = part(rgba, False)
    outs = {
        "aurora-logo-full-transparent": fade_box(rgba, FULL_BOX, 14),
        "aurora-symbol-transparent": fade_box(symbol, SYMBOL_BOX, 14),
        "aurora-wordmark-transparent": fade_box(word, WORD_BOX, 8),
    }
    imgs = {k: image(v) for k, v in outs.items()}
    for k, v in imgs.items():
        v.save(os.path.join(FINAL, k + ".png"), optimize=True)
    # The same crops, pixel for pixel, on the master's own background.
    master.crop(SYMBOL_BOX).save(os.path.join(FINAL, "aurora-symbol-on-midnight.png"), optimize=True)

    nodpi = os.path.join(RES, "drawable-nodpi")
    webp(imgs["aurora-logo-full-transparent"], os.path.join(nodpi, "logo_full.webp"))
    # Compose scales bitmaps bilinearly, so each use gets a cut near its size on screen.
    webp(width(imgs["aurora-symbol-transparent"], 600), os.path.join(nodpi, "logo_symbol.webp"))
    webp(width(imgs["aurora-symbol-transparent"], 240), os.path.join(nodpi, "logo_symbol_small.webp"))
    webp(width(imgs["aurora-wordmark-transparent"], 480), os.path.join(nodpi, "logo_wordmark.webp"))

    fg = symbol.astype(np.float32)
    for name, k in DENSITIES.items():
        folder = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(folder, exist_ok=True)
        webp(launcher_foreground(fg, k), os.path.join(folder, "ic_launcher_fg.webp"))
    # The Android 12+ splash icon: the launcher's composition on the splash's 288dp canvas, at 3x. Its safe area fits the system's 192dp circle.
    webp(launcher_foreground(fg, 288 * 3 / 108), os.path.join(nodpi, "splash_symbol.webp"))

    # The store icon: the launcher composition on the master's own fitted background, without the wordmark.
    span = 72 * ICON_PX_PER_DP
    cx, cy = ICON_CENTRE
    box = tuple(round(v) for v in (cx - span / 2, cy - span / 2, cx + span / 2, cy + span / 2))
    alpha = symbol[..., 3:]
    icon = image(np.dstack([alpha * symbol[..., :3] + (1 - alpha) * b, np.ones(alpha.shape)])).crop(box).resize((512, 512), Image.LANCZOS).convert("RGB")
    icon.save(os.path.join(ROOT, "brand", "store", "out", "play-icon-512.png"), optimize=True)
    icon.save(os.path.join(FINAL, "aurora-app-icon-512.png"), optimize=True)

    # Round trip: cutout over fitted background against the master. Report it, don't assume it.
    c = rgba[..., 3:] * rgba[..., :3] + (1 - rgba[..., 3:]) * b
    err = np.abs(c - a).max(2) * 255
    print(f"round trip error on master: mean {err.mean():.2f}/255, p99 {np.percentile(err, 99):.2f}/255, max {err.max():.1f}/255")
    cs = lambda x, y: tuple((b[y, x] * 255).round().astype(int))
    print("fitted background: centre", cs(627, 560), "mid-edge", cs(627, 30), cs(30, 627), "corner", cs(30, 30), cs(30, 1224))


if __name__ == "__main__":
    main()
