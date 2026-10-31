# Handoff: Aurora Phase 3 end-to-end emulator validation

- Task ID: aurora-phase-3-end-to-end-emulator-validation-20261029-005848
- Task name: Aurora Phase 3 end-to-end emulator validation
- Current agent: Claude Code (Opus 5.5)
- Status: completed (local commits e7b1beb, b631b04; not pushed or merged)
- Last updated: 2026-10-29 01:50

## Objective
Install Phase 3 debug on emulator-5554 preserving G1 data, user enables aurora Shield, validate Shield end to end, measure, review decisions, fix and commit locally

## Completed work
- Backed up device g1.csv to g1-results/emulator-5554/device-g1-raw-20261029.csv (122 rows).
- Same debug signer + versionCode 1 -> adb install -r of Phase 3 debug (fa38dc1/b419290). firstInstallTime kept, data kept.
- Spike service gone; enabled_accessibility_services now null. ShieldService declared. App launches; Today shows '1 app chosen · Not protected yet' (Clock via legacy protected_apps key).

- User enabled aurora Shield. Verified bound, capabilities=0; Today/Protect/Settings show Active.
- E2E pass: Clock Shield + label, Close app->home, Back->home, exempt (aurora, Settings, a11y settings, dialer, emergency dialer, app info), untick/re-tick changes enforcement, 6th app refused on device, Calendar label, Settings row disabled.
- Bugs fixed (uncommitted): (1) repeat suppression left Clock in front -> re-assert every event; (2) per-present tokens killed in-flight Shield -> generation tokens + bounded confirm re-check (1 s, 3 retries) + onStop hidden hook; (3) over-limit auto-activation -> explicit held/paused marks, resume() with user choice dialog; (4) copy: singular count, Off card when allowed.
- Rapid reopen 9/9 Shield in front; Clock->Calendar gaps 0.05-2.5 s never Shield over Calendar.
- Latency (debug build, emulator, 30 opens): detect med 2 p95 31; decide med 0; present med 231 p90 383; exposure med 249 p90 387 p95 485 max 694; 23/30 <=300 ms.

## Current work
- none

## Remaining work
- (to be determined)

## Technical decisions
- (none yet)

## Files modified
- (none yet)

## Tests and results
- Revoke/re-enable (by user): setting removed 30806.05, unbound+destroyed 30806.0; listed 30871.14, connected 30871.1 (0.1 s resolution), same pid 30429. UI status during off window not observed.
- Final: 100 unit tests pass; lintDebug clean; verifyRoborazziDebug pass; assembleDebug/Release pass; lintRelease fails only AndroidGradlePluginVersion (pre-existing, environmental). Release: no spike, no debug log, not debuggable.
- (none yet)

## Errors or blockers
- none

## Exact next steps
1. User review of e7b1beb, b631b04 and merge decision.
2. Optional: second off cycle to observe Today/Protect while Shield is off.
3. Physical-device G1.

<!-- Observable progress between checkpoints: `ai-handoff recorded aurora-phase-3-end-to-end-emulator-validation-20261029-005848.md` -->
