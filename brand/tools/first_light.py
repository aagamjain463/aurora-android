"""
First Light: Aurora's signature horizon artwork, rendered procedurally.

Original, deterministic art (no stock, no generative model): layered ridgelines from fractal noise,
aerial perspective and valley mist, a luminous horizon ring, and a rippled lake reflection. The same
code renders every variant, so the art can be re-tuned and re-exported in one place.

Run with any Python that has numpy (Blender's bundled interpreter works):
    python3 -I brand/tools/first_light.py
Writes PNG masters to brand/artwork/ ; brand/tools/export.sh turns them into Android WebP.
"""
import os
import struct
import sys
import zlib

import numpy as np

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "artwork")


def hexc(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4)])


def write_png(path, rgb):
    """rgb: float array HxWx3 in 0..1."""
    h, w, _ = rgb.shape
    data = (np.clip(rgb, 0, 1) * 255 + 0.5).astype(np.uint8)
    raw = b"".join(b"\x00" + data[y].tobytes() for y in range(h))

    def chunk(tag, body):
        c = struct.pack(">I", len(body)) + tag + body
        return c + struct.pack(">I", zlib.crc32(tag + body) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def blur(img, sx, sy=None):
    """Gaussian blur via FFT. img HxW or HxWxC."""
    sy = sx if sy is None else sy
    h, w = img.shape[:2]
    fy = np.fft.fftfreq(h)[:, None]
    fx = np.fft.fftfreq(w)[None, :]
    g = np.exp(-2 * (np.pi ** 2) * ((fx * sx) ** 2 + (fy * sy) ** 2))
    if img.ndim == 2:
        return np.real(np.fft.ifft2(np.fft.fft2(img) * g))
    return np.stack([np.real(np.fft.ifft2(np.fft.fft2(img[..., c]) * g)) for c in range(img.shape[2])], -1)


def noise1d(n, rng, octaves, base_period, persistence=0.5):
    """Fractal value noise along one axis, roughly -1..1."""
    x = np.arange(n, dtype=float)
    out = np.zeros(n)
    amp, period, total = 1.0, base_period, 0.0
    for _ in range(octaves):
        pts = rng.uniform(-1, 1, int(n / period) + 3)
        t = x / period
        i = t.astype(int)
        f = t - i
        f = f * f * (3 - 2 * f)
        out += amp * (pts[i] * (1 - f) + pts[i + 1] * f)
        total += amp
        amp *= persistence
        period /= 2.0
    return out / total


def noise2d(h, w, rng, period_x, period_y):
    gy, gx = int(h / period_y) + 3, int(w / period_x) + 3
    pts = rng.uniform(0, 1, (gy, gx))
    y = np.arange(h) / period_y
    x = np.arange(w) / period_x
    iy, ix = y.astype(int), x.astype(int)
    fy, fx = y - iy, x - ix
    fy = (fy * fy * (3 - 2 * fy))[:, None]
    fx = (fx * fx * (3 - 2 * fx))[None, :]
    a = pts[iy][:, ix]
    b = pts[iy][:, ix + 1]
    c = pts[iy + 1][:, ix]
    d = pts[iy + 1][:, ix + 1]
    return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


PALETTES = {
    # Night: the approved direction. Emerald-black sky, golden ring, misty emerald hills.
    "night": dict(
        bg="#05100D", sky_top="#031110", sky_mid="#07170F", sky_low="#132C20",
        glow="#F0BE6A", core="#FFF0C8", ember="#D9893F", halo="#2A6449",
        fog_near_sun="#A39E6A", fog_far="#24493A",
        hills=["#365F49", "#284A3A", "#1B3A2D", "#132C22", "#0C2019", "#071510"],
        water_tint="#0B2219", ring="#FFE7AE", ring_bloom="#F3B85C", stars=0.55,
        reflect=0.52, grain=0.010,
    ),
    # Day: the same place in early morning mist, for the light theme's warm paper.
    "day": dict(
        bg="#F5F1E8", sky_top="#EEEDE4", sky_mid="#E1E7DC", sky_low="#EBD9BC",
        glow="#F4C98A", core="#FFF6E2", ember="#E8A866", halo="#C9DACB",
        fog_near_sun="#F1E2C6", fog_far="#D3DFD4",
        hills=["#C3D2C2", "#AFC3B1", "#98B29F", "#7F9C8A", "#6A8877", "#557264"],
        water_tint="#D9E2D6", ring="#D99A4E", ring_bloom="#F2C080", stars=0.0,
        reflect=0.50, grain=0.006,
    ),
}


def render(palette, w, h, ring_x, seed=7, fade_start=0.60, horizon=0.43, ring_r=0.219, trees=True):
    p = PALETTES[palette]
    day = palette == "day"
    rng = np.random.default_rng(seed)
    yy, xx = np.mgrid[0:h, 0:w].astype(float)
    yh = int(h * horizon)  # the water line
    cx, R = w * ring_x, w * ring_r
    cy = yh + R * 0.22  # the ring sits slightly sunk behind the hills

    # 1. Sky: vertical gradient, then light pooling around the ring.
    t = yy / yh
    sky = (hexc(p["sky_top"]) * (1 - smoothstep(0, 0.75, t))[..., None]
           + hexc(p["sky_mid"]) * (smoothstep(0, 0.75, t) * (1 - smoothstep(0.55, 1.0, t)))[..., None]
           + hexc(p["sky_low"]) * smoothstep(0.55, 1.0, t)[..., None])
    d_sun = np.sqrt(((xx - cx) / 1.25) ** 2 + (yy - yh) ** 2)
    halo = np.exp(-(d_sun / (w * 0.30)) ** 2)
    sky = sky * (1 - 0.55 * halo[..., None]) + hexc(p["halo"]) * 0.55 * halo[..., None]
    warm = np.exp(-(d_sun / (w * 0.17)) ** 2)
    sky += hexc(p["glow"]) * (0.55 if not day else 0.16) * warm[..., None]
    # The ring's interior: a faint luminous disc, hazier toward the horizon.
    d_ring = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
    inside = smoothstep(R + 4, R - 30, d_ring) * smoothstep(cy - R, yh, yy) * 0.9
    sky += hexc(p["glow"]) * 0.10 * inside[..., None]
    core = np.exp(-(((xx - cx) / (w * 0.10)) ** 2 + ((yy - yh) / (w * 0.035)) ** 2))
    sky += hexc(p["core"]) * (1.15 if not day else 0.35) * core[..., None]

    if p["stars"] > 0:
        n = int(w * h / 9000)
        sx, sy = rng.integers(0, w, n), rng.integers(0, int(yh * 0.6), n)
        mag = rng.uniform(0.1, 1, n) ** 3 * p["stars"]
        star = np.zeros((h, w))
        star[sy, sx] = mag
        star = blur(star, 0.8) * 6
        star *= 1 - halo
        sky += star[..., None] * hexc("#E9F3EC")

    # 2. The ring: a crisp line of light with two layers of bloom.
    S = w / 1440.0
    ring = np.exp(-((d_ring - R) / (2.1 * S)) ** 2)
    bloom1 = np.exp(-((d_ring - R) / (11.0 * S)) ** 2)
    bloom2 = np.exp(-((d_ring - R) / (46.0 * S)) ** 2)
    ring_mask = (yy < yh + 2).astype(float)
    if day:
        # On a pale morning sky light can't add up; the ring is laid on as warm pigment instead.
        a = np.clip(0.85 * ring + 0.22 * bloom1 + 0.06 * bloom2, 0, 1) * ring_mask
        sky = sky * (1 - a[..., None]) + hexc(p["ring"]) * a[..., None]
    else:
        sky += (hexc(p["ring"]) * (0.95 * ring)[..., None]
                + hexc(p["ring_bloom"]) * (0.30 * bloom1 + 0.13 * bloom2)[..., None]) * ring_mask[..., None]

    scene = sky.copy()
    ring_layer = 0 * ring_mask[..., None] if day else (hexc(p["ring"]) * (0.95 * ring)[..., None] + hexc(p["ring_bloom"]) * (0.30 * bloom1 + 0.13 * bloom2)[..., None]) * ring_mask[..., None]

    # 3. Ridgelines, far to near. Each: a fractal profile, aerial perspective, valley mist, rim light.
    x = np.arange(w, dtype=float)
    occl = np.ones((h, w))
    fog_sun = hexc(p["fog_near_sun"])
    fog_far = hexc(p["fog_far"])
    # Large-scale shapes: high on the left, a soft valley under the ring, rising again on the right.
    def shape(left_h, valley_h, right_h, width):
        # A slope that keeps rising toward each edge (no plateaus), plus a few rounded peaks.
        u = (x - cx) / w
        rise_l = smoothstep(0, width * 1.6, -u) * (0.75 + 0.45 * np.clip(-u, 0, 1) / max(ring_x, 0.3))
        rise_r = smoothstep(0, width * 1.6, u) * (0.75 + 0.45 * np.clip(u, 0, 1) / max(1 - ring_x, 0.3))
        hgt = valley_h + (left_h - valley_h) * rise_l + (right_h - valley_h) * rise_r
        for _ in range(4):
            px = rng.uniform(0, w)
            if abs(px - cx) < w * width * 0.6:
                continue
            hgt += rng.uniform(0.02, 0.07) * np.exp(-((x - px) / (w * rng.uniform(0.05, 0.13))) ** 2)
        return hgt

    layers = [
        # (shape args: left, valley, right, valley width), noise amp, base period, depth 0 far..1 near, tree line
        ((0.10, 0.040, 0.13, 0.22), 0.050, 0.14, 0.00, False),
        ((0.15, 0.028, 0.11, 0.24), 0.055, 0.18, 0.20, False),
        ((0.20, 0.014, 0.085, 0.24), 0.045, 0.22, 0.40, False),
        ((0.24, 0.004, 0.06, 0.24), 0.050, 0.28, 0.60, True),
        ((0.34, -0.01, 0.035, 0.40), 0.040, 0.34, 0.80, True),
        ((0.09, -0.04, 0.012, 0.34), 0.020, 0.45, 1.00, True),
    ]
    for idx, (sh, amp, period, depth, treeline) in enumerate(layers):
        base = shape(*sh)
        # Sharper crests on the far ranges (ridged noise), rounder hills up close.
        ridged = 1 - np.abs(noise1d(w, rng, 5, period * w * 0.6))
        soft = noise1d(w, rng, 5, period * w)
        prof = base + amp * ((1 - depth) * (ridged - 0.5) * 1.6 + depth * soft)
        base_ridge = np.minimum(yh - prof * w * 0.50, yh)
        ridge = base_ridge.copy()
        if treeline and trees:
            tree = np.zeros(w)
            xs = np.cumsum(rng.uniform(2.0, 5.0, int(w / 2)) * (w / 1440))
            xs = xs[xs < w]
            heights = rng.uniform(3, 11, len(xs)) * (0.6 + 0.8 * depth) * (w / 1440)
            # Only on the slopes away from the ring, thinning toward the valley.
            for xi, hi in zip(xs, heights):
                away = min(1.0, abs(xi - cx) / (w * 0.30))
                if rng.uniform() > away ** 2:
                    continue
                lo, hi_x = int(max(0, xi - hi * 0.5)), int(min(w, xi + hi * 0.5 + 1))
                seg = hi - np.abs(x[lo:hi_x] - xi) * 2.2
                tree[lo:hi_x] = np.maximum(tree[lo:hi_x], seg)
            ridge = np.where(base_ridge < yh - 2, base_ridge - tree, base_ridge)
        # Mist and rim light follow the terrain, not the tree tops, so trees don't streak.
        below = yy - base_ridge[None, :]
        land = smoothstep(-0.8, 0.8, yy - ridge[None, :])
        land *= (yy <= yh + 1)
        col = hexc(p["hills"][idx])
        # Aerial perspective: far layers dissolve into the light near the sun.
        near_sun = np.exp(-(((xx - cx) / (w * 0.30)) ** 2))
        fog_col = fog_far * (1 - near_sun[..., None]) + fog_sun * near_sun[..., None]
        haze = (1 - depth) ** 1.4 * 0.62 + 0.03
        # Valley mist: each layer fades into fog toward its base.
        mist = smoothstep(0, w * (0.05 + 0.06 * depth), below) * (0.50 - 0.40 * depth)
        f = np.clip(haze * (0.5 + 0.5 * near_sun) + mist, 0, 0.92)
        layer = col * (1 - f[..., None]) + fog_col * f[..., None]
        # Rim light along the ridge, warm and strongest near the ring.
        rim = np.exp(-np.clip(below, 0, None) / 3.0) * np.exp(-((xx - cx) / (w * 0.22)) ** 2) * (1 - 0.6 * depth)
        layer += hexc(p["glow"]) * (0.55 * rim)[..., None]
        scene = scene * (1 - land[..., None]) + layer * land[..., None]
        occl = occl * (1 - land)

    # 4. Low mist banks along the horizon.
    m = noise2d(h, w, rng, w / 3.5, w / 40) * 0.6 + noise2d(h, w, rng, w / 9, w / 90) * 0.4
    band = np.exp(-((yy - (yh - w * 0.03)) / (w * 0.045)) ** 2)
    mist_amt = np.clip(m - 0.35, 0, 1) * band * (0.55 if not day else 0.6)
    mist_col = fog_far * (1 - np.exp(-((xx - cx) / (w * 0.3)) ** 2))[..., None] + fog_sun * np.exp(-((xx - cx) / (w * 0.3)) ** 2)[..., None]
    scene = scene * (1 - mist_amt[..., None]) + mist_col * mist_amt[..., None]

    # 5. The lake: a rippled mirror of everything above the water line, darker and greener.
    out = scene.copy()
    rows = np.arange(yh, h)
    dist = (rows - yh).astype(float)
    ripple_amp = 1.0 + dist * 0.035
    wobble = noise2d(h - yh, w, rng, w / 30, 3.0) - 0.5
    src_y = np.clip((2 * yh - rows)[:, None] - (dist[:, None] * 0.04 * wobble), 0, yh - 1).astype(int)
    src_x = np.clip(xx[yh:] + wobble * ripple_amp[:, None] * 6, 0, w - 1).astype(int)
    refl = (scene - 0.75 * ring_layer * occl[..., None])[src_y, src_x]
    refl = blur(refl, 1.2, 4.0)
    tint = hexc(p["water_tint"])
    k = p["reflect"] * np.exp(-dist / (h * 0.7))
    water = refl * k[:, None, None] + tint * (1 - k[:, None, None])
    # Glitter: a column of broken light under the ring.
    streak = noise2d(h - yh, w, rng, 22, 1.6)
    streak = np.clip((streak - 0.45) * 2.2, 0, 1)
    col_w = w * (0.025 + dist / (h - yh) * 0.10)
    column = np.exp(-((xx[yh:] - cx) / col_w[:, None]) ** 2) * np.exp(-dist / (h * 0.55))[:, None]
    water += hexc(p["glow"]) * (0.65 * streak * column + 0.18 * column)[..., None]
    # A bright seam right at the water line.
    seam = np.exp(-(dist / 2.0) ** 2)[:, None] * np.exp(-((xx[yh:] - cx) / (w * 0.22)) ** 2)
    water += hexc(p["core"]) * (0.5 * seam)[..., None]
    out[yh:] = water

    # 6. Finish: a quiet top edge for the status bar, a fade into the app background, fine grain.
    top = smoothstep(h * 0.20, 0, yy)
    out = out * (1 - 0.35 * top[..., None]) + hexc(p["sky_top"]) * 0.35 * top[..., None]
    fade = smoothstep(h * fade_start, h * 0.985, yy) ** 1.2
    out = out * (1 - fade[..., None]) + hexc(p["bg"]) * fade[..., None]
    out += (rng.standard_normal((h, w, 1)) * p["grain"])
    return np.clip(out, 0, 1)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    w = int(sys.argv[1]) if len(sys.argv) > 1 else 1440
    h = int(w * 0.92)
    jobs = [
        # Hero art, ring offset right so left-aligned headlines sit in calm sky and water.
        ("first_light_night", "night", 0.67),
        ("first_light_day", "day", 0.67),
        # Centred: the Intent prompt, the Shield and the Focus timer.
        ("first_light_night_centered", "night", 0.50),
        ("first_light_day_centered", "day", 0.50),
    ]
    for name, pal, rx in jobs:
        img = render(pal, w, h, rx)
        write_png(os.path.join(OUT, name + ".png"), img)
        print("wrote", name)
