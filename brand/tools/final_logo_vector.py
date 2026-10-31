#!/usr/bin/env python3
"""Vector approximations of the approved symbol, traced by hand from brand/final/aurora-final-logo.png.

The points below are in master pixels. These vectors are not pixel-identical to the master: its luminous texture
has no vector form. Wherever the logo is shown at full fidelity, use the raster cuts from final_logo.py.
"""
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
FINAL = os.path.join(ROOT, "brand", "final")
RES = os.path.join(ROOT, "app", "src", "main", "res", "drawable")

# Every curve of the symbol, read off the master along its bright edges.
OUTER_LEFT = [(200, 800), (250, 788), (300, 750), (350, 692), (400, 600), (450, 510), (500, 420), (550, 340), (600, 272), (620, 255), (637, 247), (650, 245)]
OUTER_RIGHT = [(650, 245), (666, 249), (683, 262), (700, 283), (750, 393), (800, 507), (840, 597), (870, 660), (900, 730), (950, 793), (1000, 817), (1060, 812)]
ARCH_2 = [(545, 350), (565, 338), (590, 333), (633, 350), (683, 410), (750, 500), (800, 570), (850, 672), (900, 730)]
COUNTER_LEFT = [(553, 558), (600, 487), (625, 448), (650, 418), (670, 410)]
COUNTER_RIGHT = [(670, 410), (690, 418), (703, 455), (720, 503), (742, 570)]
RIGHT_INNER = [(742, 570), (770, 610), (800, 660), (820, 700), (850, 750), (900, 800), (950, 823), (1060, 812)]
LINE_A = [(553, 558), (500, 625), (450, 685), (400, 733), (350, 760), (300, 780), (250, 793), (200, 800)]
LOWER_CROSS = [(1117, 747), (1050, 697), (1000, 663), (950, 638), (900, 617), (850, 601), (800, 597), (750, 598), (700, 605), (650, 617), (600, 635), (550, 662), (500, 700), (450, 744), (400, 778), (350, 800), (300, 814), (250, 817), (200, 800)]
UPPER_CROSS = [(150, 737), (200, 703), (250, 680), (300, 657), (350, 637), (393, 618), (450, 595), (500, 575), (550, 560), (590, 557), (650, 561), (700, 568), (750, 578), (800, 590), (845, 600)]
FOOT_ARCH = [(200, 800), (250, 818), (300, 830), (350, 833), (400, 832), (433, 822), (470, 798), (500, 760), (530, 712), (550, 683), (583, 658), (625, 657), (650, 663), (683, 683), (700, 700), (730, 747), (750, 777), (780, 800), (820, 820), (850, 830), (900, 834), (950, 832), (1000, 827), (1060, 812)]


def spline(points, move=True):
    """A Catmull-Rom curve through the points, as cubic Bezier path data."""
    p = [points[0]] + list(points) + [points[-1]]
    d = f"M{points[0][0]:g} {points[0][1]:g}" if move else f"L{points[0][0]:g} {points[0][1]:g}"
    for i in range(1, len(p) - 2):
        c1 = (p[i][0] + (p[i + 1][0] - p[i - 1][0]) / 6, p[i][1] + (p[i + 1][1] - p[i - 1][1]) / 6)
        c2 = (p[i + 1][0] - (p[i + 2][0] - p[i][0]) / 6, p[i + 1][1] - (p[i + 2][1] - p[i][1]) / 6)
        d += f"C{c1[0]:.1f} {c1[1]:.1f} {c2[0]:.1f} {c2[1]:.1f} {p[i + 1][0]:g} {p[i + 1][1]:g}"
    return d


def closed(*curves):
    return " ".join(spline(c, i == 0) for i, c in enumerate(curves)) + "Z"


def between(points, x0, x1):
    return [p for p in points if x0 <= p[0] <= x1]


def transform(curve, k, ox, oy):
    return [((x - ox) * k, (y - oy) * k) for x, y in curve]


# The shape the small variant, the status bar icon and the themed icon share: the A's silhouette and its counter.
def silhouette(k=1.0, ox=0.0, oy=0.0):
    t = lambda c: transform(c, k, ox, oy)
    counter = closed(t(COUNTER_LEFT), t(COUNTER_RIGHT), t(list(reversed(between(UPPER_CROSS, 553, 745)))))
    return closed(t(OUTER_LEFT), t(OUTER_RIGHT), t(list(reversed(FOOT_ARCH)))) + counter


def wisp(curve, width):
    """A filled sliver along a curve, thickest in the middle, so a line of light can taper to nothing."""
    n = len(curve) - 1
    top = [(x, y - width * (1 - abs(2 * i / n - 1))) for i, (x, y) in enumerate(curve)]
    bottom = [(x, y + width * (1 - abs(2 * i / n - 1))) for i, (x, y) in enumerate(curve)]
    return closed(top, list(reversed(bottom)))


# Colours sampled from the master along the symbol, emerald at the left to warm gold at the right.
SPECTRUM = [(0.0, "#10A98A"), (0.28, "#2DD8A5"), (0.50, "#A9E8B4"), (0.68, "#FBEBB0"), (0.85, "#F7C873"), (1.0, "#D9A04E")]


def stops(alpha=None):
    a = "" if alpha is None else f' stop-opacity="{alpha}"'
    return "".join(f'<stop offset="{o}" stop-color="{c}"{a}/>' for o, c in SPECTRUM)


def full_svg():
    gradient = f'<linearGradient id="spectrum" gradientUnits="userSpaceOnUse" x1="200" y1="0" x2="1060" y2="0">{stops()}</linearGradient>'
    fade = lambda i, a, b: f'<linearGradient id="{i}" gradientUnits="userSpaceOnUse" x1="0" y1="{a[0]}" x2="0" y2="{a[1]}"><stop offset="0" stop-color="#fff" stop-opacity="{b[0]}"/><stop offset="1" stop-color="#fff" stop-opacity="{b[1]}"/></linearGradient>'
    defs = (gradient + fade("lift", (560, 830), (0.0, 0.55))
            + '<radialGradient id="flare"><stop offset="0" stop-color="#FFF6D6" stop-opacity="0.95"/><stop offset="0.12" stop-color="#FFD98A" stop-opacity="0.5"/><stop offset="0.45" stop-color="#F7B85A" stop-opacity="0.12"/><stop offset="1" stop-color="#F7B85A" stop-opacity="0"/></radialGradient>'
            + '<filter id="glow" x="-20%" y="-20%" width="140%" height="140%" color-interpolation-filters="sRGB"><feGaussianBlur stdDeviation="14"/></filter>'
            + '<filter id="edgeGlow" x="-20%" y="-20%" width="140%" height="140%" color-interpolation-filters="sRGB"><feGaussianBlur stdDeviation="3.5"/></filter>'
            + '<mask id="liftMask" maskUnits="userSpaceOnUse" x="0" y="0" width="1254" height="1254"><rect width="1254" height="1254" fill="url(#lift)"/></mask>')
    main = closed(OUTER_LEFT, OUTER_RIGHT, list(reversed(RIGHT_INNER)), list(reversed(COUNTER_RIGHT)), list(reversed(COUNTER_LEFT)), LINE_A)
    crown = closed(between(OUTER_LEFT, 545, 650), OUTER_RIGHT[:10], list(reversed(ARCH_2)))
    foot = closed(FOOT_ARCH, list(reversed(RIGHT_INNER[1:])), between(LOWER_CROSS, 200, 750))
    veil = closed(between(LOWER_CROSS, 850, 1117), [(1117, 747), (1000, 817), (950, 793), (900, 730), (870, 660), (850, 615)])
    middle = closed(LINE_A, list(reversed(between(LOWER_CROSS, 200, 550))), [(550, 662), (553, 558)])
    bar = closed(between(UPPER_CROSS, 393, 845), between(LOWER_CROSS, 450, 845), [(450, 744), (393, 618)])
    edges = [OUTER_LEFT, OUTER_RIGHT, ARCH_2, COUNTER_LEFT, COUNTER_RIGHT, RIGHT_INNER, LINE_A, FOOT_ARCH]
    edge_d = " ".join(spline(c) for c in edges)
    wisps = wisp(UPPER_CROSS, 2.4) + wisp(LOWER_CROSS, 3.0)
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="142 190 980 672" width="980" height="672">
<title>Aurora symbol (vector approximation of brand/final/aurora-final-logo.png; not pixel-identical)</title>
<defs>{defs}</defs>
<g id="glow-layer" filter="url(#glow)" opacity="0.5"><path d="{main}" fill="url(#spectrum)"/><path d="{foot}" fill="url(#spectrum)"/></g>
<g id="ribbons" fill="url(#spectrum)">
<path id="veil" d="{veil}" opacity="0.05"/>
<path id="ribbon-main" d="{main}" opacity="0.30"/>
<path id="ribbon-foot" d="{foot}" opacity="0.30"/>
<path id="ribbon-middle" d="{middle}" opacity="0.30"/>
<path id="ribbon-crossbar" d="{bar}" opacity="0.18"/>
<path id="ribbon-crown" d="{crown}" opacity="0.40"/>
<g id="ribbon-lift" mask="url(#liftMask)"><path d="{foot}" opacity="0.6"/></g>
</g>
<path id="edge-glow" d="{edge_d}" fill="none" stroke="url(#spectrum)" stroke-width="5" opacity="0.75" filter="url(#edgeGlow)"/>
<path id="edges" d="{edge_d}" fill="none" stroke="url(#spectrum)" stroke-width="1.8" stroke-linecap="round"/>
<ellipse id="crossing-flare" cx="835" cy="597" rx="170" ry="110" fill="url(#flare)"/>
<g id="crossbar-wisps"><path d="{wisps}" fill="url(#spectrum)" filter="url(#edgeGlow)"/><path d="{wisps}" fill="#FFFBE6" opacity="0.9"/></g>
</svg>
'''


def small_svg():
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="190 235 880 610" width="880" height="610">
<title>Aurora symbol, small-size variant. A secondary derivative for very small sizes; not the official logo.</title>
<defs><linearGradient id="spectrum" gradientUnits="userSpaceOnUse" x1="200" y1="0" x2="1060" y2="0">{stops()}</linearGradient></defs>
<path fill-rule="evenodd" fill="url(#spectrum)" d="{silhouette()}"/>
</svg>
'''


def android_vector(name, side_dp, port, content_w, ox, oy, fill):
    k = port * content_w / 860 / port
    d = silhouette(k, ox, oy)
    open(os.path.join(RES, name + ".xml"), "w").write(
        f'<!-- Generated by brand/tools/final_logo_vector.py: the silhouette of the approved symbol. -->\n'
        f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{side_dp}dp" android:height="{side_dp}dp" android:viewportWidth="{port}" android:viewportHeight="{port}">\n'
        f'    <path android:fillType="evenOdd" android:fillColor="{fill}" android:pathData="{d}" />\n</vector>\n')


def main():
    open(os.path.join(FINAL, "aurora-symbol.svg"), "w").write(full_svg())
    open(os.path.join(FINAL, "aurora-symbol-small.svg"), "w").write(small_svg())
    open(os.path.join(FINAL, "aurora-symbol-mono.svg"), "w").write(small_svg().replace('fill="url(#spectrum)"', 'fill="#FFFFFF"').replace("small-size variant", "one-colour variant"))
    # The master's silhouette spans x 200-1060, y 245-834: centre (630, 540), and its width sets the scale.
    k_mono = 58 / 860
    android_vector("ic_launcher_monochrome", 108, 108, 58, 630 - 54 / k_mono, 540 - 54 / k_mono, "#FF000000")
    k_stat = 22 / 860
    android_vector("ic_stat_aurora", 24, 24, 22, 630 - 12 / k_stat, 540 - 12 / k_stat, "#FFFFFFFF")
    # The widget's 18dp mark: the small variant in the master's spectrum, too small for its translucency to read.
    colour = "".join(f'<item android:offset="{o}" android:color="#FF{c[1:]}" />' for o, c in SPECTRUM)
    d = silhouette(k_stat, 630 - 12 / k_stat, 540 - 12 / k_stat)
    open(os.path.join(RES, "ic_mark.xml"), "w").write(
        '<!-- Generated by brand/tools/final_logo_vector.py: the small-size variant of the approved symbol. -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n'
        f'    <path android:fillType="evenOdd" android:pathData="{d}"><aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="1" android:startY="0" android:endX="23" android:endY="0">{colour}</gradient></aapt:attr></path>\n</vector>\n')


if __name__ == "__main__":
    main()
