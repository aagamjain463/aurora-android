# Handoff: Track overhaul with Progress, Goals and Usage

- Task ID: track-overhaul-with-progress-goals-and-usage-20261031-102118
- Task name: Track overhaul with Progress, Goals and Usage
- Current agent: Claude Code
- Status: completed
- Last updated: 2026-10-31 10:21:18

## Objective
Rebuild Track tab with Progress, Goals and Usage sub-menus and working goals for sessions and limits

## Completed work
- Track headline "See your progress. Own your growth."; no description. Segmented Progress, Goals, Usage.
- Progress: minimal XP card; Today/This week/Overall with sessions started, succeeded, time set, Time Reclaimed; last 3 session results.
- Goals: builder (name, then session + tracking, or limit + apps/whole screen time + per day/week), progress line, used/left, status, track record, delete.
- Usage: screen time, day bars, most used.
- Habits and old goal kinds removed; Goals card removed from Home; docs updated.

## Current work
- None.

## Remaining work
- None requested.

## Technical decisions
- "Every Monday per week" read as weekly, resetting each Monday.
- Session time counts only while the Shield service was bound; sessions-per-week counts Outcome.Succeeded only.
- Failure: limit fails when passed; session fails when period ends short, except the period the goal was created in.
- `Rules.trackGoals` is a new field, so old `goals` JSON can't break strict Rules parsing. Phone had no habits or goals, so no migration.
- `History.goalUsage` stores finished days; today never stored. Clear history resets goal createdAt so cleared days don't read as failed.
- Sessions and time set count by the day a session began. "Counted by Aurora" metrics removed from Track.

## Files modified
- New: progress/Goals.kt, ui/track/GoalScreens.kt, test progress/GoalsTest.kt. Rewritten: ui/track/Track.kt, ui/insights/InsightsScreen.kt.
- Deleted: progress/Habits.kt, ui/track/HabitScreens.kt, HabitsTest, habit screenshots.
- Edited: ConfigActions, Rules, Progress, Runs, HomeScreen, ConfigureScreen, AuroraNavHost, SettingsScreen, strings, tests, docs.

## Tests and results
- recordRoborazziDebug, testDebugUnitTest, compileDebugAndroidTestKotlin: pass. NavigationTest covers create and delete of a session goal.
- Installed on phone and emulator; Track sub-tabs opened on phone, no crash. No goal created on the phone, so real-usage limit progress isn't seen on device.

## Errors or blockers
- None. Nothing committed.

## Exact next steps
1. Founder creates a real goal on the phone; commit when happy.

<!-- Observable progress between checkpoints: `ai-handoff recorded track-overhaul-with-progress-goals-and-usage-20261031-102118.md` -->
