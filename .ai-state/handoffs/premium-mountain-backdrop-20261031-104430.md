# Handoff: premium mountain backdrop

- Task ID: premium-mountain-backdrop-20261031-104430
- Task name: premium mountain backdrop
- Current agent: Claude Code
- Status: completed (reverted at founder request)
- Last updated: 2026-10-31

## Outcome
Founder rejected the new mountains and asked for the previous backdrop. brand/tools/aurora_art.py, brand/artwork/aurora_*.png and res/drawable-nodpi/aurora_*.webp restored byte-for-byte from the pre-task backup; screenshots re-recorded; testDebugUnitTest passed. Everything below describes the rejected version. Do not re-apply it without the founder asking.

## Objective
Improve the mountain look in the backdrop image: more premium, Aurora-like, on-brand.

## Completed work
- Replaced the flat ridge lines in brand/tools/aurora_art.py with lit height fields (range_of, ridged): summit pyramids, radiating gullies, moon from the left with cast shadows, snow fields, faint aurora green on upward faces and crests.
- Composition kept: dark massif left, snow ridges right, far peak in the V, horizon 0.44.
- Mountains reflect a little more strongly near the shore.
- Sky mask: red channel now takes the block maximum, removing a dark halo of unlit sky round crests.
- Re-exported drawables and re-recorded screenshots.

## Current work
None.

## Remaining work
None for this task. Store images in brand/store were not re-rendered; they read aurora_still.png, so run store.sh when store assets are next needed.

## Technical decisions
- Stayed in the existing numpy script, no new dependency. Sky.kt and Backdrop.kt unchanged.
- Snow ridge moon strength lowered to 0.54 so headlines stay legible over it on Balanced screens.

## Files modified
brand/tools/aurora_art.py, brand/artwork/aurora_{night,mask,still}.png, app/src/main/res/drawable-nodpi/aurora_{night,mask,still}.webp, app/src/test/screenshots/*

## Tests and results
./gradlew recordRoborazziDebug passed. ./gradlew testDebugUnitTest passed. Viewed home, focus, insights, onboarding (Dark).

## Errors or blockers
Not checked on a physical device. Onboarding screenshot shows a horizontal background seam about 548dp down; this change does not touch that code and its origin was not investigated. Another session was editing MainActivity.kt and Unveil.kt in the same tree during recording.

## Exact next steps
None.
