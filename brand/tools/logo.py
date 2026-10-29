"""
Aurora logo masters: the First Light symbol, the outlined wordmark and the lockups, as SVG.

The symbol is defined once here on a 24-unit grid; the Compose mark (ui/components/Brand.kt) and the
launcher-icon vectors use the same numbers. The wordmark outlines come straight from the bundled Manrope
font (default instance), so no font is needed to display the SVG.

    python3 -I brand/tools/logo.py
"""
import os
import struct

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..")
OUT = os.path.join(HERE, "..", "logo")
FONT = os.path.join(ROOT, "app", "src", "main", "res", "font", "manrope.ttf")

# Construction, 24-unit grid. A semicircular ring resting on the horizon, a point of light above it.
ARC_C = (12.0, 19.2)
ARC_R = 7.5
STROKE = 3.0
DOT_C = (12.0, 6.2)
DOT_R = 2.0

EMERALD_A, EMERALD_B, SUN = "#2FB883", "#8BE8BF", "#F3C77D"
NIGHT, PAPER, INK = "#05100D", "#F5F1E8", "#0B1411"


def symbol_paths(s=1.0, ox=0.0, oy=0.0, mono=None, grad_id="fl"):
    cx, cy = ARC_C[0] * s + ox, ARC_C[1] * s + oy
    r, sw = ARC_R * s, STROKE * s
    arc = f'M{cx - r:.2f},{cy:.2f} A{r:.2f},{r:.2f} 0 0 1 {cx + r:.2f},{cy:.2f}'
    dx, dy, dr = DOT_C[0] * s + ox, DOT_C[1] * s + oy, DOT_R * s
    stroke = mono or f"url(#{grad_id})"
    dot = mono or SUN
    defs = "" if mono else (
        f'<linearGradient id="{grad_id}" gradientUnits="userSpaceOnUse" x1="{cx - r:.2f}" y1="{cy:.2f}" x2="{cx + r:.2f}" y2="{cy - r:.2f}">'
        f'<stop offset="0" stop-color="{EMERALD_A}"/><stop offset="1" stop-color="{EMERALD_B}"/></linearGradient>'
    )
    body = (f'<path d="{arc}" fill="none" stroke="{stroke}" stroke-width="{sw:.2f}" stroke-linecap="butt"/>'
            f'<circle cx="{dx:.2f}" cy="{dy:.2f}" r="{dr:.2f}" fill="{dot}"/>')
    return defs, body


# Manrope's default instance is its lightest weight; the wordmark is set at ~Medium (500), reached by
# expanding the outline with a round-joined stroke of this many units per 72 px of cap height.
WEIGHT_STROKE = 3.4


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
    sc = cap_px / (font.upm * 0.72)  # Manrope cap height is ~0.72 em
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


if __name__ == "__main__":
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
        path, wpx = wordmark(font, "Aurora", 0, 72, 72, tracking=0.02)
        save(name, svg(round(wpx) + 6, 96, f'<g transform="translate(2,2)">{ink(path, fill)}</g>'))

    # Horizontal lockup: symbol at cap height x1.45, a gap of one stroke width x3.
    for name, fill, bg in (("aurora-lockup-on-night.svg", "#F1F6F3", NIGHT), ("aurora-lockup-on-paper.svg", INK, PAPER)):
        cap = 72
        s = cap * 1.45 / 24
        d, b = symbol_paths(s, 40, 24, grad_id="lk")
        path, wpx = wordmark(font, "Aurora", 40 + 24 * s + 18, 24 + 19.2 * s, cap, tracking=0.02)
        W = int(40 + 24 * s + 18 + wpx + 40)
        save(name, svg(W, int(24 * s + 48), b + ink(path, fill), d, bg))

    # App-icon preview: the launcher composition at 512px (mirrors res/drawable/ic_launcher_*.xml).
    s = 512 / 108
    glow = ('<radialGradient id="gl" cx="0.5" cy="0.66" r="0.5"><stop offset="0" stop-color="#F3C77D" stop-opacity="0.30"/>'
            '<stop offset="0.45" stop-color="#3FC495" stop-opacity="0.16"/><stop offset="1" stop-color="#3FC495" stop-opacity="0"/></radialGradient>'
            '<linearGradient id="sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#031110"/><stop offset="1" stop-color="#0B2A1F"/></linearGradient>')
    d, b = symbol_paths(2.4 * s, (54 - 12 * 2.4) * s, (54 - 12 * 2.4) * s, grad_id="ic")
    body = f'<rect width="512" height="512" rx="112" fill="url(#sky)"/><rect width="512" height="512" rx="112" fill="url(#gl)"/>' + b
    save("aurora-app-icon-512.svg", svg(512, 512, body, glow + d))
