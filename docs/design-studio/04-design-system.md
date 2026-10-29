# 04 — Design system

The editable, executable design system is the Compose code plus the token file. There is no Figma file
(see "Figma" below for why).

## Tokens

- **Machine-readable:** `brand/tokens/aurora.tokens.json` (W3C Design Tokens format): palette, semantic
  colours, type, spacing, radii, motion.
- **Android:** `ui/theme/Color.kt` (`Palette`, `AuroraColors`), `Type.kt` (`AuroraType`), `Theme.kt`
  (`Space`, `Radius`, `Motion`).
- **Sync:** `app/src/test/java/app/aurora/ui/DesignTokensTest.kt` fails if a palette colour in Kotlin
  differs from the JSON.

Spacing `Space`: XXS 4 · XS 8 · S 12 · M 16 · L 24 · Gutter 24 · XL 32 · XXL 48 (dp).
Radius `Radius`: S 12 · M 18 · L 26 · Dialog 30 · Pill 50%.
Motion `Motion`: Quick 120 · Standard 240 · Gentle 420 · Reveal 900 · Breath 4200 (ms), `Motion.Ease`.

## Components (all in `app/src/main/java/app/aurora/ui/components/`)

| Component | File | Notes |
|---|---|---|
| `FirstLightBackdrop(tier)` | FirstLight.kt | Signature art, tiers, parallax, scrims |
| `Modifier.auroraSurface(colors, shape, edge, warm)` | FirstLight.kt | The one card surface: top-lit gradient, top-bright hairline; `edge` = mode colour, `warm` = sun cards |
| `LightRing(progress)` | FirstLight.kt | Focus timer ring with art window |
| `AuroraMark`, `Wordmark`, `AuroraHeader`, `TwoToneHeadline` | Brand.kt | Identity; two-tone keeps exact copy in semantics |
| `AuroraButton(kind)` | Buttons.kt | Primary (First Light gradient and glow), Secondary, Quiet, Danger; `compact` for card headers |
| `AuroraNavBar` | Scaffold.kt | Lifted panel, selected pill, rising-point dot |
| `AuroraScreen(art=…)` | Scaffold.kt | Large-title list screen; with `art` the top bar stays clear until collapsed |
| `LitBadge` | Parts.kt | Ringed icon circle; lit = state is on |
| `StatCell`, `StatDivider`, `WeekDots` | Parts.kt | Card stats and this week as points of light |
| `ModeChip`, `modeIcon`, `modeColor` | ui/protect/ProtectParts.kt | One glyph and colour per mode |
| `ChoicePills(segmented=…)` | Controls.kt | Pills or one-track segmented switch |
| `RowGroup`, `AuroraRow`, `SectionLabel` | Rows.kt | Restrained configuration lists (now on `auroraSurface`) |
| `DurationPicker` | Kit.kt | Presets plus custom hours and minutes |
| `AuroraDialog`, `EmptyState`, `Notice`, `SkeletonRows`, `StatusPill` | Feedback.kt | Dialogs, empty, loading and notice states |
| `AuroraLight`, `HorizonGlow`, `HorizonArc` | Light.kt, Brand.kt | Progress visual and atmosphere |

Rules:

- A screen gets one Primary button. Ending something early is Danger, never Primary.
- Every container is `auroraSurface`. Don't hand-roll `clip + background + border`.
- Mode is always shown with `ModeChip` or `modeColor`, never in a new colour.
- Hero screens open with `AuroraHeader`. Detail screens use `AuroraScreen` with a back button.

## Motion

| Interaction | Spec |
|---|---|
| Press | Scale 0.97, `Motion.Quick`; ripple in content colour |
| Tab change | Colour and dot fade, `auroraTween()` (Standard) |
| Hero scroll | Art parallax 0.5×, fades out over 1.2× its height |
| Phase change (Focus) | Fade in Gentle, fade out Standard |
| Onboarding page | Fade plus 10% slide, Gentle, `Motion.Ease` |
| Timers | Linear 1s glide between ticks (ring, arc) |
| Start or finish | `HapticFeedbackType.Confirm` |
| Ambient | `HorizonGlow` breath 4.2s, only while a session runs |
| Reduced motion | `Aurora.reducedMotion` (setting, or system animations off): every tween snaps, no parallax, no breath |

## Accessibility

- Text contrast: body text uses `textSecondary` (Mist 300 on Night 950 ≈ 10:1). Light-theme CTA text is
  white on ≤ `#2A7F55` (≥ 4.5:1).
- Touch targets ≥ 48dp (buttons, icon buttons, rows ≥ 64dp).
- `TwoToneHeadline` exposes the exact copy, with no line break, as a heading. Stat groups and week dots
  merge semantics and speak a summary. Timers speak "x remaining".
- Font scale 200% is verified in the screenshot suite (`*_LargeText.png`).

## Figma

There is no Figma connector in this environment, and no Figma file was created. The Chrome extension
could drive figma.com, but that needs the founder's sign-in and uploads private designs to a third
party. Both are approval-gated, and pixel-driving a full component library through the browser would
produce a fragile, unverified copy of what the code already defines. The editable deliverables instead:

- SVG masters: `brand/logo/*.svg`, which import into Figma, Penpot or Inkscape as editable vectors.
- `brand/tokens/aurora.tokens.json`, which imports into Figma with the Tokens Studio plugin or into
  Penpot tokens.
- `docs/design-studio/brand-specimen.html`, a living specimen of the symbol, palette and type.
- The Compose components above, which are the components.
