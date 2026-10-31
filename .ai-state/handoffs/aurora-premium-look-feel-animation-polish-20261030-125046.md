# Handoff: Aurora premium look, feel and animation polish

- Task ID: aurora-premium-look-feel-animation-polish-20261030-125046
- Task name: Aurora premium look, feel, motion and product design
- Current agent: Claude Opus 5.5
- Status: completed
- Last updated: 2026-10-30

## Objective
Raise Aurora's brand, look, feel, motion and product design to a premium, Opal-beating level while keeping First Light.

## Completed work
- New `ui/components/Motion.kt`: `RollingClock` (rolling digits, one TalkBack value), `Modifier.pressable(shape)` (spring press, clipped ripple), `ProvideIntro` + `Modifier.rise()` (staggered first-show rise by vertical position), `rememberFill` (sweep from zero on first show, then follow).
- Home: live `NowHero` (large countdown, name and end time, progress) replaces the tagline while a timed run is active, cross-fading in and out; RunCard hides its duplicate timer (`inHero`). The sky flares for the first 6s of a run. The choices become two compact tiles when Home has something better (stacked at large text). The empty metric drops the grey "0m". The insights line moves into the progress card; the separate insights button is removed. Cards use `animateItem()` and `rise()`. A Confirm haptic plays on quick start.
- Tab bar: a single pill and gold point glide on a spring, the icon lifts, `SegmentTick`, and the page fades out beneath the dock.
- Navigation: tabs fade in with a 0.985 scale; pushed screens slide 1/6 while the one below steps back.
- Buttons: spring press 0.96, and a `ContextClick` haptic on Primary.
- Timers: RollingClock on Home, the Protect session, Focus, the Shield countdown and Strict.
- Fills: ProgressLine, HabitRing, LightRing and HorizonArc sweep in.
- Shield stage: the icon lands on a spring, the halo breathes, the content rises.
- Docs: Motion table in docs/design-studio/04-design-system.md.
- Strings: added home_now_eyebrow, home_now_detail, home_now_spoken; removed the unused today_see_insights.

## Second pass (user: "yes do that")
- Every `AuroraScreen` with art (the Shield, Intent and Track tabs, Plus, the Focus run) now opens with ProvideIntro and wraps each item in `Column(Modifier.rise())` (private `RisingScope` in Scaffold.kt). It is a Column, not a Box, so items with several children still stack. Home's animateItem wrappers are Columns too.
- `app/src/main/baseline-prof.txt` adds wildcard rules for app/aurora. After R8, 657 of 668 app classes are in the release profile (checked against mapping.txt). Emulator cold start of the release build: about 302ms without the profile against about 267ms with it (5 runs each). The debug build is far slower; that was the lag seen earlier.
- The emulator GPU is software (gfxinfo GPU p50 4950ms), so its smoothness can't be judged; it needs a real phone.
- The emulator holds the debug build again. Its UninstallGuard device admin is active from earlier testing, which blocks `adb uninstall`.

## Files modified
app/src/main/baseline-prof.txt(new), ui/components/{Motion.kt(new),Buttons.kt,Scaffold.kt,Parts.kt,Brand.kt,FirstLight.kt}, ui/home/{HomeScreen.kt,HomeSections.kt}, ui/AuroraNavHost.kt, ui/track/Track.kt, ui/library/Parts.kt, ui/settings/SettingsScreen.kt, ui/protect/ProtectParts.kt, ui/focus/FocusScreen.kt, ui/strict/StrictScreens.kt, protect/ShieldContent.kt, res/values/strings.xml, docs/design-studio/04-design-system.md, app/src/test/screenshots/*

## Tests and results
- testDebugUnitTest: 321 tests, 0 failures. lintDebug: clean (4 baseline). assembleDebug OK.
- recordRoborazziDebug re-run; reviewed home, home_active (Dark, Light, LargeText), home_ready, home_navigation, shield_cooldown, track.
- Emulator recording: the tab pill glides, Home rises in order, and the horizon arc sweeps.

## Errors or blockers
- Cold start and the first switch to Home lag about 300ms on the emulator debug build. Untested on a release build or a real phone. A baseline profile (androidx.profileinstaller + baselineprofile plugin) would help.

## Exact next steps
1. Test on a real phone: feel of the haptics, spring tuning, 120Hz smoothness.
2. Commit when the user asks; the tree mixes earlier sessions' uncommitted work.
