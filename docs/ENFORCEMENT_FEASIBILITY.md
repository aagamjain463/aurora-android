# Enforcement feasibility (Phase 2A)

This document records how aurora can enforce app protection on Android, what was actually tested,
and what is still unproven. It is the written result of the gate G1 spike in
[`ROADMAP.md`](ROADMAP.md) section 5.3, plus early evidence for G3 and G4.

- **Date:** 2026-10-28.
- **Tested on:** one emulator only, `aurora_pixel` (Pixel 7 profile, Android 16, API 36, arm64,
  2 GB RAM, Google APIs image). **No physical device was tested.**
- **Build:** debug (debuggable, not minified).
- **Status:** G1 is **not passed**. The mechanism works on the emulator, but the pass criteria in
  section 5.3 need three physical devices, API levels other than 36, and a latency measurement on a
  release-like build. See [Phase 3 prerequisites](#9-phase-3-prerequisites). The device harness for
  those runs is in [section 10](#10-g1-device-harness-phase-2d).

---

## 1. Summary

| Question | Answer on the emulator (API 36) | Confidence |
| --- | --- | --- |
| Can aurora detect when a selected app comes to the front? | Yes. An `AccessibilityService` that listens only to `TYPE_WINDOW_STATE_CHANGED` received an event for every tested open: launcher icon, Recents, `am start` (deep-link style) and an app starting its own activity. | Confirmed on emulator |
| Can it show an aurora Shield without the person returning to aurora? | Yes. The service starts a full-screen aurora activity from the background. The system log shows the start was allowed as `BAL_ALLOW_TOKEN`. | Confirmed on emulator |
| Does it work with aurora's main activity closed? | Yes. It works after aurora's task is removed, after a process kill, after an app update and after a reboot, without opening aurora. | Confirmed on emulator |
| Can the person safely get back to the launcher or another app? | Yes. "Close app" and Back go to the home screen. Home, Recents, the notification shade, incoming calls and the emergency dialer all keep working. | Confirmed on emulator |
| What happens when the permission is revoked? | The system unbinds and destroys the service at once. aurora can detect this and shows `NeedsPermission`. Nothing is shielded. | Confirmed on emulator |
| What happens when the service is killed or the device restarts? | **Process kill:** the system restarts the service in 1–3 s. **Crash:** restarted after about 11 s. **Force-stop:** Android **turns the service off**, and only the person can turn it back on. **Reboot:** the service comes back by itself, but it took about 29 s after boot on this emulator. During that time nothing was protected. | Confirmed on emulator |
| Can it support the Intent reflection flow? | Yes. The Shield activity showed a reason step with choices, a text field with the keyboard, and a 5-minute opening that returned the person to the app. | Confirmed on emulator |
| Is the Shield fast enough (P90 under 300 ms)? | **Unknown.** The emulator is too slow to answer this. Even Clock's own hot start takes about 1 s here. | Not measured |

**Recommendation:** use an `AccessibilityService` that watches only window-state changes, and a
full-screen Shield **activity in its own task**. Do not use an accessibility overlay as the primary
Shield. Do not use `UsageStatsManager` for detection. The rest of this document gives the evidence.

---

## 2. The prototype

The spike is isolated in the **debug source set**. It is not compiled into release builds: the
release manifest has no spike entries and no accessibility service. Phase 3 rebuilds the mechanism in
`main/` and deletes the spike.

| File | Purpose |
| --- | --- |
| `app/src/debug/AndroidManifest.xml` | Spike service, Shield activity, a separate "aurora spike" launcher entry, and `QUERY_ADVANCED_PROTECTION_MODE`. |
| `app/src/debug/res/xml/spike_shield_service.xml` | Service config: `typeWindowStateChanged` only, `canRetrieveWindowContent=false`, `isAccessibilityTool=false`. |
| `app/src/debug/res/values/spike.xml` | Service description and the dark Shield window theme. |
| `app/src/debug/java/app/aurora/spike/Spike.kt` | Pure logic: `spikeDecide()`, the system exemption list, `spikeStatus()`, and parsing of the enabled-services setting. |
| `app/src/debug/java/app/aurora/spike/SpikeShieldService.kt` | Detection adapter. It also shows the overlay variant. Its `UsageStatsManager` latency probe was removed for the G1 harness (section 10). |
| `app/src/debug/java/app/aurora/spike/SpikeShieldActivity.kt` | The Shield activity and the shared Shield UI, with the pause and reflect steps. |
| `app/src/debug/java/app/aurora/spike/SpikeControlActivity.kt` | Status panel, the prominent disclosure before Accessibility settings, and the activity/overlay switch. |
| `app/src/testDebug/java/app/aurora/spike/SpikeTest.kt` | 5 unit tests for the pure logic. |

The Shield UI reuses the design system: `AuroraTheme` (always dark), `HorizonGlow`, `AppIcon`,
`AuroraButton` (Primary "Close app" and Quiet "Open with intent"), `ChoicePills` and
`AuroraTextField`. Every Shield screen is labelled "debug spike · not the real Shield".

The protected apps come from the real Protect tab (`UserPreferences.protectedApps`). The spike
reads them through the existing `PreferencesRepository`.

**How to run it**

1. `./gradlew installDebug`
2. Choose apps on the Protect tab.
3. Open "aurora spike", read the disclosure, then turn on "aurora spike shield" in Accessibility
   settings. You can also turn it on with
   `adb shell settings put secure enabled_accessibility_services app.aurora/app.aurora.spike.SpikeShieldService`.
4. Open a protected app. Filter the timings with `adb logcat -s AuroraSpike`.

---

## 3. Mechanisms evaluated

### 3.1 AccessibilityService (foreground detection): chosen

- The service gets a `TYPE_WINDOW_STATE_CHANGED` event with the package name whenever a window
  comes to the front. No polling and no foreground service are needed. The system binds the service
  and keeps it running.
- The config asks for the least possible access: one event type, no window content, no key
  filtering, no gestures and `isAccessibilityTool=false`.
- **Seen:** an event arrived for every open path that was tried. Apps often send 2–3 events for one
  open (a splash `FrameLayout`, then the main activity).
- **Seen:** the system's own consent dialog still says *"Allow … to have full control of your
  device? … It can read all content on the screen"*, even though aurora asks for no window content.
  Android shows that wording for every non-tool service. aurora's own disclosure must come first and
  explain what aurora really reads.

### 3.2 UsageStatsManager: rejected for detection

- There is no callback. aurora would have to poll `queryEvents()`, and polling continuously needs a
  running process.
- **Measured:** the spike polled every 25 ms, starting from the accessibility event for the same open.
  Over 27 Clock opens the `ACTIVITY_RESUMED` record became visible after: median 57 ms, P90 276 ms,
  max 4,224 ms. In 2 more opens it was not visible within 5 s. Real polling adds its own interval on
  top of this.
- Keeping a poll loop alive in the background needs a foreground service. On Android 14+ that needs a
  declared type. The only type that could fit is `specialUse`, which Play reviews by hand. It also
  needs a permanent notification.
- Even after it detects the app, a usage-stats approach can't show an activity from the background
  without `SYSTEM_ALERT_WINDOW`, which is another special permission. On Android 15+, starting a
  foreground service from the background with that permission also needs a visible overlay window.
- **Keep** `UsageStatsManager` for what it already does: Insights and daily-limit accounting.

### 3.3 Shield as a full-screen activity: chosen

- The activity runs in its own task (`taskAffinity="app.aurora.shield"`, `singleTask`,
  `excludeFromRecents`). The protected app's task stays underneath and is **paused**. It doesn't
  take input.
- **Seen:** the background start was allowed (`BAL_ALLOW_TOKEN` in `ActivityTaskManager`) when
  aurora had no visible window and its main activity was closed.
- **Seen:** the protected app's Recents thumbnail was black while the Shield covered it.
- **Seen (important):** Chrome started its own first-run activity *over* the Shield about 1 s later.
  The system allowed it as `BAL_ALLOW_GRACE_PERIOD`, the background-start grace period an app keeps
  for a short time after it was visible. The fix is to show the Shield again on **every**
  window event from a protected app. A "Shield already shown" flag must never suppress it. With that
  fix the Shield held over Chrome for the whole 12 s observation.
- "Open for 5 minutes" calls `finish()` on the Shield. The protected app's task comes back exactly
  where it was.

### 3.4 Shield as a TYPE_ACCESSIBILITY_OVERLAY window: rejected as primary

It works, but it is weaker than the activity for aurora's purpose:

| Check | Activity | Overlay |
| --- | --- | --- |
| Protected app under the Shield | Paused | **Still resumed**, so media and timers keep running |
| Recents thumbnail of the protected app | Black | **Shows the app's content** |
| Recents button | Shield stays with the task | **The overlay is removed**, and the app is one tap away |
| Back | Handled by `OnBackPressedDispatcher` | Needs a custom root view. With the keyboard open, Back closed the app instead of the keyboard |
| Compose | Normal | Needs a hand-made `LifecycleOwner` and `SavedStateRegistryOwner` on the root view (the first try crashed) |
| Keyboard | Works | Connects (`mInputShown=true`) |
| An app launching over it | Can happen (see 3.3), fixed by showing it again | Can't happen, because the overlay stays above all app windows |

Keep the overlay only as a fallback for a device where background activity starts fail. G1 hasn't
found such a device yet.

### 3.5 Background execution, process death and reboot

| Event | Result on the emulator |
| --- | --- |
| aurora's task removed, process alive | Service keeps working |
| `am kill app.aurora` | No effect. The system-bound service keeps the process important |
| `kill -9` on the process (simulated low-memory kill) | "Scheduling restart of crashed service … in 1000ms". Connected again 1–3 s later |
| Crash inside the service | Restarted after about 11 s. The service stayed enabled |
| App update (`adb install -r`) | Service rebound by itself |
| Revoked in Settings, or the setting cleared | `onUnbind` and `onDestroy` called at once. Status shows `NeedsPermission` |
| **Force-stop** | **Android removed aurora from `enabled_accessibility_services`.** Opening aurora did not restore it. Protection stays off until the person turns it back on |
| Reboot | Before the device is unlocked: "Ignoring non-encryption-aware service". After boot, the service connected without aurora being opened, about 29 s after `sys.boot_completed`. The emulator was also overloaded at that point (a SystemUI ANR). The gap on a real device is unmeasured |

aurora needs no foreground service, wake lock, boot receiver or battery-optimisation exemption for
detection. The system binds the service and keeps it alive.

### 3.6 Emergency and essential functions

- An incoming call showed as a heads-up over the Shield. Answering it brought the in-call screen to
  the front. Hanging up returned to the Shield, not to the protected app.
- The emergency dialer opened over the Shield.
- The spike never shields `SystemExempt` (system UI, Settings, package installers, permission
  controller, phone, telecom, emergency, safety hub) or the runtime list (all launchers, the current
  keyboard, the default dialer and aurora itself). A unit test checks that Settings stays reachable
  even when the person protects it. This keeps "turn off the service" and "uninstall aurora" always
  possible.

### 3.7 Android version notes

| Version | Relevant change | Evidence |
| --- | --- | --- |
| 10 (API 29) | Background activity start (BAL) restrictions start. | Official BAL page. Historical behaviour exempted services bound by the system, including accessibility. |
| 12 (API 31) | Foreground services can't start from the background. Play accessibility declaration needed for API 31+ targets. | Official FGS and Play pages |
| 13 (API 33) | "Restricted settings" can block turning on accessibility for some sideloaded apps. | Google help page. Not tested (see G4) |
| 14 (API 34) | `PendingIntent` senders must opt in to grant BAL. Foreground service types required. | Official BAL page. aurora calls `startActivity` directly, so it isn't affected |
| 15 (API 35) | `PendingIntent` creators must opt in to grant BAL. | Official BAL page. Not affected |
| 16 (API 36) | Advanced Protection Mode (AAPM) and `AdvancedProtectionManager` added. | Tested: the API answers (`false`) with `QUERY_ADVANCED_PROTECTION_MODE` |
| 17 (API 37) | With AAPM on, non-tool apps lose access to the Accessibility API. Reports say access already granted is revoked. Opt-in task-hijacking protection (`allowCrossUidActivitySwitchFromBelow`). | Press coverage of Beta 2 (March 2026) and an October 2026 announcement. **No official developer doc found. Not tested** |

**The current official BAL page no longer lists `AccessibilityService` by name.** It lists
"The app is bound by a service that has been granted permission to start background activities".
The API 36 test shows the start is still allowed (`BAL_ALLOW_TOKEN`), but this must be re-checked on
every API level G1 lists.

---

## 4. What failed or surprised us

1. **Force-stop turns protection off for good.** The person, or an OEM "clean up" tool that
   force-stops apps, removes aurora from the enabled accessibility services. aurora can't turn it
   back on.
2. **Apps can launch over the Shield** during their background-start grace period. Handled by showing
   the Shield again on every event. A debounce that suppresses this re-shows the app.
3. **UiAutomator turns accessibility services off.** Every `uiautomator dump` unbound and destroyed
   the spike service, because `UiAutomation` suppresses other services unless it is created with
   `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`. Phase 3's instrumented tests must use that flag, or
   they will test nothing.
4. **The reboot gap.** Protected apps opened in the first ~30 s after boot were not shielded on the
   emulator.
5. **Two spike bugs, both fixed:** the reflect step carried over to the next Shield, and the overlay's
   lifecycle owners were set on the wrong view, which crashed the service.
6. **adb text input is unreliable** with Gboard on the emulator (characters reordered or dropped) in
   both mechanisms. The keyboard connects. Typed-text fidelity was not verified.

---

## 5. Google Play policy concerns

Sources read on 2026-10-28: Play Console Help "Use of the AccessibilityService API" (answer 10964491),
the Android background activity start page, the foreground service pages and the Advanced Protection
page.

- **Disclosure and consent.** A non-tool app must show a prominent disclosure **inside the app**,
  during normal use, separate from other disclosures, and it needs an affirmative action from the
  person. A privacy policy or the store listing alone is not enough. The spike's control screen
  shows a draft of that disclosure.
- **Declaration.** Apps targeting API 31+ with an accessibility service complete a Play Console
  declaration. It includes why the API is used, whether data is collected or shared, and a **video
  of the in-app disclosure**. It must be submitted again if the use changes. Website filtering in
  1.1 would be such a change.
- **`isAccessibilityTool`.** aurora is not an accessibility tool and must keep it `false`.
- **Autonomous actions.** The policy forbids using the API to "autonomously initiate, plan, and
  execute actions". Starting the Shield happens because the person chose to protect the app. "Close
  app" and the global Home action happen only when the person taps. **Whether Play reviewers read
  the automatic Shield as allowed is unconfirmed.** Only a Play review of a closed-testing build can
  answer it (G2).
- **Preventing disable or uninstall.** A clause that forbids using the API to stop people from
  disabling or uninstalling apps, except for parental-control or enterprise apps, appears only in
  secondary sources. It was not on the page fetched today. aurora complies either way: Settings and
  package installers are never shielded, and Lockdown must never block them.
- **Not needed:** `SYSTEM_ALERT_WINDOW`, `QUERY_ALL_PACKAGES`, any foreground service type,
  device admin, exact alarms (for detection) or `INTERNET`.
- **`QUERY_ADVANCED_PROTECTION_MODE`** was used only in the debug spike. Before adding it to
  release, confirm its protection level and whether Play needs it declared.

---

## 6. Unverified assumptions

1. Background activity starts from an accessibility service work on API 26–35 and 37. Only API 36 was
   tested.
2. The Shield appears with P90 under 300 ms on a mid-range physical device in a release build.
3. Samsung One UI, Xiaomi HyperOS and OnePlus OxygenOS don't kill or disable the service in normal
   use. They also don't hide the Shield, and their launchers send window events promptly.
4. The reboot gap on real devices.
5. Android 17 with AAPM on revokes aurora's access, and `AdvancedProtectionManager` reports it.
6. Sideloaded builds installed from a file need "Allow restricted settings" (G4). The adb install
   used here was recorded as package source `OTHER` and was not restricted.
7. Play approves this use (G2).
8. Split-screen, picture-in-picture, freeform windows, widgets and notification deep links were not
   tested.
9. TalkBack with the Shield, and 200% font scale on the Shield, were not tested.

---

## 7. Recommended architecture

This uses the shape that `ROADMAP.md` section 3 already plans: one `protect/` package in the
existing module, with no new dependencies.

```
app/src/main/java/app/aurora/protect/
├── Policy.kt            Target, PolicyState, Decision, decide()      pure, unit tested
├── ProtectionStatus.kt  ProtectionStatus + status()                 pure, unit tested
├── ShieldService.kt     AccessibilityService: the detection adapter
├── ShieldActivity.kt    The Shield: pause, Friction wait, Intent reflection
└── Permissions.kt       One place that checks every permission and opens its Settings screen
```

**`decide()` (pure).** `decide(target, now, state): Decision`, as in `ROADMAP.md` 3.1. Two lessons from
the spike:

- `PolicyState` carries the **exempt set** (system list plus runtime launchers, keyboard and dialer).
  "Never shield Settings or emergency flows" can then be tested in `decide`, not left to the adapter.
- Grants (Intent openings, Cooldown windows) are part of `PolicyState` and persist in DataStore. The
  spike's in-memory grants were lost with the process.

**Detection adapter (`ShieldService`).** It turns events into `Target`, calls `decide`, and acts:

- It holds a `PolicyState` snapshot that a coroutine keeps current by collecting the repositories. It
  never reads DataStore per event (as the spike does).
- It tracks the **last real foreground package**, ignoring aurora, system UI and the keyboard.
  That separates a *fresh* open (start the Shield at its first step) from a *repeat* event for the
  same open (show the Shield again but keep the person's place).
- It never suppresses showing the Shield again (see section 4, item 2).
- It publishes `connected` from `onServiceConnected`, `onUnbind` and `onDestroy`.

**Intervention (`ShieldActivity`).** Own task, `singleTask`, `excludeFromRecents`, the dark window
theme, Back goes home. It carries the package, the event time and the "fresh" flag. A way through
writes a grant, then calls `finish()`. "Close app" starts the home intent. Friction (Phase 4) and the
Intent flow are steps inside the same activity.

**Permissions (`Permissions.kt`).** Plain functions, not a class hierarchy:
`accessibilityEnabled()` (reads `ENABLED_ACCESSIBILITY_SERVICES`), `advancedProtectionOn()` (API 36+),
the existing usage-access check, and later notifications and exact alarms. Each has an
`open…Settings()` and a `remember…Access()` composable that re-checks on resume. This is Phase 2
task 4.

**`ProtectionStatus`.**

```kotlin
enum class ProtectionStatus { Off, NeedsPermission, NotRunning, Blocked, Active }
```

| Value | When | UI |
| --- | --- | --- |
| `Off` | Nothing is protected | Neutral |
| `NeedsPermission` | Service not enabled. This includes after a force-stop. | Ask, with the disclosure |
| `NotRunning` | Enabled but not connected: boot gap, crash restart, OEM kill | "Starting protection…". After a timeout, explain and offer to re-enable |
| `Blocked` | Android 17+ AAPM is on | Explain that Android's Advanced Protection doesn't allow it, and how to change that |
| `Active` | Enabled **and** connected **and** items exist | The only state that may say "protected" |

`status()` is pure (the spike's `spikeStatus()` plus `Blocked`). The flow combines it with the
preferences flow and the service's `connected` flow, and re-checks on resume.

**Persistence.** Protected items with modes, the session, grants and Cooldowns go in DataStore
(Phases 3–4). History goes in Room (Phase 5), as the roadmap already plans. The service needs nothing
persisted beyond these.

**Degraded protection.** Every surface reads `ProtectionStatus`. During a Focus session or
Lockdown, any status other than `Active` must say plainly that apps are not being paused right now.
It must never show a running session as "protected".

---

## 8. Risks and mitigations

| Risk | Likelihood | Mitigation |
| --- | --- | --- |
| Play rejects the accessibility use (G2) | Medium | Minimal config, in-app disclosure, declaration video, and a closed-testing review before Phase 3 ships. Fallback: section 5.3 fallbacks, which change the product promise. |
| A force-stop or OEM cleaner turns protection off | High on some OEMs | Detect `NeedsPermission` on resume. Explain without blame. Document per OEM. Never claim it can't happen. |
| Android 17 AAPM blocks the service | Low (opt-in mode), growing | `Blocked` status, honest copy. |
| Background start rules tighten again | Medium over time | Overlay fallback kept as a design option. Re-check on each Android release (Phase 12). |
| The protected app launches over the Shield | Seen | Show the Shield again on every event. Instrumented test with an app that starts a second activity. |
| Latency lets the app flash before the Shield | Unknown | Measure on devices. Keep the Shield light: first frame before loading the icon, no I/O on the main thread. |
| The reboot gap leaves apps open for a while | Seen (~29 s on emulator) | Measure on devices. Document it. Show `NotRunning` meanwhile. |
| The system consent dialog ("full control") scares people | High | aurora's disclosure comes first and says what is really read, and that Android's wording is generic. |
| Instrumented tests silently turn the service off | Seen | Use `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`. Avoid `uiautomator dump` in test scripts. |
| Debug spike code leaks into release | Low | It lives in `src/debug/`. Delete it when Phase 3 lands. |

---

## 9. Phase 3 prerequisites

G1 passes only when these are done:

1. **Physical devices:** a Pixel, a Samsung and a Xiaomi or OnePlus. Run the spike's scenario list
   (section 3) on each.
2. **API coverage:** API 26, 29, 31, 34, 36 and 37. Only API 36 was installed here. The other system
   images, or devices, are needed.
3. **Latency on a non-debuggable build:** 50 opens, P90 under 300 ms on the mid-range device. The
   spike is debug-only, so this needs either a measurement build type or the Phase 3 code.
4. **Untested entry points:** split-screen, picture-in-picture, a notification deep link and a widget.
5. **G2:** a closed-testing Play review of a minimal build with the disclosure and declaration.
6. **G4:** install a debug APK from a file manager or browser on Android 13+, and record the
   restricted-settings steps.
7. **G3 on Android 17:** turn AAPM on and check revocation and `isAdvancedProtectionEnabled`.
8. **Accessibility of the Shield:** TalkBack, 200% font scale and reduced motion.
9. **Decision:** keep the spike in `src/debug/` until Phase 3, or move it to a `spike/g1` branch as
   `ROADMAP.md` 5.1 rule 1 says. The spike was put in the debug source set because commits needed
   approval.

---

## 10. G1 device harness (Phase 2D)

The harness turns section 9 items 1–4 and 8 into a repeatable run. It lives only on the
`spike/g1-harness` branch, next to the spike. It is never merged into `main`.

**Status (2026-10-28):** the harness builds, and its preflight ran on the emulator. **No physical
device has been tested.** No latency numbers exist yet. G1 is still **not passed**.

### 10.1 What it measures

All on-device times use one clock, `CLOCK_MONOTONIC` (`SystemClock.uptimeMillis()` and
`System.nanoTime()`). Restart and reboot times use `CLOCK_BOOTTIME` (`elapsedRealtime()` and
`/proc/uptime`).

| Interval | From | To |
| --- | --- | --- |
| **Detection** | `AccessibilityEvent.eventTime`: the protected app's window came to the front | The service's `onAccessibilityEvent` starts |
| **Presentation** | The service's `onAccessibilityEvent` starts | The end of the Shield's first frame that started after the event reached the service (`FrameMetrics` `INTENDED_VSYNC_TIMESTAMP` + `TOTAL_DURATION`). The frame is then in the compositor |
| **Exposure** (the G1 number) | `eventTime` | The same end of the Shield's first frame. Exposure = detection + presentation |

Not included:

- The time before the app's window event. That is the app's own launch time, so it's the same with
  or without aurora.
- The last display refresh after the frame reaches the compositor. That adds up to one refresh
  interval (about 8–17 ms). `g1.sh device` records the refresh rate.

**Trials.** Each *fresh* open of a protected app is one trial. "Fresh" means a different app was in
front before it. Every trial ends with exactly one row: `ok`, or `timeout` if no Shield frame came
within 2 s. The host script also counts every open it causes. An open with no trial row is a `miss`.
All opens are in the denominator, so misses and timeouts count against the 300 ms target. They never
drop out of it. After each open the script also records whether the Shield has window focus
(`shield_in_front`). That catches an app that launched over the Shield.

**Pass rule used by `report`:** `within-300ms` is the number of `ok` opens with exposure ≤ 300 ms,
divided by **all** opens. G1 needs P90 under 300 ms over at least 50 opens on a mid-range phone. That
is the same as `within-300ms` ≥ 90%.

**Build.** Latency is measured on the `g1` build type. It uses release settings (R8, not debuggable)
plus the debug-only spike, and it's signed with the debug key. A debuggable build runs slower and
would understate the result. The release build still contains no spike code.

**Privacy.** The device writes only package names and clock values to
`Android/data/app.aurora/files/g1.csv`. It never writes window content. The spike's config still has
`canRetrieveWindowContent=false`. There is no network access, and the harness adds no telemetry.
The harness no longer runs the UsageStatsManager probe (section 3.2), because it polled every 25 ms
during the frames being timed.

### 10.2 Files

| File | Purpose |
| --- | --- |
| `app/src/debug/java/app/aurora/spike/G1.kt` | Trial and service-event rows, 2 s timeout, writes off the main thread |
| `SpikeShieldService.kt` | Stamps the time an event arrives, opens a trial for each fresh open, logs connect, unbind and destroy |
| `SpikeShieldActivity.kt` | Closes the trial with the first qualifying `FrameMetrics` frame |
| `app/build.gradle.kts` | `g1` build type: release settings plus the debug source set |
| `tools/g1/g1.sh` | Host script. Needs only `adb`. Results go to `g1-results/<serial>/` (git-ignored) |

### 10.3 Running it on a phone

Use a test phone, not a personal one. The harness launches apps, force-stops them and can reboot
the phone.

1. On the phone, turn on Developer options and USB debugging. Connect it, then accept the RSA prompt.
2. Build and install: `git switch spike/g1-harness && ./gradlew assembleG1 && adb install -r app/build/outputs/apk/g1/app-g1.apk`.
   If the debug build is already installed with another key, uninstall it first. On Android 13+ an
   adb install isn't "restricted". To check G4, also install the APK once from a file manager.
3. Open **aurora** and finish onboarding. On the Protect tab, choose one app that has a launcher icon
   and that you're happy to open many times (for example Clock or Calculator).
4. Open **aurora spike** and read the disclosure. Then turn on **aurora spike shield** yourself in
   Settings › Accessibility. Read Android's own dialog before you accept. The harness never turns it on.
5. Run, from the repository root:

   ```sh
   tools/g1/g1.sh device
   tools/g1/g1.sh launches com.google.android.deskclock 50 warm
   tools/g1/g1.sh launches com.google.android.deskclock 20 cold
   tools/g1/g1.sh rapid com.google.android.deskclock com.android.settings 20
   tools/g1/g1.sh manual launcher-tap 10
   tools/g1/g1.sh manual recents 5
   tools/g1/g1.sh manual notification 5     # an app notification that opens the protected app
   tools/g1/g1.sh manual lock-unlock 5      # Shield up, lock, unlock: it must still be in front
   tools/g1/g1.sh manual split-screen 3
   tools/g1/g1.sh manual pip 3              # only for apps that support picture-in-picture
   tools/g1/g1.sh manual talkback 3         # turn TalkBack on first, then check that "Close app" can be reached
   tools/g1/g1.sh kill
   tools/g1/g1.sh revoke
   G1_ALLOW_REBOOT=1 tools/g1/g1.sh reboot  # only if rebooting this phone is fine
   tools/g1/g1.sh forcestop                 # last: Android may turn the service off
   tools/g1/g1.sh note "Samsung: Shield hidden after ..."
   tools/g1/g1.sh report
   ```

   Use the protected app's real package name. Scripted opens use `am start` from the shell. They skip
   the launcher's animation, so `launcher-tap` covers real taps.
6. Keep `g1-results/<serial>/` (three CSV files and `device.txt`). Paste the `report` output into
   section 10.5. A multi-phone report needs `device.txt` from each phone.

**Each phone needs:** its manufacturer, model, Android version and API level (from `device.txt`).
Also record median, P90, P95, max, sample size, timeouts, misses, `shield-not-in-front`, kill restart
time, revoke result, reboot delay after unlock, force-stop result and notes.

### 10.4 Known limits of the method

- The protected app sends 2–3 window events per open. Only the first event of a fresh open is timed.
  Later events still show the Shield again, and `shield_in_front` checks the end result.
- `FrameMetrics` can drop reports when the system is under load. A dropped report becomes a `timeout`
  even if the Shield showed. `shield_in_front=1` on a timeout row points to this.
- `manual` and `revoke` timings depend on when you press Enter. Use them for pass or fail, not latency.
- `kill` uses `run-as … kill -9` on a debuggable build. On the `g1` build it uses `am crash` (API 30+),
  which is a crash restart (about 11 s on the emulator), not a low-memory kill.
- `reboot` reports the delay after you press Enter on unlock. It's accurate to about a second.
- In split-screen, only the focused window counts as "in front".

### 10.5 Results

| Phone | Android (API) | Opens | Within 300 ms | Median | P90 | P95 | Max | Timeouts | Misses | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| *none tested yet* | | | | | | | | | | |

G1 stays **not passed** until this table has a Pixel, a Samsung and a Xiaomi or OnePlus phone, and
section 9 items 2 and 5–8 are done.
