# Handoff: Aurora business model and roadmap update

- Task ID: aurora-business-model-and-roadmap-update-20261028-143309
- Task name: Aurora business model and roadmap update
- Current agent: Claude Code (Opus 5.5)
- Status: completed
- Last updated: 2026-10-28 15:40

## Objective
Write docs/BUSINESS_MODEL.md and update docs/ROADMAP.md for Free/Plus model, entitlements, billing in Phase 11; no app code

## Completed work
- New docs/BUSINESS_MODEL.md: principles (same protection strength, limits at configuration time), Free/plus tables, India pricing + illustrative net revenue (unverified), definitions of protected items, modes (Flexible/Friction/Cooldown/Intent/Only during focus), Focus profiles, Lockdown + 120-min ledger rules, limit rules, schedules, insights/goals/gentle streaks/challenges, counting table, entitlement design (plan/Plan.kt), downgrade, cloud sync conflict, Android limitations, open questions Q1-Q9.
- Rewrote docs/ROADMAP.md: 13 phases, Phase 2 = groundwork + entitlement core; 3 Shield (5 items); 4 modes + Intent (3); 5 Focus + profiles (1); 6 schedules/limits/Lockdown; 7 Insights basic/advanced; 8 Light; 9 Hardening; 10 Closed beta; 11 Billing; 12 Release 1.0; 13 web + content (1.1). Gates G1-G13 (G13 Play India pricing new). "Strict mode" renamed Lockdown. D3 superseded, D10 settled, D14 new.
- README links BUSINESS_MODEL.md.
- Baseline: ./gradlew testDebugUnitTest lintDebug passed (exit 0). JDK 21, gh logged in.

## Current work
- none

## Remaining work
- User confirms Q1-Q9 definitions (terms were new to repo).
- Commit/merge docs (branch docs/roadmap; this round uncommitted).

## Technical decisions
- decide() never reads plan; plan-parity test. Limits checked in ViewModel add/enable actions + Lockdown start.
- Lockdown is only no-bypass state; minutes reserved at start, refunded on external early end.
- Billing after closed beta so G9 testing period overlaps Phase 11.

## Files modified
- docs/BUSINESS_MODEL.md (new), docs/ROADMAP.md, README.md. No app code.

## Tests and results
- testDebugUnitTest + lintDebug green on current code.

## Errors or blockers
- none

## Exact next steps
1. Commit docs; merge docs/roadmap to main.
2. Branch phase-2 from main; start with CI workflow + plan/Plan.kt + PlanTest, then UI/screenshot tests (D8), permission helpers, Play Console account.
