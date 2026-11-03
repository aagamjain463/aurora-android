# Aurora Android: agent instructions

## Design preservation (permanent rule)

**Aurora's visual source of truth is First Light (founder-requested complete overhaul, 2026-11-02 evening), sharpened
on 2026-11-03 by a second founder-requested overhaul (lit cards, stronger dawn colour). "Aurora" is a brand name, not
a picture of the aurora or the dawn (founder, 2026-11-03). Dark (night) is the default theme; Light (day) is the alternative. Functional changes must reuse
this design system unless the founder explicitly requests a redesign.**

- Tokens: `brand/tokens/aurora.tokens.json` and `ui/theme/Color.kt` (`DarkColors`, `LightColors`), `ui/theme/Type.kt`.
  The approved look is `app/src/test/screenshots/` (`*_Dark.png`, `*_Light.png`).
- The one colour Aurora owns is the dawn (gold, ember, rose, plum) over a violet-black night or warm paper. Use it only
  through the system: the sunlit filled action (`sunlit`, `AuroraButton` Primary), the lit
  mark (`AuroraMark(lit = true)`) and the Focus ring. No other gradients, no glassmorphism, no blur.
- No glow, Horizon or stars behind pages. The founder had the bottom glow (`PageGlow`) removed from every screen on
  2026-11-03 ("it looks weird and is not necessary"); don't bring back any page-level glow or backdrop art. Protection
  state is said in words (`EnforcementStatus`), not in light.
- Cards (`glass`) are lit from above at night (fill a step brighter on top, rim brightest on top); by day they are paper
  on a soft warm shadow. The sunlit action has a lit top edge. The dock
  marks the chosen tab with a point of first light, not a well.
- Type: Instrument Serif (one weight, never synthesised bold) for what a screen is about (`HeroBold`, `Display*`,
  the wordmark); Geist for everything read or tapped; light tabular Geist for timers and metrics. Never set numbers
  in the serif: its 1 reads as an l ("lh 30m").
- The brand mark is the curved A (`res/drawable/ic_aurora_mark.xml`); lit by the dawn on the launcher, opening,
  header, Shield and sign-in. The launcher icon is the lit A on plain night: the founder rejected a Horizon behind it
  (2026-11-03), so leave the logo alone unless asked. Notification icon stays one colour.
- Build new UI from `ui/components` (`glass`, `auroraSurface`, `AuroraButton`, `sunlit`, `LitBadge`,
  `ModeChip`, `AuroraHeader`, `TwoToneHeadline`, `HeroHeadline`). Don't hand-roll cards, buttons or colours.
- Older docs (`docs/design-studio/*`, the monochrome reference image, Northern Lights) are history; where they disagree
  with First Light, First Light wins. Backup of First Light before the orbit Horizon: `git stash list` ("First Light UI
  before 2026-11-03 overhaul") and `~/aurora-backups/first-light-before-overhaul-20261103.tgz`. Older backup: `git stash list` ("monochrome UI before Dawn overhaul") and
  `~/aurora-backups/monochrome-before-dawn-20261102.tgz`.
- Founder-approved copy is exact and lives in `res/values/strings.xml` (checked by `CopyTest`).
- Never show sample or invented statistics; design the empty state instead.
- Changing a palette colour means changing the token JSON too (`DesignTokensTest` enforces it).
- After UI changes, run `./gradlew recordRoborazziDebug`, then look at the changed images in
  `app/src/test/screenshots/` before calling the work done.
