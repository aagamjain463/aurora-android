# aurora Android architecture

The architecture is intentionally small. Phase 1 is one Gradle module, a handful of
repositories, and one ViewModel per screen. Add structure when a real need appears, not before.

The current four-tab configuration and Habits model is documented in
[CONFIGURATIONS.md](CONFIGURATIONS.md). Historical phase notes below describe the
foundation; use the configuration model for current activation and progress behavior.

## Stack

| Concern | Choice | Why |
| --- | --- | --- |
| Language | Kotlin 2.4 | AGP 9 compiles Kotlin itself. The root build pins the Kotlin version. |
| UI | Jetpack Compose + Material 3 | M3 supplies accessible primitives (switch, dialog, text selection). aurora supplies the look. |
| Presentation | MVVM: `ViewModel` + `StateFlow` | Survives configuration changes. Screens are functions of state. |
| Navigation | Navigation Compose 2.x, string routes | Four root tabs and pushed builders/detail screens share the existing nav graph. |
| Persistence | DataStore Preferences + kotlinx.serialization JSON values | Small data, async and transactional, held in memory for the Shield (`data/Store.kt`). No database needed yet. |
| DI | Manual (`AuroraApplication`) | Four singletons. A framework would cost more than it saves. |
| Min / target / compile SDK | 26 / 36 / 37 | API 26 adds adaptive icons and variable fonts and covers almost every active device. Current AndroidX releases require compile SDK 37. Target stays at 36 until aurora is tested on Android 17. |

There are no analytics or third-party advertising SDKs. `INTERNET` exists for two purposes:
1. `account/AccountRepository.kt` communicating with Firebase Authentication and the Cloudflare Worker backend for verified identity, daily strict quota checks, multi-device sync, and account deletion.
2. `plan/Billing.kt` sending purchase tokens to the verification backend (`docs/BILLING.md`).
No personal usage data, package names, or websites are ever sent (`docs/ACCOUNT_SYSTEM.md`).

## Layout

```
app/src/main/java/app/aurora/
├── AuroraApplication.kt   Dependency holder, auroraViewModel {}, rememberAccess()
├── MainActivity.kt        Edge-to-edge, theme selection, start destination
├── account/               Identity, FirebaseIdentity, AccountRepository, AccountApi, ProfileVault, AccountActions
├── data/
│   ├── Preferences.kt     UserPreferences, FocusSession, PreferencesRepository, clockTicks()
│   ├── AppCatalog.kt      Real launchable apps (label + icon) via PackageManager
│   ├── ScreenTime.kt      Usage access check, today's foreground time, foregroundTime()
│   ├── Access.kt          Special access, AccessState, ProtectionStatus, AccessRepository
│   └── Format.kt          Duration and clock formatting
├── plan/
│   ├── Plan.kt            Plan, Allowance, Feature, FreeLimits, ProtectedItem, canProtect(), keepActive()
│   ├── Billing.kt         Google Play Billing, purchase verification, seat management
│   └── Entitlement.kt     Signed snapshot parsing and cryptographic verification
├── protect/               Rules, ShieldService, ContentService, Configurations, TrustedClock
└── ui/
    ├── AuroraNavHost.kt   Routes, tabs, transitions, floating tab bar
    ├── theme/             Palette, semantic colours, type scale, Space/Radius/Motion tokens
    ├── components/        The design system (see brand/Aurora_Brand_Guidelines.md)
    ├── account/           AccountGate, Sign-in, Email link dialogs, Profile decision
    └── onboarding/  home/  protect/  focus/  insights/  settings/
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

**One protected list, enforced by Shield.** Apps come from `PackageManager` through a
`<queries>` launcher intent, not `QUERY_ALL_PACKAGES` (a Play-restricted permission). Choices
persist in order in DataStore (`protect/ProtectedItems.kt`), and Protect, Today, Insights and the
Shield service all read the same `ProtectionRepository.list`. Details are in `SHIELD.md`.

**Screen time is computed, never stored.** `ScreenTimeRepository.today()` replays
`UsageEvents` since local midnight through `foregroundTime()`, a pure, unit-tested function. The
default home launcher is excluded. Nothing is persisted or sent anywhere.

**Tabs are Home, Shield, Intent and Track. Settings and builders are pushed.** Home is always the root
of the back stack, so the tab bar can `popUpTo(Home)` with save/restore state. The tab bar
floats over content and tab screens pad for it. Showing or hiding the bar never resizes
the content.

**No cloud backup.** `allowBackup="false"` and `data_extraction_rules.xml` exclude everything from
cloud backup, because behavioural data shouldn't leave the phone silently. On Android 12 and later, a
phone-to-phone transfer that the person starts can still carry it. Revisit this with an explicit,
opt-in export.

## Complete product

The rule engine, Focus, Lockdown, schedules, limits, Intent, websites, progress and billing added on
`feat/aurora-complete` are described in **`PRODUCT.md`**, including the rule precedence.

## Shield (Phase 3)

`protect/` holds the production Shield: `decide()` (pure), the protected-item store, an `Interceptor`
between the accessibility service and a replaceable `ShieldPresenter`, and the Shield activity. Its
architecture, decision flow, storage, lifecycle, presentation, instrumentation, tests and limitations
are in **`SHIELD.md`**. **The UI claims "protected" only when `ProtectionStatus` is `Active`.**

Strict mode and Lockdown (Phase 6) can't defeat uninstall, safe mode or `adb`, and the UI must not
imply that they can. Device admin and device owner aren't used.

Website filtering needs separate research: per-browser accessibility URL reading, or a local
VPN DNS filter. Both raise policy and privacy questions to settle before implementation.

## Special access and protection status (Phase 2)

`data/Access.kt` is the one place that reads aurora's special access.

| Access | Read with | Opens | Used by |
| --- | --- | --- | --- |
| `Usage` | AppOps `GET_USAGE_STATS` (`ScreenTimeRepository.hasAccess()`) | Usage access settings, deep-linked to aurora | Insights, Today, Settings, onboarding |
| `Accessibility` | `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` for `app.aurora.protect.ShieldService` | Accessibility settings, after the in-app disclosure | Protect, Today, Settings (`rememberProtectionStatus`) |
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
  described as protected. Phase 3 supplies `connected` from the service (`ShieldService.connected`)
  and adds `ConfigError` (the protected list can't be read). `blocked` isn't detected yet.

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
- The plan source is `AuroraApplication.plan`, which comes only from a snapshot the verification server
  signed, trusted for 7 days offline (`plan/Billing.kt`, `plan/Entitlement.kt`, `docs/BILLING.md`). Superseded text follows.
  Previously: always `Free` until Phase 11. Phase 3 wires the
  first limit (5 protected items, `SHIELD.md` section 4). There's still no debug switch to Plus: Plus
  behaviour is covered by unit tests, so no build carries a way to unlock it. There is no purchase UI.

## Debug experiments and production code

Spikes live only in `app/src/debug/` and `app/src/testDebug/`, on `spike/<gate-id>` branches, and are
never merged into `main`. The G1 enforcement spike is on `spike/g1`. Release builds never compile
`src/debug/`, and CI fails if a release APK contains the word "spike".

**G1 has not passed.** On the one API 36 emulator measured (`spike/g1-harness`), 0% of opens were
shielded within 300 ms. Physical devices, OEMs, other API levels, split-screen, picture-in-picture,
TalkBack and Play policy are untested (`ENFORCEMENT_FEASIBILITY.md`). The product owner approved
building Phase 3 anyway, with the presentation mechanism behind `ShieldPresenter` so G1's outcome can
replace it. Phase 3 doesn't make G1 pass.

## Testing

| Suite | Where | Runs on | What it covers |
| --- | --- | --- | --- |
| Logic | `src/test/.../LogicTest.kt` | JVM | Usage aggregation, session maths, formatting |
| Entitlements | `src/test/.../plan/PlanTest.kt` | JVM | Every allowance on both plans, item counting, downgrade, Lockdown budget (fixed instants and zones, no wall clock) |
| Access | `src/test/.../AccessTest.kt` | JVM | Granted, missing, revoked, and every `ProtectionStatus` |
| Shield | `src/test/.../protect/` | JVM, Robolectric | `decide()`, storage and limits, event handling, the Shield activity, the limit message (`SHIELD.md` section 9) |
| UI smoke | `src/sharedTest/.../NavigationTest.kt` | JVM (Robolectric) and device | Onboarding completes, tabs switch, Focus starts and ends early |
| Screenshots | `src/test/.../ScreenshotTest.kt` | JVM (Robolectric, Roborazzi) | Six screens and the Shield × light, dark, 200% font and reduced motion |

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
