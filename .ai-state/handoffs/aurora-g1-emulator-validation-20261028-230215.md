# Handoff: Aurora G1 emulator validation

- Task ID: aurora-g1-emulator-validation-20261028-230215
- Task name: Aurora G1 emulator validation
- Current agent: Claude Opus 5.5
- Status: in progress (waiting for user to turn the service OFF for the revoke test)
- Last updated: 2026-10-28 23:56

## Objective
Install g1 build on aurora_pixel, get manual accessibility consent, run harness, record emulator-only evidence

## Completed work
- g1 Kotlin source fix (commit 2960d09, local). g1 APK installed, not debuggable, spike present.
- First enable turned off after 19.4 s (settings change, not crash; floating-button shortcut was assigned). User re-enabled with shortcut off; stable from uptime 21770 s through all runs.
- Runs (g1-results/emulator-5554/, report-before-revoke.txt):
  warm 50: ok 46, timeout 4, miss 0; exposure median 1349.6, P90 1759.4, P95 >2000; detect median 60; present median 1247.
  cold 20: ok 20; exposure median 1002.7, P90 1458.9, P95 1513.9, max 1564.9.
  rapid 0.4 s: 21 opens, ok 6, timeout 7, miss 8 (misses = 3 superseded + 5 non-fresh; every Clock event was shielded, 18/18).
  rapid 2.0 s: 21 opens, ok 20, timeout 1, miss 0.
  crash (am crash): died 22643.159, reconnected 22644.434 (1.28 s), stayed enabled.
  after-crash 5: ok 3, timeout 2 (one corrected from missed, see note).
  Shield in front at every check (0 not-in-front).
- Cause of latency: emulator uses SwiftShader (CPU GPU); gfxinfo median frame 300 ms, 98% janky. FrameMetrics cross-checked with Compose frame log: median diff 3.8 ms.

## Harness defects fixed (uncommitted on spike/g1-harness, tools/g1/g1.sh)
1. Exposure percentiles over ok only (survivor bias): now all opens, failures as >2000.
2. rapid gap hard-coded 0.4 s: now G1_RAPID; scenario label carries the gap.
3. SETTLE 2.5 s lost one late timeout row: now 3.5 s; one row corrected with a note event.

## Remaining work
- Revoke test with the user (baseline rows=114, uptime 22730.13).
- Doc section 10.5/10.6, commit locally, unit/lint/debug/release builds.

## Exact next steps
1. After the user says OFF: check enabled setting and unbind/destroyed rows after row 114; open Clock once (expect no Shield). Ask the user to turn it ON; check the connected row.
