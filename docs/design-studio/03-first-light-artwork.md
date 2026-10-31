# 03 — First Light artwork

## Assets

| File | What | Where used |
|---|---|---|
| `app/src/main/res/drawable-nodpi/first_light_night.webp` (1440×1324, 37 KB) | Night: misted emerald ridges, a thin gold ring, a golden column on the lake | Dark-theme hero art: Home, Track, Shield library, Plus, Focus, onboarding |
| `app/src/main/res/drawable-nodpi/first_light_day.webp` (1440×1324, 28 KB) | The same place in morning mist: amber ring, bright water | Light-theme hero art |
| `.../first_light_{night,day}_centered.webp` | The same paintings with the ring centred | Shield and Intent stage, the Focus ring window |
| `brand/artwork/first_light_{day,night}{,_centered}.png` | Procedural masters from `brand/tools/first_light.py` | Source for re-export |
| `brand/artwork/first_light_cinematic_master.png` | The earlier ImageGen night art | Retired from the app (29 Oct 2026); kept for history |

Both shipped images are art only: no text, controls, numbers or logos. Every label, chart and button is
native Compose on top.

## One painting, night and day (29 October 2026)

The app used to ship two styles: a photoreal ImageGen alpine scene at night and a procedural painting by
day. The Brand Bible forbids a second illustration style, and the busy treeline competed with headlines.
Both themes now use the procedural painting, which is also closest to approved reference `01-today.png`
(low hills, a lake, a gold ring):

- Night palette warmed: gold ring (`#FFDDA0`, bloom `#EE9F48`), softer emerald halo, no neon green.
- Horizon raised from 0.43 to 0.35 of the image, ring radius 0.205: eyebrows and headlines now sit on calm
  water below the horizon, never on the water line.
- The water line is blurred over ±4px, so it never draws a hard rule under type.

To bring the cinematic night back, restore `first_light_cinematic.webp` from git history and point
`firstLight()` in `FirstLight.kt` at it for `dark && !centered`.

## Provenance

- **Procedural (shipped):** `brand/tools/first_light.py`: fractal-noise ridgelines, aerial perspective,
  valley mist, a luminous ring and a rippled reflection. Deterministic and fully original. Re-export with
  `brand/tools/export.sh` (needs numpy and cwebp; Blender's Python has numpy).
- **Cinematic (retired):** generated with the built-in ImageGen tool on 29 October 2026; prompt in
  `docs/BRAND_SYSTEM.md`.

## Intensity tiers (`ArtTier` in `ui/components/FirstLight.kt`)

| Tier | Height | Screens | Treatment |
|---|---|---|---|
| Immersive | 440dp | Today, Focus, Shield and Intent, onboarding welcome and finish | Full art; ring right of centre |
| Balanced | 360dp | Shield and Intent libraries, Track, Plus | Cropped low so the headline sits on the lake |
| None | — | Settings, setup flows, app picker, item, web, dialogs | Palette, hairlines and mark only |

`FirstLightBackdrop` layers, from back to front:

1. The art, `ContentScale.Crop`, aligned `BiasAlignment(0.42, …)` so the ring sits right of centre.
1a. The living sky (`AuroraSky`): an AGSL shader on Android 13+, placed with the same crop and masked by
   `first_light_sky_*.webp`, the painting's open sky written by `first_light.py` (behind every ridge and
   the horizon mist, kept off the ring's core). Screen-blended at night, a veil by day. Earlier Android
   versions keep the still painting.
2. A horizontal shade (background colour at 62% → 0) on the reading side, so left-aligned type stays
   legible over mountains.
3. A vertical fade: light scrim under the status bar and header, then down to solid background, so the
   art falls into the page with no edge.

## Behaviour

- Parallax: the art drifts at half the scroll speed and fades as the hero leaves. With reduced motion it
  moves with the content.
- Theme: night art in dark, day art in light, both under the same scrims built from theme colours.
- Cropping holds from 360dp to tablet widths because the art is wider than every phone viewport. The ring
  is centred in the source, and the bias keeps it in frame.
- Performance: two WebP decodes, 135 KB in total, `nodpi` so they aren't scaled per density. The sky
  masks are 1.4 KB each. The shader only redraws while the sky moves, at 30 fps, and stops when the screen
  pauses or the hero scrolls away.

## Aurora Light

`AuroraLight` (in `ui/components/Light.kt`) is the data visual drawn on Canvas: a horizon rim and a
rising point whose glow and height come only from recorded progress. It appears in the Today level card
and the Insights progress card, never as decoration.

## LightRing

The Focus timer (`LightRing`) is a circular window onto the same art inside a ring of light, with minute
ticks, an emerald-to-sun sweep and a glowing head. It is the art's ring, repurposed as time.
