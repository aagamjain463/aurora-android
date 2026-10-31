# Handoff: Aurora Phase 2B engineering foundation

- Task ID: aurora-phase-2b-engineering-foundation-20261028-152721
- Task name: Aurora Phase 2B engineering foundation
- Current agent: Claude Code (Opus 5.5)
- Status: completed (local only; nothing pushed or merged; awaiting user review)
- Last updated: 2026-10-28 16:40

## Objective
Preserve Phase 2A on spike/g1, then build Phase 2B: CI, test infra, permission coordinator, entitlement policy, docs

## Completed work
- spike/g1 (from docs/roadmap@81db1c5): c13092b docs (BUSINESS_MODEL, ENFORCEMENT_FEASIBILITY, ROADMAP, README, .gitignore graphify-out/), 41dc54f spike code (debug + testDebug only). Release APK verified spike-free.
- feat/phase-2b-foundation (from c13092b): 92ad913 fix(focus) session-clear race, f742a09 plan/ entitlement core, 3aea851 data/Access.kt + ProtectionStatus, 1302aa9 CI + tests + lint + docs.
- .ai-state handoffs not committed: ai-handoff guard blocks git add on other tasks' handoffs. Untracked on disk.

## Current work
- none

## Remaining work
- Push branches and confirm GitHub Actions run (needs user approval).
- Roadmap Phase 2 task 6 (Play Console) needs account owner.
- Deferred: plan source + debug Plus switch (first phase that wires a limit); nightly emulator CI job; Linux screenshot baselines.

## Technical decisions
- Names follow BUSINESS_MODEL.md section 5 (Plan/Allowance/Feature/FreeLimits).
- Lockdown: reserve at start; day = start date in zone at start; overlaps charged once (start-order union); early-end refund; per-reservation ceil minutes.
- Robolectric 4.17 + Roborazzi 1.77.0; src/sharedTest added to test and androidTest (kotlin.directories); JVM arg --add-opens java.base/jdk.internal.access for SDK 36.
- Screenshot baselines are macOS; CI renders only, no pixel compare.
- Lint warningsAsErrors + app/lint-baseline.xml (4 Phase 1 warnings).

## Files modified
- See `git diff c13092b..feat/phase-2b-foundation --stat`.

## Tests and results
- testDebugUnitTest: 57/57 (PlanTest 23, AccessTest 3, LogicTest 4, NavigationTest 3, ScreenshotTest 24).
- verifyRoborazziDebug: pass. lintDebug: no new issues. assembleDebug/assembleRelease: pass.
- connectedDebugAndroidTest on aurora_pixel API 36 emulator: 3/3, 5 consecutive runs after focus fix (1 failure in ~8 runs before fix).
- Release APK: 0 spike strings; permissions unchanged (PACKAGE_USAGE_STATS only).

## Errors or blockers
- GitHub Actions never executed (not pushed). Workflow YAML parses (Ruby YAML); actionlint not installed.

## Exact next steps
1. User reviews branches spike/g1 and feat/phase-2b-foundation.
2. On approval, push feat/phase-2b-foundation and check the Actions run.
3. Phase 2 task 6 (Play Console) by owner; G1 device testing remains the gate for Phase 3.
