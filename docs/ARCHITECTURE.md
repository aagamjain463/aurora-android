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
| DI | Manual (`AuroraApplication`) | Three singletons. A framework would cost more than it saves. |
| Min / target / compile SDK | 26 / 36 / 37 | API 26 adds adaptive icons and variable fonts and covers almost every active device. Current AndroidX releases require compile SDK 37. Target stays at 36 until aurora is tested on Android 17. |

There are no analytics, crash-reporting SDKs, network clients or accounts. The app does not
declare the `INTERNET` permission.

## Layout

```
app/src/main/java/app/aurora/
├── AuroraApplication.kt   Dependency holder, auroraViewModel {}, rememberUsageAccess()
├── MainActivity.kt        Edge-to-edge, theme selection, start destination
├── data/
│   ├── Preferences.kt     UserPreferences, FocusSession, PreferencesRepository, clockTicks()
│   ├── AppCatalog.kt      Real launchable apps (label + icon) via PackageManager
│   ├── ScreenTime.kt      Usage access check, today's foreground time, foregroundTime()
│   └── Format.kt          Duration and clock formatting
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
  `ON_RESUME`. When the person comes back from Android Settings, the UI is already correct.

## Key decisions

**Focus sessions are timestamps, not a running service.** A session is stored as
`startedAt + durationMs` and the UI derives the rest. It survives process death and reboots and
needs no foreground service or notification permission. The trade-off is that nothing happens
when a session ends while aurora is closed. aurora shows the completion the next time it opens.
Phase 2 adds an exact alarm and notification.

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

## Testing

- `app/src/test/.../LogicTest.kt` covers the logic that can go wrong silently: usage
  aggregation across window edges and screen-off events, session maths, and formatting.
- Run it with `./gradlew testDebugUnitTest`.
- UI is verified on an emulator (see the README). Compose UI tests and screenshot tests arrive
  with Phase 2, once the screens stabilise.

## Conventions

- Screens read `Aurora.colors`, `AuroraType`, `Space`, `Radius` and `Motion`. They never use raw
  `Color(...)`, `sp` or `dp` literals for tokens that already exist.
- Animate with `auroraTween()` or check `Aurora.reducedMotion`. Never add a loop without a
  reduced-motion fallback.
- Copy is inline for now. Move it to `strings.xml` before localisation starts.
