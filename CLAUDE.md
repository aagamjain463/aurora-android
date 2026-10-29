# Aurora Android: agent instructions

## Design preservation (permanent rule)

**Aurora's approved First Light brand identity is the default visual source of truth. Functional changes
must reuse its design system unless the founder explicitly requests a redesign.**

- Rulebook: `docs/design-studio/AURORA_BRAND_BIBLE.md`. Components and tokens:
  `docs/design-studio/04-design-system.md`, `brand/tokens/aurora.tokens.json`.
- The approved references are `docs/aurora-design-reference/01–05`.
- Build new UI from `ui/components` (`auroraSurface`, `AuroraButton`, `FirstLightBackdrop`, `LitBadge`,
  `ModeChip`, `AuroraHeader`, `TwoToneHeadline`). Don't hand-roll cards, buttons or colours.
- Founder-approved copy is exact and lives in `res/values/strings.xml` (checked by `CopyTest`).
- Never show sample or invented statistics; design the empty state instead.
- Changing a palette colour means changing the token JSON too (`DesignTokensTest` enforces it).
- After UI changes, run `./gradlew recordRoborazziDebug`, then look at the changed images in
  `app/src/test/screenshots/` before calling the work done.
