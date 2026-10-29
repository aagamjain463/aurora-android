# 03 — First Light artwork

## Assets

| File | What | Where used |
|---|---|---|
| `app/src/main/res/drawable-nodpi/first_light_cinematic.webp` (1536×1024, 109 KB) | Night: pine mountains, mist, a thin gold ring over the valley, lake reflection | All dark-theme art: Today, Protect, Insights, Focus, Shield, onboarding, the Focus ring window |
| `app/src/main/res/drawable-nodpi/first_light_day.webp` (1440×1324, 26 KB) | Morning: misted green ridges, amber ring, bright water | All light-theme art |
| `brand/artwork/first_light_cinematic_master.png` | Lossless master of the night art | Source for re-export |
| `brand/artwork/first_light_{day,night}{,_centered}.png` | Procedural masters from `brand/tools/first_light.py` | Day is shipped; night and centred variants are kept as alternates |
| `brand/artwork/webp/` | WebP exports of the unused alternates | Not shipped |

Both shipped images are art only: no text, controls, numbers or logos. Every label, chart and button is
native Compose on top.

## Provenance

- **Night (cinematic):** an original image generated with the built-in ImageGen tool on 29 October
  2026 by an earlier Aurora session. The prompt is recorded in `docs/BRAND_SYSTEM.md` → "Final ImageGen
  prompt". In this session it was only re-encoded (PNG → WebP q84, 2.0 MB → 109 KB). Commercial use follows
  that tool's terms.
- **Day (procedural):** rendered by `brand/tools/first_light.py`: fractal-noise ridgelines, aerial
  perspective, valley mist, a luminous ring and a rippled reflection. Deterministic and fully original.
  Re-export with `brand/tools/export.sh` (needs numpy and cwebp).

The procedural night render (`brand/artwork/first_light_night.png`) was compared with the cinematic one
against the references. It is cleaner but flatter (no forest detail, weaker mist), so the cinematic art
ships for dark mode. The procedural day art ships for light mode, where no cinematic equivalent exists.

## Intensity tiers (`ArtTier` in `ui/components/FirstLight.kt`)

| Tier | Height | Screens | Treatment |
|---|---|---|---|
| Immersive | 440dp | Today, Focus, Shield and Intent, onboarding welcome and finish | Full art; ring right of centre |
| Balanced | 360dp | Protect, Insights | Cropped higher so the headline sits on the lake |
| None | — | Settings, schedules, app picker, item, start, web, Plus, dialogs | Palette, hairlines and mark only |

`FirstLightBackdrop` layers, from back to front:

1. The art, `ContentScale.Crop`, aligned `BiasAlignment(0.42, …)` so the ring sits right of centre.
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
- Performance: two WebP decodes, 135 KB in total, `nodpi` so they aren't scaled per density.

## Aurora Light

`AuroraLight` (in `ui/components/Light.kt`) is the data visual drawn on Canvas: a horizon rim and a
rising point whose glow and height come only from recorded progress. It appears in the Today level card
and the Insights progress card, never as decoration.

## LightRing

The Focus timer (`LightRing`) is a circular window onto the same art inside a ring of light, with minute
ticks, an emerald-to-sun sweep and a glowing head. It is the art's ring, repurposed as time.
