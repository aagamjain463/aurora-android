# Aurora design system (current)

This is the current specification of Aurora's interface. It describes what the code does today.
The visual source of truth is the founder's reference image
(`reference/monochrome-reference.png`, 2026-11-02) and the approved screenshots in
`app/src/test/screenshots/`. Older documents in this folder (`AURORA_BRAND_BIBLE.md`, the
Northern Lights passes, `../BRAND_SYSTEM.md`, `../BRAND.md`) describe earlier looks. Where they
disagree with this file or the reference, this file and the reference win.

## Principles

1. **Calm, not loud.** Warm monochrome. One filled action per screen. No glows, gradients, blur
   or neon.
2. **Control is the feeling.** Clear status first, then the one decision the person has to make.
3. **Honest.** No invented numbers. Empty states say so plainly. Protection that is not enforced
   says so on the screen.
4. **Fast.** Shield covers the app on its first frame. Motion never delays protection or input.
5. **Words are fixed.** Founder-approved copy lives in `res/values/strings.xml` (`CopyTest`).
   A visual change never rewrites copy.

## Colour

Tokens: `brand/tokens/aurora.tokens.json`, mirrored in `ui/theme/Color.kt` (`DesignTokensTest`
keeps them in sync). Screens read semantic roles from `Aurora.colors`, never raw palette values.

| Role | Dark (default) | Light |
| --- | --- | --- |
| `background` | Ink950 `#1A1A1B` | Paper50 `#F9F3F0` |
| `sky` (Shield, hero) | Ink1000 `#141415` | Paper100 `#F5EEEA` |
| `surface` (cards) | Ink900 `#232325` | Paper100 |
| `elevated` / `field` | Ink850 `#2C2C2E` | Paper200 / Paper150 |
| `textPrimary` | Snow `#F7F7F7` | Ink `#111111` |
| `textSecondary` | Ash300 `#B4B4B8` | Stone700 `#4D4845` |
| `textTertiary` | Ash500 `#8E8E93` | Stone600 `#706A67` |
| `accent` (the one filled action) | Cream `#E9DCD4` | Charcoal `#2C2C2B` |
| `hairline` / `hairlineStrong` | white 5% / 9% | black 6% / 9% |
| `danger` (ending early, errors only) | Coral400 | Coral700 |

Some role names (`violet`, `rose`, `glow*`) are left over from the Northern Lights palette. In the
monochrome palette they map to greys and cream. Do not give them colour again.

## Type

One family: **Geist** (SIL OFL), bundled as a variable font (`res/font/geist`). Scale in
`ui/theme/Type.kt` (`AuroraType`):

| Token | Use |
| --- | --- |
| `Hero` 66/70 650, tabular | Live timers: Shield, Focus, Home, Strict |
| `Metric` 44/48 700, tabular | The one number a screen is about |
| `HeroLead` + `HeroBold` | The two-line hero ("Your phone." / "Your rules.") |
| `DisplayXL`, `Display`, `DisplayS` | Screen titles and step titles |
| `Headline`, `Bar` | Card headlines, the centred top-bar title |
| `Title`, `Body`, `BodyStrong`, `Secondary`, `Caption` | Reading text. Nothing below 13sp |
| `Label`, `Choice` | Filled-button words, chips and segmented choices |
| `Eyebrow` 12sp tracked capitals | Section labels |

Numbers use lining tabular figures so timers never jitter. `RollingClock` fits long clocks to the
space it is given (including the Focus ring) and honours large font scales.

## Space, shape, surface

- 4dp grid (`Space`), 24dp screen gutter.
- Radii (`Radius`): cards 26dp, dialogs 30dp, every interactive pill fully round.
- One surface recipe: `Modifier.glass` / `auroraSurface`. A flat card one step off the page with a
  1dp hairline. A chosen card adds a faint accent wash and a firmer edge.
- The dune (`PageGlow`): two flat static hills along the bottom. Only on hero screens (Home,
  Welcome, Shield). It is Aurora's "first light on the horizon" motif and costs one draw.

## Components (`ui/components`)

Build new UI from these. Do not hand-roll cards, buttons, chips or colours.

- Structure: `AuroraScreen`, `AuroraHeader`, `AuroraNavBar` (the 64dp bordered dock),
  `ScreenIntro`, `SectionLabel`, `Eyebrow`, `BrandEyebrow`.
- Actions: `AuroraButton` (`ButtonKind` Primary / Secondary outlined / Quiet / Danger),
  `AuroraIconButton`.
- Choice: `Chip` (single), `ChipFlow` (multi, same pill), `ChoicePills`, `ChoiceCard`, `RadioRow`,
  `SelectionMark`, `AuroraSwitch` (44x26), `ToggleRow`, `DurationPicker`.
- Content: `AuroraRow`, `RowGroup`, `RowDivider`, `Card`, `MetricGrid`, `StatCell`, `BigMetric`,
  `WeekDots`, `ProgressLine`, `Tag`, `StatusPill`, `LitBadge`, `ModeGlyph`, `GlyphTile`.
- Fields and feedback: `AuroraTextField`, `AuroraDialog`, `Notice`, `EmptyState`, `SkeletonRows`,
  `LoadingSurface`.
- Brand: `AuroraMark` (the curved A, `res/drawable/ic_aurora_mark.xml`), `AuroraLogo`, `Wordmark`
  (Geist bold), `TwoToneHeadline`, `HeroHeadline`, `PageGlow`.
- Motion: `RollingClock`, `CountUpText`, `Modifier.rise`, `Modifier.pressable`, `AuroraUnveil`.

## Iconography

`AuroraIcons` in `Icons.kt`: one outlined family at a single stroke weight, 20–24dp, drawn in
`textPrimary` or `textSecondary`. The selected dock tab sits in a round well (`dockWell`). App
icons come from the system and are shown as-is in a rounded tile.

## Motion

Tokens in `ui/theme/Theme.kt` (`Motion`): one easing curve (0.2, 0, 0, 1); Quick 120ms, Standard
240ms, Gentle 420ms, Reveal 900ms.

- Use `auroraTween()`. It snaps instantly when motion is reduced.
- Reduced motion comes from the in-app setting, the system animator scale, or a low-RAM phone.
  With it on there is no movement, scaling or ambient loop.
- Allowed: opacity and short rises on entry, digits rolling in clocks, numbers counting up once,
  a press that sinks slightly, the Focus ring filling.
- Not allowed: continuous ambient effects, anything that delays the Shield, anything that blocks
  input while it plays.

## Signature screens

- **Shield** (`protect/ShieldContent.kt`): opaque `sky` background on the first frame, the curved A
  at the top, the app's tile, one bold statement, a status pill with the end time, and at most one
  filled action. The content fades and rises over 420ms. The protection does not wait for it.
- **Intent** (Shield, start-aligned variant): the question, the person's own reason in a card,
  duration chips, and "Continue intentionally" as an outlined action that unlocks after the pause.
- **Focus**: setup puts the length front and centre. While running, the clock sits inside a thin
  ring that fills as time passes, with an honest "Timer only · Shield is off" pill when nothing is
  enforced.

## Checks

- `DesignTokensTest`: palette matches the token JSON.
- `CopyTest`: approved copy is unchanged.
- `ScreenshotTest` (Roborazzi): every screen in Dark, Light, LargeText and ReducedMotion. After any UI
  change, run `./gradlew recordRoborazziDebug` and look at the changed images before you finish.
