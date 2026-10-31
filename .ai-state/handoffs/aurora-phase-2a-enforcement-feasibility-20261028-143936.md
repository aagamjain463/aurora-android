# Handoff: Aurora Phase 2A enforcement feasibility

- Task ID: aurora-phase-2a-enforcement-feasibility-20261028-143936
- Task name: Aurora Phase 2A enforcement feasibility
- Current agent: Claude Opus 5.5
- Status: completed (awaiting user review; nothing committed)
- Last updated: 2026-10-28

## Objective
Determine reliable, Play-compliant Android app-restriction enforcement; isolated prototype, architecture, docs/ENFORCEMENT_FEASIBILITY.md

## Completed work
- Audited ROADMAP (G1–G4), BUSINESS_MODEL, ARCHITECTURE, BRAND, manifest and source.
- Researched official docs: BAL page, FGS bg-start, FGS types, Play Accessibility page (10964491), AAPM page, restricted settings help. Android 17 AAPM accessibility restriction only from press coverage.
- Built debug-only spike (app.aurora.spike): accessibility detection, activity Shield, overlay Shield, Intent reflect step, status panel with disclosure, usage-stats latency probe.
- Emulator matrix on aurora_pixel API 36 (see docs/ENFORCEMENT_FEASIBILITY.md section 3).
- Wrote docs/ENFORCEMENT_FEASIBILITY.md. Updated ROADMAP gate rows G1/G3/G4, 3.5 Shield row, Phase 3 tasks 2 and 4, limitations, instrumented testing note.

## Current work
- None.

## Remaining work
- User approval to commit. Phase 2B/3 not started (needs approval).
- G1 still needs physical devices, other API levels, release-build latency (doc section 9).

## Technical decisions
- Spike lives in app/src/debug + app/src/testDebug (excluded from release) instead of a spike/g1 branch, since commits need approval. User to decide.
- Activity Shield in own task is the recommended mechanism; overlay rejected as primary; UsageStats rejected for detection.
- Shield must re-assert on every protected window event (apps can launch over it in BAL grace period).

## Files modified
- New: app/src/debug/AndroidManifest.xml, res/xml/spike_shield_service.xml, res/values/spike.xml
- New: app/src/debug/java/app/aurora/spike/{Spike,SpikeShieldService,SpikeShieldActivity,SpikeControlActivity}.kt
- New: app/src/testDebug/java/app/aurora/spike/SpikeTest.kt
- New: docs/ENFORCEMENT_FEASIBILITY.md
- Modified: docs/ROADMAP.md (targeted edits only)
- graphify-out/ created by `graphify update .` (untracked)

## Tests and results
- testDebugUnitTest: LogicTest 4/4, SpikeTest 5/5 pass. assembleDebug, assembleRelease, lintDebug pass (no spike lint warnings).
- Release APK manifest contains no spike entries.
- Emulator: Shield via BAL_ALLOW_TOKEN; Close/Back to home; Recents; Intent grant; revoke detected; force-stop removes service from enabled list; kill -9 restart 1-3 s; crash restart ~11 s; reboot reconnect ~29 s; call + emergency dialer OK; overlay works but weaker.
- Latency not meaningful on emulator (Clock hot start ~1 s): activity Shield p50 2179 ms, p90 3873 ms (n=27, debug).

## Errors or blockers
- No physical device available. Only API 36 system image installed.

## Exact next steps
1. User reviews diff and decides: commit spike in src/debug or move to spike/g1 branch.
2. Run doc section 9 prerequisites on physical devices before starting Phase 3.
