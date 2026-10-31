# Handoff: Backdrop mountain left side visibility

- Task ID: backdrop-mountain-left-side-visibility-20261031-091324
- Task name: Backdrop mountain left side visibility
- Current agent: Claude Code
- Status: completed
- Last updated: 2026-10-31

## Objective
Make the left part of the mountain in the app backdrop image as visible and clear as the middle and right parts

## Completed work
- REVERTED at user request: user preferred the original near-black massif. Painting code, art, drawables, docs and screenshots are back to the original; everything below describing the relit massif no longer applies.
- Relit the left massif in brand/tools/aurora_art.py with the right ridge's snow tones.
- Re-exported aurora_night, aurora_still and aurora_mask; re-recorded Roborazzi screenshots.
- Updated the massif description in the brand bible and 13-the-ribbon.md.

## Current work
- None

## Remaining work
- None

## Technical decisions
- Fix the painting, not the Compose layer: the massif was painted #060709 to #101215.
- Left tone uses the same colours as the right ridge (#0B0D11 to #565C64), mirrored x for shading, and fades out across u 0.24 to 0.52 so phone crops still show the lit slope.
- Land mask shape unchanged, so the live sky is unaffected.

## Files modified
- brand/tools/aurora_art.py, brand/artwork/aurora_{night,still,mask}.png
- app/src/main/res/drawable-nodpi/aurora_{night,still,mask}.webp
- docs/design-studio/AURORA_BRAND_BIBLE.md, docs/design-studio/13-the-ribbon.md
- app/src/test/screenshots/*

## Tests and results
- recordRoborazziDebug: 292 unit tests, 0 failures, 0 errors. home_Dark screenshot and the still reviewed by eye.

## Errors or blockers
- None

## Exact next steps
1. None. Nothing committed. aurora_night.png matches the pre-change file by SHA-1.
