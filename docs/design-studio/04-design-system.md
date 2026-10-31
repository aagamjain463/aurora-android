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
| `FirstLightBackdrop(tier)` | Backdrop.kt | Signature art, tiers, parallax, scrims |
| `AuroraSky(sky, glow)`, `Sky` | Sky.kt | Living aurora over the art; pass `sky`/`glow` to the backdrop |
| `Modifier.auroraSurface(colors, shape, edge, warm)` | Backdrop.kt | The one card surface: top-lit gradient, top-bright hairline; `edge` = mode colour, `warm` = sun cards |
| `LightRing(progress)` | Backdrop.kt | Focus timer ring with art window |
| `AuroraLogo`, `AuroraMark`, `Wordmark`, `AuroraHeader`, `TwoToneHeadline` | Brand.kt | Identity; two-tone keeps exact copy in semantics |
| `AuroraButton(kind)` | Buttons.kt | Primary (the aurora sweep), Secondary, Quiet, Danger; `compact` for card headers |
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
| Press (buttons) | Scale 0.97 on a spring (damping 0.8, stiffness 900); Primary adds a `ContextClick` tick |
| Press (cards) | `Modifier.pressable(shape)` before the surface: scale 0.98 on a spring, ripple clipped to the card |
| Tab change | An aurora ribbon (emerald to dawn, lit rim, soft bloom) marks the tab: its leading edge springs fast (0.68, 900), its tail slow (0.82, 220), so it stretches and gathers; the icon settles 0.86→1 (damping 0.7); `SegmentTick` |
| Screen change | Tabs cross-fade (240ms), and never replay their opening; pushed screens slide in 1/6 while the one below steps back 1/14 |
| Screen opening | Every `AuroraScreen` and Home: `ProvideIntro` + `Modifier.rise()`, blocks fade up over the last 18dp, a beat later the lower they sit, once per screen. 0.9s with art, 0.56s without; the first screen waits for the opening to part |
| Choice pills | `ChoicePills`: one lit pill slides between options, its leading edge faster than its tail (spring 0.86, 520/260), with `SegmentTick` |
| Selection | `ChoiceCard` presses and ticks, its fill and border cross-fade; `SelectionMark`'s check pops in (spring 0.55, 500) |
| Steps | `StepFlow`: the progress segment fills, the light lands on it, and each step arrives from the side it came from |
| Numbers | `CountUpText`: key metrics count up once (0.8s). `RollingClock` for values that change under the finger (Focus length) |
| Shield | `AuroraSweep` once as the stage lands; the app settles in an emerald halo |
| Time passing | `AuroraBackdrop` with `arc`: the thread runs along the horizon and the sky lights across behind it |
| List changes | Home cards use `animateItem()`, so cards arriving or leaving move the rest smoothly |
| Hero hand-over | While a timed run is active, Home's hero is its live countdown (`NowHero`); swapping cross-fades and rises |
| Hero scroll | Art parallax 0.5×, fades out over 1.2× its height |
| Phase change (Focus) | Fade in Gentle, fade out Standard |
| Onboarding page | Fade plus 10% slide, Gentle, `Motion.Ease` |
| Timers | `RollingClock`: each changed digit falls in from above (Gentle); read as one value by TalkBack |
| Fills | `rememberFill`: progress lines, habit rings, the Focus ring and the horizon arc sweep up from zero on first show (Reveal + 300ms), then glide |
| Shield stage | The app lands in its halo on a soft spring, the halo breathes (Breath), the moment's content rises in |
| Splash | The mark draws (animated vector) on night, then fades in 240ms into the opening behind it |
| Opening | `AuroraUnveil`: a point of light races along the ribbon's path (2–28%), rays grow (12–52%), the night lifts from the bottom and carries the ribbon off the top (44–100%), 5.2s in all, after the splash mark has drawn and held and the page behind has settled. Tap hurries it. First run holds at 40% for a swipe up. Progress steps per frame with a capped step, so a stall slows it and never jumps. Shader runs at half resolution from an offscreen layer; the page is not drawn while the night is opaque, and its sky only starts as the ribbon leaves (84%). Android 12 and below get a plain fade |
| Startup | `app/src/main/baseline-prof.txt` precompiles all of Aurora's code (657 of 668 classes after R8); Compose ships its own |
| Start or finish | `HapticFeedbackType.Confirm`; `AuroraSweep`: a ribbon of the sky's light climbs the whole page (1.9s, Android 13 and later); the sky surges (`Sky.Flare`) for the first 6s of a run |
| Ambient | The aurora always drifts: Quiet 0.45×, Lit 0.65×, Alive 1× pace, and wakes with a 2.6s surge each time a screen opens; `HorizonGlow` breath 4.2s while a session runs; 30 fps, paused off screen; still with reduced motion |
| Finish | `Sky.Flare`: a 0.9s surge settling over 3.6s, with Confirm haptic |
| Reduced motion | `Aurora.reducedMotion` (setting, or system animations off): every tween snaps, no parallax, no breath, no rise or rolling digits, the sky never moves |

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

- Logo master and cuts: `brand/final/` (see its README). The SVGs there are editable approximations of the raster master
- `brand/tokens/aurora.tokens.json`, which imports into Figma with the Tokens Studio plugin or into
  Penpot tokens.
- `docs/design-studio/brand-specimen.html`, a living specimen of the symbol, palette and type.
- The Compose components above, which are the components.


## Editorial refinement

- `BrandEyebrow` (Brand.kt): short label beside a horizon rule and the gold rising point.
- `AuroraNavBar`: 72dp floating dock with 16dp side insets and 12dp top/bottom space. `TabBarHeight`
  reserves at least 96dp and grows with font scale; system navigation padding is added by the screen.
- Display and DisplayXL use locally bundled Newsreader 400 at optical size 48. Everything functional,
  including timers, stays Manrope. See the current brand bible and token file for exact values.
- Home starts with the brand promise, then the filled Normal Use row and neutral Custom Session
  row. Measurement follows the actions; unavailable measurement keeps its existing explanation.
- Tinted surface edges are intentionally quieter than selection borders. Gold belongs to progress,
  intention and the rising point; filled actions stay emerald.
