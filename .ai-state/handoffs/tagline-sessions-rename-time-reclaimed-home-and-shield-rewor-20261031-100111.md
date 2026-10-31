# Handoff: Tagline, sessions rename, Time Reclaimed, home and Shield rework

- Task ID: tagline-sessions-rename-time-reclaimed-home-and-shield-rewor-20261031-100111
- Task name: Tagline, sessions rename, Time Reclaimed, home and Shield rework
- Current agent: Claude Code
- Status: completed
- Last updated: 2026-10-31

## Objective
Apply founder copy changes, rename configurations to sessions, overhaul Time Reclaimed, restructure Home and Shield menu

## Completed work
- Copy: tagline, description, Custom Sessions, Time Reclaimed, Shield and Intent headlines, Pause and Use intentionally step text.
- All user-visible "configuration" text is now "session" (strings.xml, current docs, store listing, privacy policy). CopyTest bans the word.
- Home order: hero, Normal/Custom, Time Reclaimed, what is running or needs attention, all Custom Sessions, Scheduled, Always, Goals. Your progress card removed.
- Time Reclaimed shows Today, This week, Overall, from measured time away only. Finished days persist in `History.reclaimed`.
- Shield tab: Web & content (accent edge) then Daily limits directly under Create a Shield; More protection section removed.

## Current work
- None.

## Remaining work
- None requested.

## Technical decisions
- "Sheet" in the request read as "Shield" (speech-to-text).
- Kotlin identifiers, string keys and stored JSON keep "config" names: strings.xml says keys are stable, and renaming stored fields risks user data.
- Android keeps usage events about a week, so `UsageKeepDays = 6`: older unmeasured days are not counted rather than guessed. Overall starts from the 6 days before first run of this version.
- Week starts Monday, matching the removed progress card.
- Custom Sessions now list all saved sessions, also while something runs, minus the ones running. Unused Shields and Intents still appear there, as before.
- `formatDuration(0)` now returns "0m"; it said "Under 1m".
- Goals card stays on Home: not part of the request.

## Files modified
- app/src/main/res/values/strings.xml
- app/src/main/java/app/aurora/data/TimeAway.kt, data/Format.kt, progress/Progress.kt
- app/src/main/java/app/aurora/ui/home/HomeScreen.kt, ui/home/HomeSections.kt, ui/AuroraNavHost.kt
- app/src/main/java/app/aurora/ui/library/ShieldScreens.kt, ui/components/Rows.kt
- Tests: CopyTest, TimeAwayTest, LogicTest, ScreenshotTest, FormFactorScreenshotTest, NavigationTest
- Docs: README, PRODUCT, STORE_LISTING, privacy-policy, AURORA_COPY_REVIEW, AURORA_BRAND_BIBLE, 02-brand-identity, 04-design-system
- app/src/test/screenshots/ re-recorded

## Tests and results
- `./gradlew testDebugUnitTest`: pass. `recordRoborazziDebug`: pass. `compileDebugAndroidTestKotlin`: pass.
- Looked at home_ready, library_shield, intent_library screenshots. Not run on a real device.

## Errors or blockers
- None. Nothing committed.

## Exact next steps
1. Founder checks Time Reclaimed on a device after a real session; commit when happy.

<!-- Observable progress between checkpoints: `ai-handoff recorded tagline-sessions-rename-time-reclaimed-home-and-shield-rewor-20261031-100111.md` -->
