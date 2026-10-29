# aurora-android

aurora is a premium digital-wellbeing app. It helps you take back control of your phone through
intentional use.

This repository is the native Android app: Kotlin and Jetpack Compose, local-first, with no
account, no analytics and no `INTERNET` permission.

| Today | Protect | Focus | Complete | Insights |
| --- | --- | --- | --- | --- |
| ![Today](docs/screenshots/today.png) | ![Protect](docs/screenshots/protect.png) | ![Focus](docs/screenshots/focus-running-dark.png) | ![Complete](docs/screenshots/focus-complete-dark.png) | ![Insights](docs/screenshots/insights.png) |

- Brand and design system: [`docs/BRAND.md`](docs/BRAND.md)
- Architecture and decisions: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- Configurations, four-tab navigation and Habits: [`docs/CONFIGURATIONS.md`](docs/CONFIGURATIONS.md)
- Roadmap to 1.0 and beyond: [`docs/ROADMAP.md`](docs/ROADMAP.md)
- Plans and limits (aurora Free and aurora plus): [`docs/BUSINESS_MODEL.md`](docs/BUSINESS_MODEL.md)
- The complete product, verification and launch dependencies: [`docs/PRODUCT.md`](docs/PRODUCT.md)

## Status: configurations and Habits (`feat/aurora-configurations-habits`)

Home activates Normal Use or Custom Configurations. Shield and Intent hold reusable presets;
Track shows Habits, configuration performance, screen time and progress. Configurations support
starting now, later, on recurring schedules or Always, with optional Focus. Always Block keeps
its commitment rules. Existing billing, data controls and optional Web & content protection remain.

What is verified, what isn't, rule precedence, and what still needs phones, Play Console and policy
review: [`docs/PRODUCT.md`](docs/PRODUCT.md). Emulator screenshots: `docs/screenshots/emulator-2026-10-29/`.

## Requirements

- JDK 17 or newer. The project was built with Temurin 21.
- Android SDK with **platform 37** (compile) and build-tools 36.x. Target SDK is 36 and min SDK is 26.
- Use the Gradle wrapper that comes with the repository (Gradle 9.8.2). Android Gradle Plugin is 9.5.0 and Kotlin is 2.4.21.

To use Android Studio, open the folder; it fetches everything it needs. To use the command line only,
follow these steps:

```sh
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # or set ANDROID_HOME
```

## Build, test, run

```sh
./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew installDebug         # install on a running emulator or connected device
adb shell am start -n app.aurora/.MainActivity
```

### Checks

CI (`.github/workflows/android.yml`) runs these on every push and pull request. Run the same
locally before pushing:

```sh
./gradlew testDebugUnitTest    # unit tests, Robolectric UI tests, and a render of every screen
./gradlew lintDebug            # Android lint; new warnings are errors (baseline: app/lint-baseline.xml)
./gradlew assembleDebug assembleRelease
```

These need a device or the local baselines, so CI doesn't run them:

```sh
./gradlew connectedDebugAndroidTest   # the same UI tests on a running emulator (overwrites aurora's app data)
./gradlew verifyRoborazziDebug        # compare every screen with app/src/test/screenshots/ (macOS baselines)
./gradlew recordRoborazziDebug        # re-record the baselines after an intended UI change
```

To try Insights without going through Settings, grant Usage access to a debug build:

```sh
adb shell appops set app.aurora GET_USAGE_STATS allow
```

## Project layout

```
app/src/main/java/app/aurora/
  data/            preferences (DataStore), installed apps, screen time, special access, formatting
  plan/            aurora Free and aurora plus limits, and the Lockdown budget (pure Kotlin)
  ui/theme/        colours, type, spacing, radius, motion tokens
  ui/components/   the aurora design system
  ui/<screen>/     onboarding, home, protect, focus, insights, settings
app/src/test/       unit tests, screenshot tests and their baselines (screenshots/)
app/src/sharedTest/ UI tests that run under Robolectric and on a device
docs/              BRAND.md, ARCHITECTURE.md, ROADMAP.md, BUSINESS_MODEL.md, ENFORCEMENT_FEASIBILITY.md,
                   screenshots, font licence
```

The Manrope typeface is bundled under the SIL Open Font License (`docs/fonts-OFL.txt`).
