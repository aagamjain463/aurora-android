# Handoff: First Light opening animation

- Task ID: first-light-opening-animation-20261031-105106
- Task name: First Light opening animation
- Current agent: Claude Opus 5.5
- Status: completed
- Last updated: 2026-10-31 12:15
- Checkpoint: work is in worktree ../aurora-android-first-light, branch feat/first-light, based on feat/final-logo 1b8b92a. Do not kill or reboot emulator-5554 (denied once); it runs SwiftShader, so frame numbers are not representative. Do not touch phone ayeqz595b6g6u4ey. Disk has about 2 GB free.
- Design: splash icon is animated WebP via animated-image (splash_icon_first_light.xml); Unveil.kt reveal shader uses first_light_reveal.png over logo_full.webp; assets from brand/tools/first_light.py.

## Objective
Implement FIRST LIGHT splash and Compose opening animation using the founder-approved logo, then verify on emulator

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

<!-- Observable progress between checkpoints: `ai-handoff recorded first-light-opening-animation-20261031-105106.md` -->

## Final state
- Commit on feat/first-light, worktree ../aurora-android-first-light, on top of feat/final-logo 1b8b92a. Not merged, not pushed.
- Tests: testDebugUnitTest 297 pass. verifyRoborazziDebug: 16 failures in 9 live-countdown tests (one inspected: a one-second timer tick); no baseline changed. lintDebug: 3 PluralsCandidate errors in strings.xml, not in this work.
- Verified on emulator-5554 (API 36, SwiftShader): cold, hot, warm with Shield alive, running session, reduced motion, tap, rotation, night modes, 720x1280, 1440x3120; Shield blocked YouTube.
- Not verified: real-GPU frame pacing, reboot, Focus timer specifically, TalkBack, API 26-35.
- Exact next steps: founder merges feat/final-logo then feat/first-light; check frame pacing on a real device or an emulator with -gpu host.

## Founder follow-up, 2026-10-31
- Founder saw the logo animate twice on the phone and asked for once, and much slower. Splash icon is now empty (splash_empty.xml), animated WebP removed, opening is 4.5 s (Unveil.kt TotalMs, FormMs, PartAt, LeaveAt).
- Installed on phone ayeqz595b6g6u4ey at founder's request. Phone recording shows one formation. Debug build on phone: about 2 s of empty midnight green before the logo; gfxinfo over launch and Home: 46 ms median, 33% janky, while screen recording.
- Next: release build to measure real startup and smoothness on the phone.

## Founder follow-up 2, 2026-10-31
- Asked for slightly slower and perfectly smooth. Opening is 5.5 s. Phone now runs assemblePhone build (release speed, debug key), compiled with speed-profile.
- Per-frame log on phone, 3 launches, with temporary logging since removed: no frame over 25 ms in form, hold, content or after-Home phases; fade 1 late frame in one run of 3. Clean build gfxinfo over launch: 1.7% janky, 17-18 ms median.
- Evidence clips in docs/design-studio/evidence/first-light still show 4.5 s timing.
- Next: founder merges feat/final-logo then feat/first-light when ready.

