# Aurora Android: agent instructions

## Design preservation (permanent rule)

**Aurora's visual source of truth is the finalized monochrome identity (founder, 2026-11-07):
`brand/Aurora_Brand_Guidelines.md`. Personality: Quiet Authority. Central idea: Control. Dark (Obsidian) is the
default theme; Light (Porcelain) is fully developed. Functional changes must reuse this design system unless the
founder explicitly requests a redesign.**

- Logo: `brand/Aurora_Final_Approved_Logo.png` is the master (`LogoMasterTest` pins its hash). Never redraw, trace,
  tint, regenerate or recolour it. In the app it is `AuroraMark` (`res/drawable-nodpi/aurora_logo.png`: the master
  resampled with only its black canvas lifted off). Launcher uses `ic_launcher_logo.png` (the master on its
  own black). The opening and splash use `aurora_glyph.png` (the mark alone, lifted off its tile and black, no border). The notification icon is the only one-colour derivative (Android requires it).
- Gold exists inside the logo and, since 2026-11-08 (founder), on Aurora Plus alone: its card (`PlusSurface`),
  badge (`PlusBadge`), name, and the actions that lead to it (`ButtonKind.Plus`/`PlusQuiet`, `AuroraDialog(plus = true)`),
  all from `ui/components/Plus.kt` and the `PlusGold*` palette / `colors.plus`. No gold or accent hue anywhere else:
  not in other tokens, text, icons, borders, charts, highlights or marketing. `DesignTokensTest.paletteIsMonochrome`
  enforces it (danger red and `PlusGold*` are the only hues).
- Never: northern lights, mountains, landscapes, stars, celestial scenery, glowing horizons, luminous arcs, light
  ribbons, decorative glassmorphism, gradients or page-level glow. The design board
  (`brand/Aurora_Brand_Design_Reference.png`) is a concept only; its illuminated curves are rejected.
- Tokens: `brand/tokens/aurora.tokens.json` and `ui/theme/Color.kt` (`DarkColors`, `LightColors`), `Type.kt`,
  `Theme.kt` (`Space`, `Radius`, `Motion`). Changing a palette colour means changing the token JSON too.
- Type: Inter only (`res/font/inter.ttf`, variable). The brand line uses its display cut (`AuroraType.BrandLine`, opsz 32; founder, 2026-11-08). Semibold headings with slightly tight tracking, regular body,
  tabular numerals for timers and statistics. No synthesized italic or bold; nothing thinner than 350.
- Shapes: buttons and fields 14dp, cards 20dp, dialogs 24dp; chips round. Cards are glass (`glass` /
  `auroraSurface` = `glassCard`: translucent fill, sheen, no outline; buttons, chips and fields keep the lit rim). The primary action is solid Porcelain on dark, solid Obsidian on light (`AuroraButton` Primary).
- Motion: 160ms micro, 240ms navigation, 280ms modal, one ease curve, no bounce, no continuous decorative loops;
  respect reduced motion. Blocking screens appear immediately.
- Copy: the five approved brand messages in the guidelines are exact; add no slogans. Founder-approved UI copy lives
  in `res/values/strings.xml` (checked by `CopyTest`). Never show sample or invented statistics.
- Materials (founder, 2026-11-07): iOS-style glass on every element: cards, rows, fields, chips, wells, buttons.
  Solid = stability, glass = contextual depth. Glass only via `Modifier.material(Material.Subtle|Standard|Elevated, shape, backdrop)` in
  `ui/components/Glass.kt`: the top bar (Subtle), the dock and a flow's pinned action (Standard), dialogs
  (Elevated + `GlassWindow` window blur), controls via `glassControl`. Real blur API 31+, lens API 33+; page elements via
  `glassCard` (behind `glass`/`auroraSurface`), solid primary fills get `glassShine`. Never on the logo itself. Rim/sheen are white only, never gold. Usage map: `brand/Aurora_Material_System.md`.
  `DesignTokensTest.glassIsReadable` guards contrast.
- Build new UI from `ui/components` (`glass`, `auroraSurface`, `material`, `AuroraButton`, `AuroraMark`, `AuroraHeader`,
  `HeroHeadline`, `TwoToneHeadline`, `LitBadge`, `ModeChip`). Don't hand-roll cards, buttons or colours.
- After UI changes, run `./gradlew recordRoborazziDebug`, then look at the changed images in
  `app/src/test/screenshots/` before calling the work done.
- The previous identity (First Light: dawn colours, serif type, northern-lights logo) is retired and backed up in
  `backups/branding/first-light-2026-11-07/` (see its README). It is history, not guidance.
