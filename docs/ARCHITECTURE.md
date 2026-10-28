# aurora Android architecture

The architecture is intentionally small. Phase 1 is one Gradle module, a handful of
repositories, and one ViewModel per screen. Add structure when a real need appears, not before.

## Stack

| Concern | Choice | Why |
| --- | --- | --- |
| Language | Kotlin 2.4 | AGP 9 compiles Kotlin itself. The root build pins the Kotlin version. |
| UI | Jetpack Compose + Material 3 | M3 supplies accessible primitives (switch, dialog, text selection). aurora supplies the look. |
| Presentation | MVVM: `ViewModel` + `StateFlow` | Survives configuration changes. Screens are functions of state. |
| Navigation | Navigation Compose 2.x, string routes | Six destinations don't need type-safe routes or a serialization plugin. |
| Persistence | DataStore Preferences | Small key/value data, async and transactional. No database needed yet. |
| DI | Manual (`AuroraApplication`) | Four singletons. A framework would cost more than it saves. |
| Min / target / compile SDK | 26 / 36 / 37 | API 26 adds adaptive icons and variable fonts and covers almost every active device. Current AndroidX releases require compile SDK 37. Target stays at 36 until aurora is tested on Android 17. |

There are no analytics, crash-reporting SDKs, network clients or accounts. The app does not
declare the `INTERNET` permission.

## Layout

```
app/src/main/java/app/aurora/
├── AuroraApplication.kt   Dependency holder, auroraViewModel {}, rememberAccess()
├── MainActivity.kt        Edge-to-edge, theme selection, start destination
├── data/
│   ├── Preferences.kt     UserPreferences, FocusSession, PreferencesRepository, clockTicks()
│   ├── AppCatalog.kt      Real launchable apps (label + icon) via PackageManager
│   ├── ScreenTime.kt      Usage access check, today's foreground time, foregroundTime()
│   ├── Access.kt          Special access, AccessState, ProtectionStatus, AccessRepository
│   └── Format.kt          Duration and clock formatting
├── plan/
│   ├── Plan.kt            Plan, Allowance, Feature, FreeLimits, ProtectedItem, canProtect(), keepActive()
│   └── Lockdown.kt        LockdownReservation and the Free daily Lockdown budget
└── ui/
    ├── AuroraNavHost.kt   Routes, tabs, transitions, floating tab bar
    ├── theme/             Palette, semantic colours, type scale, Space/Radius/Motion tokens
    ├── components/        The design system (see docs/BRAND.md)
    ├── onboarding/  home/  protect/  focus/  insights/  settings/
```

Each screen file holds its `ViewModel`, its UI state class and its composables. Split a file only
when it gets hard to read.

## Data flow

```
DataStore ──► PreferencesRepository.preferences: Flow<UserPreferences>
                       │
PackageManager ──► AppCatalog          ──┐
UsageStatsManager ──► ScreenTimeRepo   ──┼─► XViewModel.state: StateFlow ──► @Composable screen
clockTicks() (1 Hz, only while visible) ─┘                                      │
                                                         user intent ◄──────────┘
                                                         vm.toggle()/start()/… ─► repository
```

- State is collected with `collectAsStateWithLifecycle` and shared with
  `WhileSubscribed(5s)`. The 1 Hz clock therefore stops when the screen is not visible.
- Screens that depend on permissions (Insights, Today, Settings, onboarding) re-check on
  `ON_RESUME`, through `AccessRepository` and `rememberAccess()`. When the person comes back from
  Android Settings, the UI is already correct.

## Key decisions

**Focus sessions are timestamps, not a running service.** A session is stored as
`startedAt + durationMs` and the UI derives the rest. It survives process death and reboots and
needs no foreground service or notification permission. The trade-off is that nothing happens
when a session ends while aurora is closed. aurora shows the completion the next time it opens.
Phase 5 adds an exact alarm and notification.

**The protected-app list is real but not enforced.** Apps come from `PackageManager` through a
`<queries>` launcher intent, not `QUERY_ALL_PACKAGES` (a Play-restricted permission). Choices
persist as package names in `UserPreferences.protectedApps`. Every surface that shows the list
says that blocking is not active yet.

**Screen time is computed, never stored.** `ScreenTimeRepository.today()` replays
`UsageEvents` since local midnight through `foregroundTime()`, a pure, unit-tested function. The
default home launcher is excluded. Nothing is persisted or sent anywhere.

**Tabs are Today, Protect and Insights. Settings and Focus are pushed.** Today is always the root
of the back stack, so the tab bar can `popUpTo(Today)` with save/restore state. The tab bar
floats over content and tab screens pad for it. Showing or hiding the bar never resizes
the content.

**No cloud backup.** `allowBackup="false"` and `data_extraction_rules.xml` exclude everything from
cloud backup, because behavioural data shouldn't leave the phone silently. On Android 12 and later, a
phone-to-phone transfer that the person starts can still carry it. Revisit this with an explicit,
opt-in export.

## Where blocking plugs in (Phase 2+)

The blocking engine reads two inputs that already exist:

- `UserPreferences.protectedApps`: what to protect.
- `UserPreferences.session`: whether a focus window is active and when it ends.

The likely shape is a `protect/` package with the following parts:

1. **Detection.** An `AccessibilityService` that watches foreground window changes. Use it only
   with a clear in-app disclosure and a Play Console declaration. Alternatively, poll
   `UsageStatsManager`, which needs no accessibility access but reacts more slowly.
2. **Intervention.** A full-screen aurora activity ("pause before opening") launched over the
   protected app.
3. **Status.** A `ProtectionStatus` flow (`Off` / `Active` / `NeedsPermission`) that replaces the
   "Preview" notice and the "No session running" pill. **The UI must claim "protected" only
   when this flow says `Active`.**
4. **Strict mode.** A commitment timestamp that hides "end session" and resists in-app bypasses.
   It cannot defeat uninstall, safe mode or `adb`, and the UI must not imply that it can. Device
   admin and device-owner capabilities are a separate decision, and the launcher product is the
   natural place for them.

Website filtering needs separate research: per-browser accessibility URL reading, or a local
VPN DNS filter. Both raise policy and privacy questions to settle before implementation.

## Special access and protection status (Phase 2)

`data/Access.kt` is the one place that reads aurora's special access.

| Access | Read with | Opens | Used by |
| --- | --- | --- | --- |
| `Usage` | AppOps `GET_USAGE_STATS` (`ScreenTimeRepository.hasAccess()`) | Usage access settings, deep-linked to aurora | Insights, Today, Settings, onboarding |
| `Accessibility` | `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` for `app.aurora.protect.ShieldService` | Accessibility settings | Nothing yet. The service arrives in Phase 3, so this always reads `Missing` |
| `Notifications` | `NotificationManagerCompat.areNotificationsEnabled()` | aurora's notification settings | Nothing yet (Phase 5) |

- `AccessState` is `Granted`, `Missing` or `Revoked`. `Revoked` means it was granted earlier in
  this visit and has since been turned off; Settings then says so in the Usage access row.
- `rememberAccess(access)` re-checks on every resume, so returning from Android Settings, or
  revoking access while aurora is in the background, is reflected at once.
- aurora never turns access on itself and declares no new permission for this. It doesn't request
  `POST_NOTIFICATIONS` or exact alarms until a phase uses them.
- `ProtectionStatus` separates permission from working protection: `Off`, `Blocked` (Android or the
  device maker won't let the service run), `NeedsPermission`, `NotRunning` (switch on, service not
  connected) and `Active`. The accessibility switch alone is never protection. Only `Active` may be
  described as protected. Phase 3 supplies the `connected` and `blocked` inputs from the service.

## Entitlements (Phase 2)

`plan/` implements `BUSINESS_MODEL.md` section 5 as pure Kotlin with no Android imports.

- `FreeLimits` holds every limit number: 5 protected items, 3 Intent apps, 3 daily limit rules,
  1 schedule, 1 Focus profile, 120 Lockdown minutes a day. aurora plus has none (`limit()` is null).
- `canAdd(plan, allowance, current)` is the check each feature's ViewModel runs when something is
  added or enabled. Removing never needs a check.
- `ProtectedItem.App.of()` and `ProtectedItem.Website.of()` validate and normalise items
  (`www.`, case, trailing dot, Unicode to ASCII). Apps and websites share one pool, and
  `canProtect()` counts distinct items, so an item in several rules counts once and an item that is
  already protected never needs a slot.
- `keepActive()` picks what stays active after a downgrade: the person's choices, then the oldest.
  The rest pause and are never deleted.
- **Lockdown budget** (`Lockdown.kt`). A Lockdown reserves its planned minutes when it starts
  (`reserveLockdown()`), charged to the local date of its start in the device's zone at that moment.
  - A Lockdown that crosses midnight finishes and counts toward its starting day.
  - Minutes are charged in start order, and time already covered by an earlier Lockdown is not
    charged again, so overlapping or restarted Lockdowns can't double-count or reset the budget.
  - `endedEarlyAt()` refunds the unused minutes when a Lockdown ends for a reason outside aurora's
    control. Each reservation rounds up to whole minutes.
  - After a time-zone change, today is the local date in the new zone, so the budget can come back
    early (travelling east) or stay spent (travelling west).
  - The ledger is wall-clock based. Setting the clock forward to a new date gives a fresh budget;
    this is accepted (`BUSINESS_MODEL.md` section 7). Setting it back never refunds minutes.
  - The functions only read a ledger. Phase 6 stores it in DataStore, so a restart changes nothing.
- No feature reads the plan yet, and there is no plan source. Every person is on Free until Phase 11.
  The debug-only switch to Plus (`ROADMAP.md` Phase 2, task 5) is deferred to the first phase that
  wires a limit, because until then it would switch nothing. There is no purchase UI.

## Debug experiments and production code

Spikes live only in `app/src/debug/` and `app/src/testDebug/`, on `spike/<gate-id>` branches, and are
never merged into `main`. The G1 enforcement spike is on `spike/g1`. Release builds never compile
`src/debug/`, and CI fails if a release APK contains the word "spike".

**G1 has not passed.** Interception works on one API 36 emulator only. Physical devices, OEMs,
other API levels, latency, split-screen, picture-in-picture, TalkBack and Play policy are untested
(`ENFORCEMENT_FEASIBILITY.md`). Phase 3 can't start until it passes. Nothing in Phase 2 depends on
G1: the access layer and `ProtectionStatus` are ready for whichever mechanism G1 selects.

## Testing

| Suite | Where | Runs on | What it covers |
| --- | --- | --- | --- |
| Logic | `src/test/.../LogicTest.kt` | JVM | Usage aggregation, session maths, formatting |
| Entitlements | `src/test/.../plan/PlanTest.kt` | JVM | Every allowance on both plans, item counting, downgrade, Lockdown budget (fixed instants and zones, no wall clock) |
| Access | `src/test/.../AccessTest.kt` | JVM | Granted, missing, revoked, and every `ProtectionStatus` |
| UI smoke | `src/sharedTest/.../NavigationTest.kt` | JVM (Robolectric) and device | Onboarding completes, tabs switch, Focus starts and ends early |
| Screenshots | `src/test/.../ScreenshotTest.kt` | JVM (Robolectric, Roborazzi) | Six screens × light, dark, 200% font and reduced motion |

- `src/sharedTest/` is a source directory of both `test` and `androidTest`. The same UI tests run
  under Robolectric in every CI run and on a device with `connectedDebugAndroidTest`.
- UI tests wait for DataStore results with `waitUntil`, never with sleeps.
- **Screenshots.** Baselines are in `app/src/test/screenshots/`. `verifyRoborazziDebug` compares
  against them, and `recordRoborazziDebug` re-records them. They are recorded on macOS. Linux
  rasterises text differently, so CI only renders every screen (catching crashes) and doesn't compare
  pixels. Today's greeting depends on the hour, so the test pins local time to 10:30. Under
  Robolectric usage access reads as granted with no usage, so Insights shows its "quiet day" state.
  To verify on CI later, record the baselines on Linux (for example in the CI job) and check those in.
- The instrumented tests overwrite aurora's onboarding and session state. Run them on an emulator.

## Continuous integration

`.github/workflows/android.yml` runs on every push and pull request, on `ubuntu-latest` with
Temurin 21 and the runner's Android SDK. It uses only `actions/checkout`, `actions/setup-java` (with
its Gradle cache) and `actions/upload-artifact`.

1. `testDebugUnitTest`, 2. `lintDebug`, 3. `assembleDebug assembleRelease`, 4. the release spike check.

On failure it uploads `app/build/reports/` and the test results. Lint treats warnings as errors;
the Phase 1 warnings are in `app/lint-baseline.xml`. There is no signing, release or deployment
automation, and no emulator job yet (the roadmap's nightly API 26–36 matrix needs a third-party
emulator action or a self-hosted runner).

## Conventions

- Screens read `Aurora.colors`, `AuroraType`, `Space`, `Radius` and `Motion`. They never use raw
  `Color(...)`, `sp` or `dp` literals for tokens that already exist.
- Animate with `auroraTween()` or check `Aurora.reducedMotion`. Never add a loop without a
  reduced-motion fallback.
- Copy is inline for now. Move it to `strings.xml` before localisation starts.
