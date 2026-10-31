# Handoff: App-wide animations and moving aurora backdrop

- Task ID: app-wide-animations-and-moving-aurora-backdrop-20261031-005541
- Task name: App-wide animations and moving aurora backdrop
- Current agent: Claude Code (Opus 5.5)
- Status: in progress
- Last updated: 2026-10-31 00:55:41

## Objective
Add visible premium animations throughout the app: living aurora backdrop, screen transitions, entrance and interaction motion.

## Completed work
- (none yet)

## Current work
- (starting)

## Remaining work
- (to be determined)

## Technical decisions
- (none yet)

## Files modified
- (none yet)

## Tests and results
- (none yet)

## Errors or blockers
- none

## Exact next steps
1. (to be determined)

<!-- Observable progress between checkpoints: `ai-handoff recorded app-wide-animations-and-moving-aurora-backdrop-20261031-005541.md` -->


## Checkpoint 2026-10-31T01:06:28 (status: completed; agent: Claude Code, Opus 5.5)

Objective: visible premium animations across the app, with the aurora in the backdrop moving like a real aurora.

Completed:
- Sky: SkyPace (about 15x faster, brightness unchanged), one shared SkyClock across screens, sky wakes once per process, slower star twinkle (Sky.kt).
- New AuroraGlow gives plain screens a live ribbon through TopAurora; PageGlow drifts (capped to about 15 redraws/s).
- Openings: rise is 44dp with a slight scale, replays when a tab is chosen (TabArrival), not on back. Blocks scrolled to or loaded late rise in by themselves.
- Pushed screens slide in with the one below sinking to 0.92; tabs fade over each other with no sky dip; stronger Focus, step flow, onboarding and dock motion.
- Press springs, more frequent knot on primary actions, rimLight on the secondary Home choice, breathing halo on AuroraLight.
- Brand bible section 8 and 13-the-ribbon.md updated.

Files: ui/AuroraNavHost.kt; ui/components Sky, Motion, Buttons, Brand, Glass, Light; ui/home/HomeSections.kt; ui/library/Parts.kt; ui/onboarding/OnboardingScreen.kt; docs; recorded screenshots.

Tests: testDebugUnitTest passed; recordRoborazziDebug ran, 103 images changed, 4 inspected. Verified on emulator by frame capture: sky moves, tab stagger, push, scroll reveal, no crashes.

Open: the emulator uses software rendering (SwiftShader, about 200ms per frame), so smoothness and battery are NOT verified on real hardware. Sky pace could be tuned through SkyPace. Nothing committed.

Next steps: run on a physical phone, judge SkyPace and frame rate; commit when approved.
