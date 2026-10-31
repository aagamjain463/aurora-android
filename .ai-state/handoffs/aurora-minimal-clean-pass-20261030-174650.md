# Handoff: aurora minimal clean pass

- Task ID: aurora-minimal-clean-pass-20261030-174650
- Task name: aurora minimal clean pass
- Current agent: Claude Opus 5.5
- Status: completed (not committed)
- Last updated: 2026-10-30 17:57:56

## Objective
Make the app slightly more minimal, organised and clean without removing any function; remove only unnecessary info

## Completed work
- Home: no eyebrow, subtitle or section label; shorter rows; empty metric card hidden; Recent list left to Track; duplicate status line hidden on the hero's run.
- Shield and Intent tabs: one headline, no eyebrow, body or repeated section label; shorter card text; Intent explainer shows only until the first Intent exists.
- Track: duplicate level card removed (introduced by the previous task); repeated labels and verified text trimmed.
- Builders: shorter step copy; step counter dropped in favour of the progress line (kept for screen readers).
- Shield overlay: app name under the icon dropped when the headline carries it; websites keep it. Pause text duplicate removed.
- 25 unused strings removed. Bible gains the rule: say each fact once.

## Current work
- None.

## Remaining work
- Founder review on a real phone.

## Technical decisions
- No function, setting or action removed. Commitment, consent and Shield limitation text kept.
- Settings, Plus, Strict and onboarding left as they were.

## Files modified
- ui/home, ui/library, ui/insights, ui/track, ui/focus, ui/protect/ShieldStatus, ui/configure, ui/components/Kit, protect/ShieldContent, strings.xml, ScreenshotTest, NavigationTest, Brand Bible, screenshots, brand/store/out.

## Tests and results
- recordRoborazziDebug: 321 tests, 0 failures. lintDebug: no new issues. Installed on the emulator and the phone.

## Errors or blockers
- Phone frame rate not measured: the screen was asleep, gfxinfo returned 0 frames.
- After install, the only enabled accessibility service on the phone is cz.mobilesoft.appblock; Aurora Shield is off there. Unknown whether it was on before the install.

## Exact next steps
1. Commit when the founder approves.
