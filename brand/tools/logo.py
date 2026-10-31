"""
Aurora logo masters: the Ribbon symbol, the outlined wordmark and the lockups, as SVG; the launcher, themed, status
and splash icons, as Android vectors; and the path data the Compose mark draws (ui/components/MarkPaths.kt). The symbol
is defined once, here. The wordmark outlines come straight from the bundled Cormorant font (default instance), so no
font is needed to display the SVG. The font's default instance is its lightest, so WEIGHT_STROKE brings it up to Medium.

    python3 -I brand/tools/logo.py
"""
import math
import os
import struct

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..")
OUT = os.path.join(HERE, "..", "logo")
FONT = os.path.join(ROOT, "app", "src", "main", "res", "font", "cormorant.ttf")

NIGHT, PAPER, INK = "#050609", "#F4F7F0", "#0B1411"

# The Ribbon, on a 100-unit grid: a solid dome of sky that is also an A. Its crossbar is a ribbon of light cut clean
# through it, widening as it travels, and rays rise above the ribbon in the full-colour mark. The gap is real space, so
# the symbol survives one colour at 16dp.
TOP, BOTTOM, WIDTH = 6.0, 94.0, 40.0
GAP_LEFT, GAP_RIGHT = 1.7, 3.5
COUNTER_APEX, COUNTER_WIDTH = 51.0, 14.5
CORNER = 2.4
# Same as Palette in Color.kt: the light falls from starlight through the band to the body.
DOME = [(0, "F4FFF0"), (0.34, "C9FFB4"), (1, "4BEA55")]
LEGS = [(0, "4BEA55"), (1, "17A850")]
DOME_Y, LEGS_Y = (6, 62), (54, 94)


def half(y):
    return WIDTH * ((y - TOP) / (BOTTOM - TOP)) ** 0.47


def ribbon_y(x, side=0):
    """The ribbon's centre, or its upper (side -1) or lower (side 1) edge."""
    t = (x - 10) / 80
    return 58.5 - 0.10 * (x - 50) + 6.2 * math.sin(t * 2 * math.pi) + side * (GAP_LEFT + (GAP_RIGHT - GAP_LEFT) * t)


def meets(sign, side, width_at):
    x = 50 + sign * 25
    for _ in range(60):
        x = 50 + sign * width_at(ribbon_y(x, side))
    return x, ribbon_y(x, side)


def counter(y):
    return COUNTER_WIDTH * (max(y - COUNTER_APEX, 0) / (BOTTOM - COUNTER_APEX)) ** 0.70


def span(a, b, n=16):
    return [a + (b - a) * i / n for i in range(n + 1)]


def rounded(poly, r=CORNER):
    """Softens the corners of a polygon, leaving its curves alone."""
    out, n = [], len(poly)
    for i, p in enumerate(poly):
        a, b = poly[i - 1], poly[(i + 1) % n]
        u, v = (a[0] - p[0], a[1] - p[1]), (b[0] - p[0], b[1] - p[1])
        lu, lv = math.hypot(*u), math.hypot(*v)
        if lu < 1e-6 or lv < 1e-6:
            continue
        if (u[0] * v[0] + u[1] * v[1]) / (lu * lv) < -0.82:
            out.append(p)
            continue
        ru, rv = min(r, lu / 2), min(r, lv / 2)
        s, e = (p[0] + u[0] / lu * ru, p[1] + u[1] / lu * ru), (p[0] + v[0] / lv * rv, p[1] + v[1] / lv * rv)
        for t in (0, 0.25, 0.5, 0.75, 1):
            q = 1 - t
            out.append((q * q * s[0] + 2 * q * t * p[0] + t * t * e[0], q * q * s[1] + 2 * q * t * p[1] + t * t * e[1]))
    return out


def polygons():
    """The symbol as closed polygons: the dome, then the two legs."""
    xl, yl = meets(-1, -1, half)
    xr, yr = meets(1, -1, half)
    # Fine steps at the crown, so the top of the dome is truly round.
    crown = [TOP + (yr - TOP) * (i / 28) ** 2 for i in range(29)]
    crown_l = [TOP + (yl - TOP) * (i / 28) ** 2 for i in range(29)]
    dome = [(50 - half(y), y) for y in reversed(crown_l)] + [(50 + half(y), y) for y in crown[1:]] + [(x, ribbon_y(x, -1)) for x in span(xr, xl, 28)[1:-1]]
    outl, yol = meets(-1, 1, half)
    outr, yor = meets(1, 1, half)
    inl, yil = meets(-1, 1, counter)
    inr, yir = meets(1, 1, counter)
    left = [(x, ribbon_y(x, 1)) for x in span(outl, inl, 12)] + [(50 - counter(y), y) for y in span(yil, BOTTOM)[1:]] + [(50 - half(y), y) for y in span(BOTTOM, yol)[:-1]]
    right = [(x, ribbon_y(x, 1)) for x in span(inr, outr, 12)] + [(50 + half(y), y) for y in span(yor, BOTTOM)[1:]] + [(50 + counter(y), y) for y in span(BOTTOM, yir)[:-1]]
    return [rounded(dome), rounded(left), rounded(right)]


def rays():
    """Rays of the aurora rising inside the dome, from the ribbon up: (polygon, full-colour mark only)."""
    out = []
    for x, tall, w in ((33, 17, 6.0), (44.5, 34, 7.0), (56.5, 33, 7.0), (67.5, 20, 6.0)):
        y = ribbon_y(x, -1) - 1.6
        out.append([(x - w / 2, y), (x - w * 0.30, y - tall), (x + w * 0.30, y - tall), (x + w / 2, y)])
    return out


def poly_path(poly, s=1.0, ox=0.0, oy=0.0):
    return "M" + " ".join(f"{x * s + ox:.2f},{y * s + oy:.2f}" for x, y in poly) + "z"


def svg_gradient(gid, stops, ys, k, oy):
    items = "".join(f'<stop offset="{o}" stop-color="#{c}"/>' for o, c in stops)
    return f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="0" y1="{ys[0] * k + oy:.2f}" x2="0" y2="{ys[1] * k + oy:.2f}">{items}</linearGradient>'


def symbol_paths(s=1.0, ox=0.0, oy=0.0, mono=None, grad_id="fl"):
    """[s] is scale per 24-unit symbol box, kept for the lockups."""
    k = s * 0.24
    polys = polygons()
    if mono:
        return "", "".join(f'<path d="{poly_path(q, k, ox, oy)}" fill="{mono}"/>' for q in polys)
    defs = svg_gradient(grad_id + "d", DOME, DOME_Y, k, oy) + svg_gradient(grad_id + "l", LEGS, LEGS_Y, k, oy)
    defs += f'<linearGradient id="{grad_id}r" gradientUnits="userSpaceOnUse" x1="0" y1="{20 * k + oy:.2f}" x2="0" y2="{60 * k + oy:.2f}"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0"/><stop offset="1" stop-color="#0B5A2A" stop-opacity="0.20"/></linearGradient>'
    body = f'<path d="{poly_path(polys[0], k, ox, oy)}" fill="url(#{grad_id}d)"/>'
    body += "".join(f'<path d="{poly_path(q, k, ox, oy)}" fill="url(#{grad_id}r)"/>' for q in rays())
    body += "".join(f'<path d="{poly_path(q, k, ox, oy)}" fill="url(#{grad_id}l)"/>' for q in polys[1:])
    return defs, body


# Cormorant's default instance is Light; this stroke brings the outlines up to the wordmark's Medium.
WEIGHT_STROKE = 1.6


def ink(path, fill, cap=72):
    return (f'<path d="{path}" fill="{fill}" stroke="{fill}" stroke-width="{WEIGHT_STROKE * cap / 72:.2f}" '
            f'stroke-linejoin="round"/>')


# --- Minimal TrueType reader: cmap 4, loca/glyf simple glyphs, hmtx. Enough for a wordmark. ---
class Font:
    def __init__(self, path):
        self.d = open(path, "rb").read()
        n = struct.unpack(">H", self.d[4:6])[0]
        self.t = {}
        for i in range(n):
            tag, _, off, ln = struct.unpack(">4sIII", self.d[12 + 16 * i:28 + 16 * i])
            self.t[tag.decode()] = (off, ln)
        head = self.t["head"][0]
        self.upm = struct.unpack(">H", self.d[head + 18:head + 20])[0]
        self.long_loca = struct.unpack(">h", self.d[head + 50:head + 52])[0] == 1
        hhea = self.t["hhea"][0]
        self.n_hm = struct.unpack(">H", self.d[hhea + 34:hhea + 36])[0]
        self._cmap()

    def _cmap(self):
        off = self.t["cmap"][0]
        n = struct.unpack(">H", self.d[off + 2:off + 4])[0]
        for i in range(n):
            pid, eid, sub = struct.unpack(">HHI", self.d[off + 4 + 8 * i:off + 12 + 8 * i])
            so = off + sub
            if struct.unpack(">H", self.d[so:so + 2])[0] == 4:
                break
        segx2 = struct.unpack(">H", self.d[so + 6:so + 8])[0]
        seg = segx2 // 2
        ends = struct.unpack(f">{seg}H", self.d[so + 14:so + 14 + segx2])
        starts = struct.unpack(f">{seg}H", self.d[so + 16 + segx2:so + 16 + 2 * segx2])
        deltas = struct.unpack(f">{seg}h", self.d[so + 16 + 2 * segx2:so + 16 + 3 * segx2])
        ro_off = so + 16 + 3 * segx2
        ros = struct.unpack(f">{seg}H", self.d[ro_off:ro_off + segx2])
        self.cmap = {}
        for k in range(seg):
            for c in range(starts[k], ends[k] + 1):
                if c == 0xFFFF:
                    continue
                if ros[k] == 0:
                    g = (c + deltas[k]) & 0xFFFF
                else:
                    a = ro_off + 2 * k + ros[k] + 2 * (c - starts[k])
                    g = struct.unpack(">H", self.d[a:a + 2])[0]
                    g = (g + deltas[k]) & 0xFFFF if g else 0
                self.cmap[c] = g

    def advance(self, g):
        off = self.t["hmtx"][0]
        i = min(g, self.n_hm - 1)
        return struct.unpack(">H", self.d[off + 4 * i:off + 4 * i + 2])[0]

    def contours(self, g):
        lo = self.t["loca"][0]
        if self.long_loca:
            a, b = struct.unpack(">II", self.d[lo + 4 * g:lo + 4 * g + 8])
        else:
            a, b = (x * 2 for x in struct.unpack(">HH", self.d[lo + 2 * g:lo + 2 * g + 4]))
        if a == b:
            return []
        p = self.t["glyf"][0] + a
        nc = struct.unpack(">h", self.d[p:p + 2])[0]
        assert nc >= 0, "composite glyphs not needed for the wordmark"
        ends = struct.unpack(f">{nc}H", self.d[p + 10:p + 10 + 2 * nc])
        npts = ends[-1] + 1
        q = p + 10 + 2 * nc
        il = struct.unpack(">H", self.d[q:q + 2])[0]
        q += 2 + il
        flags = []
        while len(flags) < npts:
            f = self.d[q]
            q += 1
            flags.append(f)
            if f & 8:
                flags.extend([f] * self.d[q])
                q += 1
        coords = []
        for short, same in ((2, 16), (4, 32)):
            v, out = 0, []
            for f in flags[:npts]:
                if f & short:
                    dv = self.d[q]
                    q += 1
                    v += dv if f & same else -dv
                elif not f & same:
                    v += struct.unpack(">h", self.d[q:q + 2])[0]
                    q += 2
                out.append(v)
            coords.append(out)
        pts = [(x, y, bool(f & 1)) for x, y, f in zip(coords[0], coords[1], flags)]
        res, s = [], 0
        for e in ends:
            res.append(pts[s:e + 1])
            s = e + 1
        return res


def glyph_path(contours, ox, oy, sc):
    """Quadratic TrueType contours to an SVG path, y flipped."""
    out = []
    for c in contours:
        pts = [(ox + x * sc, oy - y * sc, on) for x, y, on in c]
        if not pts[0][2]:
            last = pts[-1]
            start = last if last[2] else ((pts[0][0] + last[0]) / 2, (pts[0][1] + last[1]) / 2, True)
            pts = [start] + pts
        out.append(f"M{pts[0][0]:.2f},{pts[0][1]:.2f}")
        i, n = 1, len(pts)
        while i <= n:
            p = pts[i % n]
            if p[2]:
                out.append(f"L{p[0]:.2f},{p[1]:.2f}")
                i += 1
            else:
                nxt = pts[(i + 1) % n]
                end = nxt if nxt[2] else ((p[0] + nxt[0]) / 2, (p[1] + nxt[1]) / 2, True)
                out.append(f"Q{p[0]:.2f},{p[1]:.2f} {end[0]:.2f},{end[1]:.2f}")
                i += 2 if nxt[2] else 1
        out.append("Z")
    return "".join(out)


def wordmark(font, text, x, baseline, cap_px, tracking=-0.01):
    sc = cap_px / (font.upm * 0.63)  # Cormorant cap height is ~0.63 em
    parts, pen = [], x
    for ch in text:
        g = font.cmap[ord(ch)]
        parts.append(glyph_path(font.contours(g), pen, baseline, sc))
        pen += (font.advance(g) + tracking * font.upm) * sc
    return "".join(parts), pen - x


def svg(w, h, body, defs="", bg=None):
    back = f'<rect width="{w}" height="{h}" fill="{bg}"/>' if bg else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}">'
            f'<defs>{defs}</defs>{back}{body}</svg>\n')


def android_icons():
    """The launcher icon, themed icon, status icon, splash and Compose paths, all from polygons()."""
    res = os.path.join(ROOT, "app", "src", "main", "res", "drawable")
    ns = 'xmlns:android="http://schemas.android.com/apk/res/android"'
    aapt = 'xmlns:aapt="http://schemas.android.com/aapt"'
    box = 'android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108"'
    k = 0.60
    o = 54 - 50 * k
    polys = polygons()
    def grad(stops, ys, s_, o_):
        items = "".join(f'<item android:color="#FF{c}" android:offset="{p}" />' for p, c in stops)
        return f'<aapt:attr name="android:fillColor"><gradient android:startX="0" android:startY="{ys[0] * s_ + o_:.2f}" android:endX="0" android:endY="{ys[1] * s_ + o_:.2f}" android:type="linear">{items}</gradient></aapt:attr>'
    def ray_grad(s_, o_):
        return f'<aapt:attr name="android:fillColor"><gradient android:startX="0" android:startY="{20 * s_ + o_:.2f}" android:endX="0" android:endY="{60 * s_ + o_:.2f}" android:type="linear"><item android:color="#00FFFFFF" android:offset="0" /><item android:color="#330B5A2A" android:offset="1" /></gradient></aapt:attr>'
    def full(s_, o_, indent="    "):
        out = f'{indent}<path android:pathData="{poly_path(polys[0], s_, o_, o_)}">{grad(DOME, DOME_Y, s_, o_)}</path>\n'
        out += "".join(f'{indent}<path android:pathData="{poly_path(q, s_, o_, o_)}">{ray_grad(s_, o_)}</path>\n' for q in rays())
        out += "".join(f'{indent}<path android:pathData="{poly_path(q, s_, o_, o_)}">{grad(LEGS, LEGS_Y, s_, o_)}</path>\n' for q in polys[1:])
        return out
    head = '<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by brand/tools/logo.py. The Aurora symbol, the Ribbon. -->\n'
    def write(name, text):
        open(os.path.join(res, name), "w").write(head + text)
    write("ic_launcher_foreground.xml", f'<vector {ns} {aapt} {box}>\n{full(k, o)}</vector>\n')
    write("ic_launcher_monochrome.xml", f'<vector {ns} {box}>\n' + "".join(f'    <path android:fillColor="#FF000000" android:pathData="{poly_path(q, k, o, o)}" />\n' for q in polys) + "</vector>\n")
    small = 'android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"'
    write("ic_stat_aurora.xml", f'<vector {ns} {small}>\n' + "".join(f'    <path android:fillColor="#FFFFFFFF" android:pathData="{poly_path(q, 0.24)}" />\n' for q in polys) + "</vector>\n")
    write("ic_mark.xml", f'<vector {ns} {aapt} {small}>\n{full(0.24, 0)}</vector>\n')
    write("ic_launcher_background.xml", f"""<vector {ns} {aapt} {box}>
    <path android:pathData="M0,0h108v108h-108z"><aapt:attr name="android:fillColor"><gradient android:startX="54" android:startY="0" android:endX="54" android:endY="108" android:type="linear"><item android:color="#FF050609" android:offset="0" /><item android:color="#FF08120F" android:offset="1" /></gradient></aapt:attr></path>
    <path android:pathData="M0,0h108v108h-108z"><aapt:attr name="android:fillColor"><gradient android:centerX="54" android:centerY="50" android:gradientRadius="48" android:type="radial"><item android:color="#8017A850" android:offset="0" /><item android:color="#330B5A2A" android:offset="0.55" /><item android:color="#000B5A2A" android:offset="1" /></gradient></aapt:attr></path>
</vector>
""")
    # The splash: the legs rise from the ground, the dome follows, then the rays come up inside it.
    def rising(a, b):
        return f"M0,{a} L108,{a} L108,108 L0,108 z", f"M0,{b} L108,{b} L108,108 L0,108 z"
    start, end = rising(108, 0)
    fills = "".join(f'                <path android:pathData="{poly_path(q, k, o, o)}">{grad(LEGS, LEGS_Y, k, o)}</path>\n' for q in polys[1:])
    fills += f'                <path android:pathData="{poly_path(polys[0], k, o, o)}">{grad(DOME, DOME_Y, k, o)}</path>\n'
    ray_paths = "".join(f'            <path android:name="ray{i}" android:fillAlpha="0" android:pathData="{poly_path(q, k, o, o)}">{ray_grad(k, o)}</path>\n' for i, q in enumerate(rays()))
    targets = f'    <target android:name="mark"><aapt:attr name="android:animation"><objectAnimator android:duration="760" android:propertyName="pathData" android:valueType="pathType" android:valueFrom="{start}" android:valueTo="{end}" android:interpolator="@android:interpolator/fast_out_slow_in" /></aapt:attr></target>\n'
    targets += "".join(f'    <target android:name="ray{i}"><aapt:attr name="android:animation"><objectAnimator android:duration="300" android:startOffset="{520 + 40 * i}" android:propertyName="fillAlpha" android:valueFrom="0" android:valueTo="1" /></aapt:attr></target>\n' for i in range(len(rays())))
    write("splash_icon_animated.xml", f'<animated-vector {ns} {aapt}>\n    <aapt:attr name="android:drawable">\n        <vector {box}>\n            <group>\n                <clip-path android:name="mark" android:pathData="{start}" />\n{fills}            </group>\n{ray_paths}        </vector>\n    </aapt:attr>\n{targets}</animated-vector>\n')
    kt = os.path.join(ROOT, "app", "src", "main", "java", "app", "aurora", "ui", "components", "MarkPaths.kt")
    names = ", ".join(f'\n    "{poly_path(q)}"' for q in polys)
    ray_names = ", ".join(f'\n    "{poly_path(q)}"' for q in rays())
    open(kt, "w").write(f'package app.aurora.ui.components\n\n// Generated by brand/tools/logo.py. The Ribbon, on a 100-unit grid: the dome, then the two legs.\ninternal val MarkPaths = listOf({names},\n)\n\n// Rays inside the dome, in the full-colour mark only.\ninternal val MarkRays = listOf({ray_names},\n)\n')


if __name__ == "__main__":
    android_icons()
    os.makedirs(OUT, exist_ok=True)
    font = Font(FONT)

    def save(name, text):
        open(os.path.join(OUT, name), "w").write(text)
        print("wrote", name)

    d, b = symbol_paths()
    save("aurora-symbol.svg", svg(24, 24, b, d))
    _, b = symbol_paths(mono="#000000")
    save("aurora-symbol-mono-black.svg", svg(24, 24, b))
    _, b = symbol_paths(mono="#FFFFFF")
    save("aurora-symbol-mono-white.svg", svg(24, 24, b))

    for name, fill, bg in (("aurora-wordmark-light.svg", "#F1F6F3", None), ("aurora-wordmark-dark.svg", INK, None)):
        path, wpx = wordmark(font, "Aurora", 0, 72, 72, tracking=-0.01)
        save(name, svg(round(wpx) + 6, 96, f'<g transform="translate(2,2)">{ink(path, fill)}</g>'))

    # Horizontal lockup: symbol at cap height x1.45, a gap of one stroke width x3.
    for name, fill, bg in (("aurora-lockup-on-night.svg", "#F1F6F3", NIGHT), ("aurora-lockup-on-paper.svg", INK, PAPER)):
        cap = 72
        s = cap * 1.45 / 24
        d, b = symbol_paths(s, 40, 24, grad_id="lk")
        path, wpx = wordmark(font, "Aurora", 40 + 24 * s + 18, 24 + 22.56 * s, cap, tracking=-0.01)
        W = int(40 + 24 * s + 18 + wpx + 40)
        save(name, svg(W, int(24 * s + 48), b + ink(path, fill), d, bg))

    # App-icon preview: the launcher composition at 512px (mirrors res/drawable/ic_launcher_*.xml).
    s = 512 / 108
    glow = ('<radialGradient id="gl" cx="0.5" cy="0.46" r="0.44"><stop offset="0" stop-color="#17A850" stop-opacity="0.50"/>'
            '<stop offset="0.55" stop-color="#0B5A2A" stop-opacity="0.20"/><stop offset="1" stop-color="#0B5A2A" stop-opacity="0"/></radialGradient>'
            '<linearGradient id="sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#050609"/><stop offset="1" stop-color="#08120F"/></linearGradient>')
    d, b = symbol_paths(2.5 * s, 24 * s, 24 * s, grad_id="ic")
    body = f'<rect width="512" height="512" rx="112" fill="url(#sky)"/><rect width="512" height="512" rx="112" fill="url(#gl)"/>' + b
    save("aurora-app-icon-512.svg", svg(512, 512, body, glow + d))
