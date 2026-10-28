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

## Status: Phase 1 (foundation)

### Works today

- **Onboarding.** Three steps covering why, how and privacy. Usage access is explained and optional.
- **Today.** Shows the current session state, a primary focus action, and a summary of
  protected apps and screen time.
- **Focus sessions.** Choose a length (15–90 min) and an optional intention. A live countdown runs
  on the horizon arc, with end-early confirmation and a completion moment. Sessions are stored as
  timestamps, so they survive app restarts.
- **Protect.** Lists the real launchable apps on the device, with search. Your selection is saved on the device.
- **Insights.** Shows real screen time since midnight and your most-used apps, computed on the device from
  Android usage events. This requires Usage access.
- **Settings.** Theme (system, light or dark), default focus length, reduce motion, permission
  status, privacy, replay introduction, licenses and version.
- Light and dark themes, edge-to-edge layout, reduced-motion support, and screen-reader labels and roles.

### Not yet built (shown honestly in the app)

- **App blocking.** Nothing is blocked yet. The Protect screen, Today and Focus all say this.
- **Pause before opening, time limits, schedules and strict mode.** These appear in the app as
  "soon" rows and can't be tapped.
- **Website filtering, the launcher, and the iOS and desktop apps.**
- **A notification when a session ends while aurora is closed.** The completion screen appears
  the next time you open the app.

## Requirements

- JDK 17 or newer. The project was built with Temurin 21.
- Android SDK with **platform 37** (compile) and build-tools 36.x. Target SDK is 36 and min SDK is 26.
- Use the Gradle wrapper that comes with the repository (Gradle 9.8.2). Android Gradle Plugin is 9.4.1 and Kotlin is 2.4.21.

To use Android Studio, open the folder; it fetches everything it needs. To use the command line only,
follow these steps:

```sh
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # or set ANDROID_HOME
```

## Build, test, run

```sh
./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit tests (usage aggregation, session maths, formatting)
./gradlew lintDebug            # Android lint
./gradlew installDebug         # install on a running emulator or connected device
adb shell am start -n app.aurora/.MainActivity
```

To try Insights without going through Settings, grant Usage access to a debug build:

```sh
adb shell appops set app.aurora GET_USAGE_STATS allow
```

## Project layout

```
app/src/main/java/app/aurora/
  data/            preferences (DataStore), installed apps, screen time, formatting
  ui/theme/        colours, type, spacing, radius, motion tokens
  ui/components/   the aurora design system
  ui/<screen>/     onboarding, home, protect, focus, insights, settings
docs/              BRAND.md, ARCHITECTURE.md, screenshots, font licence
```

The Manrope typeface is bundled under the SIL Open Font License (`docs/fonts-OFL.txt`).
