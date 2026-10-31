# Handoff: Aurora Phase 2D merge foundation and G1 device validation

- Task ID: aurora-phase-2d-merge-foundation-and-g1-device-validation-20261028-162833
- Task name: Aurora Phase 2D merge foundation and G1 device validation
- Current agent: Claude Opus 5.5
- Status: blocked (needs a physical phone and the user enabling the accessibility service)
- Last updated: 2026-10-28 17:30

## Objective
Merge approved PR #1 to main, verify main CI, build debug-only G1 device harness on spike branch, run on physical device if available

## Completed work
- PR #1 rechecked (head d21d1f4, base main, MERGEABLE CLEAN, checks green, no new commits), merged with a merge commit: ccd7a96. Feature branch kept. No force push.
- Main CI on ccd7a96: https://github.com/aagamjain463/aurora-android/actions/runs/40676350739 success.
- Branch spike/g1-harness (from spike/g1 41dc54f), commit 395f4ba, LOCAL ONLY (not pushed):
  G1.kt (new), SpikeShieldService.kt, SpikeShieldActivity.kt, app/build.gradle.kts (g1 build type), tools/g1/g1.sh (new), .gitignore, docs/ENFORCEMENT_FEASIBILITY.md section 10.
- Emulator: g1 APK installed, onboarding done, Clock tapped on Protect tab, `g1.sh device` preflight ran (non-debuggable confirmed).

## Current work
- None. Waiting on the user.

## Remaining work
- Enabling the spike service through adb (settings put) was denied by the permission classifier. The user must turn it on in Settings, or approve it.
- No physical phone connected: G1 device runs not done. Section 10.5 results table is empty.
- Emulator smoke run of `g1.sh launches` not done (needs the service on).

## Technical decisions
- Latency = exposure: AccessibilityEvent.eventTime to end of the Shield's first FrameMetrics frame (INTENDED_VSYNC + TOTAL_DURATION), CLOCK_MONOTONIC. Split into detect and present. Excludes the final display refresh.
- Denominator = all host-caused opens; misses/timeouts (2 s) count as failures.
- g1 build type = release settings (R8, not debuggable) + debug source set, debug-signed. CSV in external files dir, so no run-as is needed.
- UsageStats probe removed from the spike (it polled during the timed frames).
- ROADMAP not changed (no new evidence).

## Files modified
- main: none in this task (merge only).
- spike/g1-harness: listed above.

## Tests and results
- main local: 57/57 unit, 3/3 connected (emulator API 36), lintDebug (4 baselined), debug and release builds; release dex spike count 0.
- spike/g1-harness: 9/9 unit, lintDebug (only the same 4 Phase 1 warnings), assembleDebug/Release/G1 ok; release spike count 0; g1 APK has the spike and is not debuggable.

## Errors or blockers
- adb accessibility enable denied (correctly: never enable silently).
- No physical device.

## Exact next steps
1. User turns on "aurora spike shield" on the emulator or a phone (Settings > Accessibility).
2. On spike/g1-harness: `./gradlew assembleG1 && adb install -r app/build/outputs/apk/g1/app-g1.apk`, then follow ENFORCEMENT_FEASIBILITY.md 10.3.
3. Fill section 10.5 from `g1.sh report`. Mark G1 only with three-phone evidence.
4. Ask before pushing spike/g1-harness.
