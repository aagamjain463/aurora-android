# Shield (Phase 3, production)

This is the production Shield on `feat/phase-3-shield`. The G1 experiment is documented separately in
`ENFORCEMENT_FEASIBILITY.md` and lives only on `spike/g1-harness`. No spike code is on this branch.

> **Status (2026-10-29).** Implemented and unit-tested. End-to-end interception has **not** been
> checked on any device with this code. **G1 is open:** on the one emulator measured, no Shield
> appeared within 300 ms. **Google Play accessibility policy approval is open.** Nothing here
> claims that the Shield meets the latency target or can't be bypassed.

## 1. Architecture

```
AccessibilityEvent (TYPE_WINDOW_STATE_CHANGED)
  └─ ShieldService            Android adapter: package name, event time. No window content.
       └─ Interceptor         repeats, rapid switching, stale Shields, timing. No Android types.
            ├─ decide()       Policy.kt: pure. PolicyState snapshot → Decision.
            └─ ShieldPresenter  interface: present(request) / dismiss()
                 └─ ActivityShieldPresenter → ShieldActivity → ShieldScreen (Compose)
```

| File (`app/src/main/java/app/aurora/`) | Role |
| --- | --- |
| `protect/Policy.kt` | `PolicyState`, `Decision`, `decide()`, `SystemExempt`. Pure Kotlin |
| `protect/ProtectedItems.kt` | `ProtectedList`, storage format, migration, `ProtectionRepository` |
| `protect/Interceptor.kt` | `ShieldRequest`, `ShieldPresenter`, event handling |
| `protect/ShieldTrace.kt` | In-memory counters and timings for diagnostics |
| `protect/ShieldService.kt` | The `AccessibilityService`. `connected` state for status |
| `protect/Exemptions.kt` | This device's launchers, Settings apps, keyboard and dialer |
| `protect/ShieldActivity.kt` | `ActivityShieldPresenter` and the Shield activity |
| `protect/ShieldScreen.kt` | The Shield UI |
| `ui/protect/ShieldStatus.kt` | Status from Android's real state, status card, disclosure, diagnostics |

## 2. Decision flow

`decide(packageName, now, state)` checks these in order, and the first match wins:

| Condition | Decision |
| --- | --- |
| No package on the event | `Invalid` |
| Configuration not loaded or unreadable (`state == null`) | `Unavailable`: shield nothing |
| Package is exempt | `Exempt` |
| Package isn't an active protected app | `Allow` |
| An unexpired grant exists (Phase 4 creates them) | `TemporarilyAllowed(until)` |
| Otherwise | `Shield(package, Protected)` |

**Unknown configuration policy.** If aurora can't read the protected list, it doesn't guess. Allowing
everything silently would be a false "protected". Blocking everything would lock the person out of
their phone. So aurora shields nothing and every status surface shows `ConfigError` ("Shield can't
read your protected apps"). Before the first read finishes, which takes milliseconds after the
service connects, decisions are also `Unavailable`.

**Exempt, always:** aurora, `android`, the system UI, Settings, the Accessibility settings app,
permission controllers, package installers, phone, telecom and emergency packages, Safety Hub, and,
from this device, its launchers, default keyboard and default dialer. An exempt app can't be newly
protected on the Protect tab.

**Baseline behaviour.** A protected app is always shielded. The Shield's only action is **Close app**,
which goes home. Back does the same. There's no "continue anyway" in Phase 3: Flexible, Friction,
Cooldown and Intent arrive in Phase 4 as new `ShieldReason`s and grants. The person stays in control.
They choose what's protected in aurora, and they can turn the service off in Android Settings, which
Shield never covers.

`decide()` never reads the plan. `PolicyTest` checks that Free and Plus give the same decisions.

## 3. Persistence

- **Store:** the existing DataStore Preferences file (`aurora`), key `protected_items`. One string
  holds one item per line, `app:<package>` or `web:<domain>`, oldest first. No database was added.
- **One source of truth:** Protect, Today, Insights, Settings and the service all read
  `ProtectionRepository.list`. `UserPreferences.protectedApps` is gone.
- **Migration:** if `protected_items` doesn't exist yet, the Phase 1/2 set `protected_apps` is read
  and sorted by package name, which is deterministic. The first change writes `protected_items` and
  removes `protected_apps` in the same transaction. Until then, nothing is written.
- **Schema evolution:** an unknown prefix or an invalid entry is skipped and counted
  (`ProtectedList.skipped`, shown in diagnostics), not fatal. Duplicates collapse.
- **Corruption:** a storage `IOException` (including `CorruptionException`) makes
  `ProtectedList(readable = false)`, which leads to `Decision.Unavailable` and `ConfigError`. Nothing
  is deleted or replaced.
- **Writes:** every change is one `DataStore.edit` transaction, and the limit is checked inside it.
  Twenty concurrent adds produce exactly five (`ProtectedItemsTest`). An edit that changes nothing
  writes nothing.
- **Uninstalled apps:** when Protect loads, apps that `PackageManager` reports as not installed are
  removed, which frees their slots (ROADMAP Phase 3 task 8). An app that is installed but hidden from
  aurora's package visibility would look uninstalled too. That's a known edge case.
- **Renamed or updated apps:** items are keyed by package name. The label and icon are read fresh
  every time.

## 4. Entitlement

- The plan comes from `AuroraApplication.plan`, which is always `Plan.Free` until billing arrives in
  Phase 11. There's no debug switch and no fake purchase in any build. Plus behaviour is tested
  through the pure functions.
- **Five items in total, across apps and websites** (`Allowance.ProtectedItems`). `canProtect()` from
  `plan/Plan.kt` is the only check. Websites stored by a future version count toward the same five.
- At the limit, Protect says: "You're protecting 5 items, the most aurora Free allows. Remove one to
  add another." It doesn't mention plus (BUSINESS_MODEL.md section 4).
- **More items than the allowance** (a downgrade, or a Phase 1 list longer than 5): the oldest 5 stay
  active (`keepActive()`). The rest are **paused**. They're kept, labelled "Paused · over the Free
  limit" on Protect and counted on Today, and new additions are refused. Removing an active item
  makes the next paused one active. Nothing is ever deleted. This differs from ROADMAP Phase 3 task
  7, which keeps every migrated app active. The 5-item limit then holds everywhere, and the
  difference is shown to the person.

## 5. Service lifecycle

| Event | Behaviour |
| --- | --- |
| Person turns the service on | Android binds `ShieldService`. `onServiceConnected` computes exemptions, starts collecting the list and preferences, and sets `connected = true` |
| Event | Only `TYPE_WINDOW_STATE_CHANGED` is delivered. One `toString()` and one `decide()`. Storage is never read on the event path |
| Person turns it off | `onUnbind`, then `onDestroy`: `connected = false`. On the emulator this took 12 ms (ENFORCEMENT_FEASIBILITY.md 10.6) |
| Process killed or crashed | The new process reads `connected = false` until Android binds the service again (1.5 s on the emulator, unverified on phones) |
| Force-stop | Android may turn the service off. Only the person can turn it on again. aurora doesn't try to get around this |
| Reboot | The service connects some time after unlock. Apps opened before that aren't shielded |

Status (`ProtectionStatus`) comes from Android's real state: the accessibility switch, re-checked on
every resume, plus the live `connected` flow. It never comes from a stored preference. Only `Active`
is described as protected.

| Status | Shown when |
| --- | --- |
| `Off` | No protected items |
| `NeedsPermission` | The switch is off. Protect offers "Turn on Shield", which shows the disclosure first |
| `NotRunning` | The switch is on but the service isn't connected |
| `ConfigError` | The list can't be read |
| `Blocked` | Not detected yet (Advanced Protection, restricted settings). It reads as `NeedsPermission` |
| `Active` | Switch on, service connected, at least one item |

## 6. Presentation

`ShieldPresenter` has two methods, `present(request): Boolean` and `dismiss()`. The Phase 3
implementation is an activity in its own task (`taskAffinity app.aurora.shield`, `singleTask`,
`excludeFromRecents`, not exported), started from the service with `FLAG_ACTIVITY_NEW_TASK |
FLAG_ACTIVITY_NO_ANIMATION`. Another mechanism replaces only the presenter. The spike rejected an
accessibility overlay as the primary Shield, because the app underneath stays resumed and Recents
removes the overlay (ROADMAP 3.5). Nothing here adds an overlay or a new permission.

- **Repeated events:** one opening sends 2–3 window events. Repeats of the same package within
  400 ms, with nothing else in front in between, are counted and dropped. An event after the Shield's
  own window means the app came back over it, and the Shield is shown again at once.
- **Rapid switching and stale Shields:** when a real app or the launcher comes to the front, the
  presenter is told to `dismiss()`. Each `present()` sets a token. An activity started for an older
  token finishes in `onCreate` instead of appearing over the app the person switched to.
- **No loops:** aurora is exempt, so the Shield's own window never triggers a Shield.
- **Background start:** an app whose accessibility service is bound may start activities from the
  background. This was seen on API 36 (emulator) only. Other API levels and OEMs are unverified. A
  refused start is counted as a failure in diagnostics.
- **Critical path:** the theme's window background is the Shield's colour, so the starting window
  hides the app before Compose draws. There's no animation on the way in. The label and icon load
  off the main thread into a layout that's already in place. Compose itself still starts on the
  first Shield. Whether that's too slow on phones is a G1 question.
- **Rotation:** the activity recreates from its intent and keeps the same Shield. It isn't timed again.
- **Revoked while visible:** the Shield stays until the person closes it. Close app still goes home.

## 7. Performance instrumentation

`ShieldTrace` keeps counts (events, Shields, repeats, failures, `Unavailable` decisions), the last 20
timings, and the last 20 lifecycle changes. It's in memory only: no package names, no disk, no network.
**Settings › Shield diagnostics** shows it.

Times are `SystemClock.uptimeMillis()` (CLOCK_MONOTONIC):

- **decide:** from the event reaching the service to the decision.
- **present:** from the decision to the end of the Shield's first frame that started after the
  decision (`FrameMetrics` `INTENDED_VSYNC_TIMESTAMP + TOTAL_DURATION`).
- **exposure:** from `AccessibilityEvent.eventTime` to that frame. This is the G1 number. It doesn't
  include the final display refresh.

These are diagnostics, not evidence. Physical-device numbers still come from the G1 harness.

## 8. Privacy and policy

- `canRetrieveWindowContent=false`, `isAccessibilityTool=false`, window-state events only.
- aurora reads only the package name in front. It doesn't log, store or send it. There's no internet
  permission.
- The in-app disclosure (`ShieldDisclosure`) comes before aurora sends anyone to Accessibility
  settings. It says what is read, what isn't, that nothing leaves the phone, and how to turn it off.
  The service description in Android Settings says the same.
- **Play policy is an open gate.** The accessibility use is declared as non-accessibility-tool. Approval
  isn't established.

## 9. Testing

| Suite | Covers |
| --- | --- |
| `protect/PolicyTest` | Truth table, exemptions, grants, unknown state, Free = Plus |
| `protect/ProtectedItemsTest` | Encoding, skipped entries, 5-item pool with websites, pause, persistence, 6th item, Plus, downgrade, 20 concurrent adds, legacy migration (also over 5), corrupted file |
| `protect/InterceptorTest` | Repeat bursts, app over Shield, rapid switching, overlays, rule changes, refused starts, trace window |
| `protect/ShieldActivityTest` (Robolectric) | Renders, Close app and Back go home, rotation, stale token never shows, `hide()`, manifest flags |
| `protect/ProtectLimitTest` (Robolectric) | The limit message at 5 items, and nothing is added |
| `ScreenshotTest` | Shield in light, dark, 200% text and reduced motion, plus every screen |

Run `./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease verifyRoborazziDebug`.

**Not automated:** binding the real service. Tests never enable an accessibility service with
`adb settings`. To check by hand on a test phone or emulator:

1. Install the debug build.
2. Protect Clock.
3. On Protect, tap Turn on Shield, read the disclosure, then turn on **aurora Shield** in
   Accessibility settings.
4. Open Clock and check that the Shield appears. Close app should land on the home screen.
5. Check Settings › Shield diagnostics.

## 10. Known limitations

- Interception latency, every entry point (Recents, notifications, deep links, widgets,
  split-screen, PiP), TalkBack and OEM behaviour haven't been measured with this code (G1).
- `Blocked` isn't detected.
- Exemptions are read when the service connects. A launcher or keyboard changed while it runs is
  picked up at the next connect.
- The 400 ms repeat window is a fixed guess, so it needs tuning on phones.
- A Shield showing when aurora's process is recreated finishes instead of reappearing, until the
  next window event.
- Uninstall pruning can't tell an uninstalled app from one hidden by package visibility.
- Connected (device) tests weren't run for this phase. `connectedAndroidTest` uninstalls `app.aurora`,
  which would delete the G1 install and its raw measurements on the test emulator.
