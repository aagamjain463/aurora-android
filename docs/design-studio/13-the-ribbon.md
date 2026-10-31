# 13: The Ribbon

30 October 2026. The founder pointed at real footage of the aurora over Alaska (Nature Relaxation Films,
youtube.com/watch?v=T75IKSXVXlc), said "this is what our Aurora actually looks like", and asked for the brand, logo,
backdrop and animations to be rebuilt around it.

The footage is reference only and is copyrighted: no frame of it ships. Tones and behaviour were measured from it, and
everything in the app is generated.

## What the footage shows

- **One ribbon, not a wash.** A long sinuous band crosses the frame on a diagonal, often with one fainter band above it.
  It folds back on itself in kinks and S-curls.
- **Saturated green.** The band is hue 114 to 120, about `#2FD420` at its core. It goes whitish only at the hottest
  knot. Teal appears only in thin curtains, hue 141 to 173.
- **A black sky.** The top of the sky is aubergine black, `#0C0913`. It goes green-black, `#12211B` to `#1E3E29`, only low
  down where the light pools. A thin bright band often sits on the far horizon.
- **Short soft rays** rise from the band, and they fade into near nothing.
- **Land holds the frame.** A near-black massif on one side, moonlit snow ridges on the other, a jagged range in the V.
- **Pin-sharp stars**, tiny and many.
- **Motion:** a wave travels along the band, kinks migrate, and bright knots wander. Rays appear and fade in place.

## What changed

| Before | Now |
|---|---|
| Two level curtains, mint to teal to violet, at low contrast | `RibbonSource`: a winding ribbon and a fainter one, folds, knots, rays, in oxygen green |
| Blue-black night | Aubergine black, green-black only in surfaces |
| Pine shore, level ranges | Massif, snow ridges, far range, frozen lake, generated from a lit height field |
| The Arcs logo, green to violet | The Ribbon: a dome that is also an A, a ribbon through it. Real cut at the crossing, so it survives one colour |
| Pastel green-to-violet primary action | Ribbon sweep, with a knot of light wandering along it every 9 seconds (`ribbonKnot`) |
| Opening: thread, strands, curtains parting | A point catches and races along the ribbon, rays rise, the ribbon lifts the night off the app |
| Splash: mark fades in | The arch's light climbs from the ground, then the ribbon runs through it |
| Sweep: canvas paths | The same ribbon shader, climbing the page |

Founder-approved copy is unchanged, on purpose.

## How it stays one thing

- `brand/tools/aurora_art.py` `ribbons()` and `RibbonSource` in `Sky.kt` are the same maths, line for line. Change one,
  change both, then run `brand/tools/export.sh`. The still for Android 12 and below is the shader's picture at
  `StillMoment`, and that moment was chosen by rendering many.
- `brand/tools/logo.py` is the only source of the symbol. It writes every icon, the splash and `MarkPaths.kt`.
- Tokens stay in `brand/tokens/aurora.tokens.json`. `DesignTokensTest` enforces it.

## Calmer, the same day

The founder saw the first version on the phone and found it too flashy and too concentrated: "spread that light", but
"don't spread it completely". The footage has this mood too (around 1:28:20): broad soft green, no hot core, peak about
`#2E8625`. So the sky now targets that frame.

- The band is about three times wider, with a soft lower edge.
- Brightness is capped (`a` at most 0.70), so it never burns white, and it never goes white.
- Rays are broad and low in contrast, and there is real dark sky between the bands.
- It moved about half as fast (later reversed: on 2026-10-31 the founder asked for motion visible to the naked eye, so `SkyPace` is now about 15 times faster, brightness unchanged). The waking surge and session flare are about half as strong.
- The opening and the session sweep use the same softer light.

Then one notch back: "increase the brightness by a little bit", "spread it a little less". The cap went from 0.60 to
0.74 (0.84 after the glass pass) and the band narrowed by about a fifth. The opening also grew: the splash mark now holds 0.5s once drawn, the mark
stays on screen above the name, and the opening runs longer than the first 2.8s.

## Glass

The founder asked for a glassy feel across all the UI, and the sky one more notch brighter (cap 0.84). Glass needs light
to catch, so `PageGlow` carries a faint glow down every page. See "Glass" in the brand bible.

## Logo, left of the sky

The founder asked for a far more premium, branded logo. The thin tapered A became a solid one: a dome whose crossbar is a
ribbon of light cut through it. The founder also saw the sky's light only in the middle and right. The cause was the
geometry: the ribbon sloped down to the left and passed behind the massif. The ribbon now runs nearly level, the massif is
lower, and a soft bank of light keeps the left of the sky lit at every moment. The opening runs 5.2s.

Then: the opening's light went back to the narrower step (it keeps the longer 5.2s). The sky dimmed (cap 0.70), and a low arc
with its own rays lights the lower right, so the light reaches the whole sky.

## Type

The founder asked for the best possible premium type. Six pairings were rendered on the real sky and interface. Cormorant
Garamond with Geist won: fine strokes that feel like light, against a crisp, quiet interface. Cormorant is set larger than
Newsreader was, because its letters are small for their size.
