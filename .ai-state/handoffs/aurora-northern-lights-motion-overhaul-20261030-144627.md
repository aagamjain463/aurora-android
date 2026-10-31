# Handoff: aurora northern-lights motion overhaul

- Task ID: aurora-northern-lights-motion-overhaul-20261030-144627
- Task name: aurora northern-lights motion overhaul
- Current agent: Claude Opus 5.5
- Status: completed (awaiting founder's real-phone feedback; nothing committed)
- Last updated: 2026-10-30 15:20

## Objective
Replace subtle motion with bold, premium, aurora-themed animations (living sky, splash hand-over, openings, tab bar, press, start moment) at Opal-level quality without copying Opal

## Completed work
- Diagnosis: the founder saw "no animations" because the sky only moved during a session and Quiet was nearly invisible (strength 0.10 with no progress), the opening was 28dp/1s, the press was 2.5%, and the cream splash flashed before a dark app.
- Sky.kt: the aurora always drifts (Quiet 0.45x, Lit 0.65x, Alive 1x). Stronger baselines (Quiet 0.42). Waking surge 0.7 decaying over 2.6s on every screen open. Flare surge raised to 1.4.
- Sky.kt `AuroraSweep(play)`: three rayed curtains (vertical gradient, DstIn ray comb, lit hem) sweep up the page over 1.9s. Shown on Home when `sky == Sky.Flare`.
- Motion.kt `rise()`: 56dp rise, 14dp blur to sharp (RenderEffect, API 31+), scale 0.94 to 1. Intro lasts 1.4s. The first intro of the process waits 320ms for the splash. `pressable` scale is 0.96.
- Buttons.kt: press scale 0.94.
- Scaffold.kt `AuroraNavBar`: a stretchy aurora ribbon (lead spring 0.68/900, tail 0.82/220, gradient accent→glowCool→glowWarm, bloom, rim). Icon pop 0.7 to 1.
- MainActivity: splash exit animation (icon 1.5x, fade 560ms). `UiModeManager.setApplicationNightMode` follows ThemeMode, so the splash matches the in-app theme. Manifest has `configChanges="uiMode"` on MainActivity so this does not recreate the activity.
- docs/design-studio/04-design-system.md Motion table updated.
- Founder follow-up (15:40):
  - The Home choices are renamed "Normal Session" and "Custom Session" (strings, the ConfigureScreen fallback name, NavigationTest).
  - ChooseCard now sits directly under the hero, before the status, reports, active and ready items. `busyNow()` was extracted from `homeNow`, which now returns Unit.
  - FocusReport: "Completed" is full width; "Partly" and "Not completed" share a row (stacked when fontScale > 1.15), so nothing wraps.
  - SmallChoice tiles show the icon over the title, with maxLines 1.
  - Tests 321/0, lint clean, Roborazzi re-recorded and reviewed (home_ready, home_active), installed on the phone.

## Current work
- none

## Remaining work
- The founder tests on the phone (Redmi 22041219I, Android 14, 60/90Hz) and gives tuning feedback.
- Optional: run the sweep on Focus start too; make AuroraLight (Home/Insights) breathe.

## Technical decisions
- No new dependencies. The sky is the existing AGSL shader (API 33+). The sweep is a Canvas, so it also works on API 26+.
- Reduced motion (system animator scale 0 or the in-app toggle) still disables everything.

## Files modified
- app/src/main/java/app/aurora/ui/components/{Sky.kt, Motion.kt, Buttons.kt, Scaffold.kt}
- app/src/main/java/app/aurora/ui/home/HomeScreen.kt
- app/src/main/java/app/aurora/MainActivity.kt, app/src/main/AndroidManifest.xml
- docs/design-studio/04-design-system.md, app/src/test/screenshots/* (re-recorded)

## Tests and results
- testDebugUnitTest: 321 tests, 0 failures. lintDebug: clean (4 baseline).
- recordRoborazziDebug re-run. Reviewed home_navigation, home_ready and track (Dark); they look correct.
- Emulator recordings: sky rays drift, the splash dissolves, the Track blocks blur in, and the sweep is rayed. The emulator GPU is software, so smoothness is not judged.
- Debug build installed on the phone (ayeqz595b6g6u4ey) and on emulator-5554.

## Errors or blockers
- The phone was locked, so no recording was made on the phone.
- The emulator has an active Intentional YouTube run, started for the sweep test.

## Exact next steps
1. Get the founder's feedback from the phone, then tune the values in Sky.kt, Motion.kt and Scaffold.kt.
2. Commit only when asked. The tree mixes in other sessions' work.
