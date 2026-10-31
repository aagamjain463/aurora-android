# Handoff: Aurora signature opening and app-wide animation

- Task ID: aurora-signature-opening-and-app-wide-animation-20261030-184749
- Task name: Aurora signature opening and app-wide animation
- Current agent: Claude Code
- Status: in progress
- Last updated: 2026-10-30 18:47:49

## Objective
Build an aurora-based unravel opening reveal and raise animation quality across the whole app, with Northern Lights as the brand identity

## Completed work
- (none yet)

## Current work
- (starting)

## Remaining work
- (to be determined)

## Technical decisions
- (none yet)

## Files modified
- (none yet)

## Tests and results
- (none yet)

## Errors or blockers
- none

## Exact next steps
1. (to be determined)

<!-- Observable progress between checkpoints: `ai-handoff recorded aurora-signature-opening-and-app-wide-animation-20261030-184749.md` -->

## Checkpoint 2026-10-30
- Task ID: aurora-signature-opening-and-app-wide-animation-20261030-184749
- Current agent: Claude Code
- Status: completed
- Objective: aurora unravel opening, better app-wide animation, Northern Lights brand identity.
- Completed work: AuroraUnveil opening with first-run swipe; screens wait for it; word-by-word headlines; stronger sky with violet tops; gold removed from night art; bible and design system updated.
- Current work: none.
- Remaining work: founder review on the phone; logo and day art not reworked; battery not measured; Opal's stone animation never observed.
- Technical decisions: shader at half resolution from an offscreen layer for frame rate; opening on cold start or after 5 minutes away; temporary perf build type removed from build.gradle.kts.
- Files modified: ui/components/Unveil.kt (new), Sky.kt, FirstLight.kt, Motion.kt, Brand.kt, theme/Color.kt, MainActivity.kt, res strings/colors/v31 themes, brand tokens, first_light.py and exported art, screenshot baselines, docs 04, 10, 11 and the bible, test OpeningTest.kt.
- Tests and results: testDebugUnitTest 322 tests, 0 failed; recordRoborazziDebug successful; phone frame stats in docs/design-studio/11-northern-lights-pass.md.
- Errors or blockers: none. Nothing committed.
- Exact next steps: founder cold-opens the app on the phone and gives feedback on timing and strength.
- Last updated: 2026-10-30

## Checkpoint 2026-10-30 (smoothness)
- Current agent: Claude Code
- Status: in progress
- Objective: founder reported the opening as glitchy; make it smooth and a little slower.
- Completed work: capped per-frame stepping, settle before moving, page not drawn under opaque night, sky shader warmed under the night, word-by-word headlines removed, thicker strands, static stars, dither, 2.8s duration, fade-in on returning to a running app.
- Tests and results: see final test line in this run; emulator sequence clean. Not yet measured on the phone.
- Errors or blockers: phone disconnected from USB mid-run.
- Exact next steps: when connected, install scratchpad aurora-smooth.apk, measure four cold opens with gfxinfo in portrait (lock rotation, then restore accelerometer_rotation 1 and user_rotation 0), review recording, update doc 11 numbers.
- Last updated: 2026-10-30

## Checkpoint 2026-10-30 (smoothness verified)
- Current agent: Claude Code
- Status: completed
- Completed work: phone measured on the optimized build; sky start moved to 84% of the opening to remove the dip at the reveal.
- Tests and results: phone framestats over 3 cold opens: no frame over 32 ms while moving. Full suite result reported in the final message.
- Errors or blockers: none. Phone is left on the optimized build; rotation restored. Nothing committed.
- Exact next steps: founder judges feel; tune PlayMs in Unveil.kt if needed.
- Last updated: 2026-10-30
