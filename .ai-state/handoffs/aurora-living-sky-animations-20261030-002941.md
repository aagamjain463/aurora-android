# Handoff: Aurora living sky animations

- Task ID: aurora-living-sky-animations-20261030-002941
- Task name: Aurora living sky animations
- Current agent: Claude Code (Opus 5.5)
- Status: completed
- Last updated: 2026-10-30 01:15

## Objective
Animated aurora curtain sky (AGSL) tied to app state: running drift, shield hold, finish flare; earned skies; brand bible update

## Completed work
- `AuroraSky` + `Sky` enum (Quiet, Lit, Alive, Flare) in `ui/components/Sky.kt`: AGSL RuntimeShader (API 33+), two curtains, rays, folds; masked by the painting's own sky so light stays behind ridges and off the ring's core. Night uses Screen blend, day uses a SrcOver veil. Earned light: strength/height/warmth scale with `History.light().glow`.
- Motion: drifts only in Alive/Flare, 30 fps throttle, paused when not RESUMED or the hero is scrolled away, uses `withInfiniteAnimationFrameNanos` (test-safe). Flare = 0.9s surge settling over 3.6s. First-show fade-in. Reduced motion: still, no surge.
- `first_light.py` now also writes quarter-size sky masks (art PNGs verified byte-identical); `export.sh` exports them; 4 masks in `drawable-nodpi/first_light_sky_*.webp` (~1.4 KB each).
- Wiring: `FirstLightBackdrop(sky, glow)`, `AuroraScreen(sky)`. Home = `skyNow()` (HomeSections.kt) + week glow; Focus Setup/Running/Complete = Quiet/Alive/Flare; FocusRunScreen = Alive; Shield = Lit; Onboarding = Lit, last page Flare.
- Docs: brand bible §5 (living sky table), §8 motion, §11 do/don't; 04-design-system; 03-first-light-artwork; DESIGN_DECISIONS #21.

## Current work
- None.

## Remaining work
- Real-device performance check on a low-end phone (emulator is slow with or without the sky).
- Optional: shareable "your sky" card; sky inside the LightRing window.

## Technical decisions
- Shader over Lottie/Rive/video: procedural like the art, crisp, theme-aware, reacts to state, no new dependency.
- Alpha-0 additive output was dropped by the renderer; switched to explicit BlendMode.Screen.
- API 26–32 keep the still painting (no fallback renderer).

## Files modified
- app/src/main/java/app/aurora/ui/components/Sky.kt (new), FirstLight.kt, Scaffold.kt
- ui/home/HomeScreen.kt, HomeSections.kt; ui/focus/FocusScreen.kt; ui/strict/StrictScreens.kt; protect/ShieldContent.kt; ui/onboarding/OnboardingScreen.kt
- res/drawable-nodpi/first_light_sky_{night,day,night_centered,day_centered}.webp (new)
- brand/tools/first_light.py, brand/tools/export.sh, brand/artwork/first_light_sky_*.png (new)
- docs/design-studio/AURORA_BRAND_BIBLE.md, 04-design-system.md, 03-first-light-artwork.md, DESIGN_DECISIONS.md
- app/src/test/screenshots/* re-recorded

## Tests and results
- recordRoborazziDebug: BUILD SUCCESSFUL. Inspected Home quiet/active (dark/light), Shield, Focus, FocusRun, onboarding.
- verifyRoborazziDebug: ~12-14 flaky failures per run, set varies; inspected diff is countdown digit (5:00:01 vs 5:00:00), pre-existing wall-clock flake, not the sky. Non-screenshot unit tests pass.
- lintDebug: 4 pre-existing errors in unrelated files; none in changed files.
- Emulator (API 36): sky renders and drifts. Emulator frame time 300ms without sky, 350ms with (emulator slow regardless).

## Errors or blockers
- None. Concurrent Gradle runs in the same project caused one test-results collision; rerun succeeded.

## Exact next steps
- Founder review on device; commit when approved.
