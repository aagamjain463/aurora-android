# Brand and design 10/10 polish

- Task ID: brand-and-design-10-10-polish-20261030-091020
- Current agent: Claude Opus 5.5
- Status: completed
- Last updated: 2026-10-30

## Objective
Inspect Aurora's brand identity, product design and look; implement improvements toward a 10/10 brand.

## Completed work
- Audited brand bible, studio docs 07/08, approved references 01-05, all dark baselines, light Home, store art.
- Home is state-driven: Shield attention, finish, Focus reports and running configurations now sit under
  the headline (were buried below the fold). Normal Use is a neutral row while a run or report shows, so
  the screen keeps one filled action.
- Store screenshot 01 no longer repeats the in-phone headline; caption is the descriptor.
- Brand bible section 6 records both rules.

## Technical decisions
- Split `homeSections` into `homeNow` (returns busy flag) and `homeLater` in HomeSections.kt.
- A finish alone keeps Normal Use primary (finish card has no action).
- Known gap: an enabled-but-not-running schedule with Shield off still shows two filled actions.

## Files modified
- app/src/main/java/app/aurora/ui/home/HomeSections.kt, HomeScreen.kt
- brand/tools/store.sh, brand/store/out/*
- docs/design-studio/AURORA_BRAND_BIBLE.md
- app/src/test/screenshots/* (re-recorded)

## Tests and results
- ./gradlew recordRoborazziDebug: 315 tests, 0 failures. Inspected home_active, home_finished, store 01.
- Lint not run this pass.

## Errors or blockers
- None. Several unrelated baselines changed from known nondeterminism (async icons, live dates).

## Exact next steps
- Founder review; capture store 02/03 on a phone (placeholder icons in Robolectric).
