# Aurora material system (2026-11-07)

Solid surfaces communicate stability. Glass communicates contextual depth. Founder direction (same day, second
pass): the glass must be clearly visible, Apple-like: light tint, lens at the rim, lit rim, sheen, soft shadow,
vibrancy. Third pass: every element is glass, cards included. Only the logo is never touched.
Code: `app/src/main/java/app/aurora/ui/components/Glass.kt`. Tokens: `material` in `brand/tokens/aurora.tokens.json`.

## Materials

| Material | Where | Over a backdrop (dark / light) | Fallback |
|---|---|---|---|
| Glass card (`glassCard`, behind `glass` and `auroraSurface`) | Every card, row group, field, chip, icon well, pill, the Shield's tiles | Porcelain 5.5% / White 82% (fields 7.5% / 60%), sheen. Cards have no outline (a chosen card keeps its tint edge); fields, chips, pills, circles keep the lit rim | same (page behind) |
| Glass shine (`glassShine`) | Solid primary fills: primary buttons, Aurora Plus row, chosen chips | gloss on top, shade below, lit rim | same |
| Glass control (`glassControl`) | Secondary buttons, segmented tracks, round icon buttons (back, settings, card menus) | Porcelain 6% / White 70%, rim, sheen | same (page behind, nothing to blur) |
| Glass / Subtle | Top bar once content scrolls beneath | Obsidian 55% / Porcelain 60%, blur 18dp | 94% / 95% |
| Glass / Standard | Floating dock; the pinned action of every step flow | Graphite 46% / White 42%, blur 12dp, lens 14dp, rim, sheen, shadow | 92% / 90% |
| Glass / Elevated | Every `AuroraDialog` | Graphite 70% / White 74% over the window-blurred page (34dp) | 97% / 98% |

## Rendering

- **True backdrop blur (API 31+).** A `Backdrop` records the content behind a pane into a `GraphicsLayer`
  (a RenderNode, replayed, not re-rasterised). The pane draws the page colour, then that node through
  `RenderEffect` blur + saturation 1.35 (vibrancy) + on API 33 an AGSL lens (`LENS` in Glass.kt) that bends the
  picture along the rim, then its tint, sheen, rim and (floating panes) a `dropShadow`.
  Panes are always siblings of their source, never inside it.
- **Dialogs.** `GlassWindow()` returns whether window blur is on and sets `FLAG_BLUR_BEHIND` + `blurBehindRadius` (compositor blur, no app cost) when
  `WindowManager.isCrossWindowBlurEnabled`; otherwise it uses a stronger dim.
- **Fallback (tinted, not blurred).** Below API 31, on low-RAM devices, with high-contrast text on (Android's
  closest "reduce transparency"), and under Robolectric. Fallback tints are 94 to 100% opaque.
- JVM screenshots (`app/src/test/screenshots`) therefore show the fallback. Real blur is verified on device.

## Component decisions

- Dock, top bar, flow action, dialogs: glass as above.
- Dropdown menus, the date picker: solid elevated (popups are separate windows; no backdrop to sample).
- Buttons, chips, switches, segmented controls, sliders, progress, fields, empty/error/loading states: solid.
- Blocking screens (`ShieldContent`, `EndSession`): solid. No blur may delay a Shield.
- Logo: never glass, blur, tint or reflection.

## Motion

Pressing a dock tab settles it to 96% (spring, no overshoot; off with reduced motion). Glass fades with the top
bar on the 240ms curve.

## Readability

`DesignTokensTest.glassIsReadable`: over the page every ink on every pane reaches 4.5:1. Worst case (a bright
primary button fully under a dark pane) primary ink stays above 2.5:1, only while it scrolls past. Dialogs keep the 280ms rise. No moving light, no animated reflections.
