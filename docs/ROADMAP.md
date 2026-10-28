# aurora Android roadmap

This plan takes aurora from the Phase 1 foundation to a 1.0 release on Google Play, and then to
1.1. It covers Shield, Intent, Focus, Insights, schedules, gamification, premium subscriptions,
testing and release for 1.0, and website filtering and supported content blocking for 1.1.

Read it with [`BRAND.md`](BRAND.md) and [`ARCHITECTURE.md`](ARCHITECTURE.md). Those documents
still win: if this plan and the brand disagree, the brand is right and this plan is wrong.

- **Status:** approved plan. Implementation has not started.
- **Settled decisions:** D2 (free and premium), D4 (website filtering approach) and D13 (1.0
  scope), on 2026-10-28. See [section 9](#9-decisions).
- **Rule:** every unverified Android or Play assumption is a **blocking gate** for the phase that
  depends on it. See [section 5](#5-validation-gates).
- **Last reviewed against the code:** 2026-10-28, commit `1270c69`.

---

## 1. Where aurora is today

Phase 1 is a working, honest shell. It is one Gradle module of about 3,500 lines.

| Area | State | Where |
| --- | --- | --- |
| Onboarding | Three steps: why, how, privacy. Usage access is optional. | `ui/onboarding/` |
| Today | Session state, primary focus action, protected-app and screen-time summary. | `ui/home/HomeScreen.kt` |
| Focus | Timer stored as `startedAt + durationMs`. Survives restarts. No notification, no enforcement. | `ui/focus/`, `data/Preferences.kt` |
| Protect | Real launchable apps via `<queries>`. Selection saved in `UserPreferences.protectedApps`. **Nothing is blocked.** | `ui/protect/`, `data/AppCatalog.kt` |
| Insights | Today's foreground time since midnight, top 8 apps. Computed, never stored. | `ui/insights/`, `data/ScreenTime.kt` |
| Settings | Theme, default length, reduce motion, permission status, privacy, licenses. | `ui/settings/` |
| Design system | Tokens, components, icons, `HorizonGlow`, `HorizonArc`, the mark. | `ui/theme/`, `ui/components/` |
| Tests | `LogicTest`: usage aggregation, session maths, formatting. | `app/src/test/` |

Constraints the current code sets, which this plan keeps unless a decision changes them:

- **Local-first.** No account, no analytics, no crash SDK, no backend, no `INTERNET` permission.
- **No cloud backup.** `allowBackup="false"` and `data_extraction_rules.xml`.
- **Small architecture.** One module, manual DI in `AuroraApplication`, one ViewModel per screen,
  DataStore Preferences, string routes.
- **Honest UI.** Every surface says what is not live yet. `ComingSoonRow` and the "Preview"
  `Notice` mark unbuilt features. The rule from `ARCHITECTURE.md` stands: **the UI claims
  "protected" only when `ProtectionStatus` says `Active`.**
- **Brand rules.** Semantic colours only, `auroraTween()` and reduced-motion fallbacks, no
  shaming copy, lowercase "aurora", own icon set, no drop shadows, no red for normal behaviour.

## 2. Vocabulary

| Name in this plan | What it is | Where it lives in the app |
| --- | --- | --- |
| **Shield** | The app-protection engine and the full-screen moment that appears over a protected app. | Configured on the Protect tab. Engine in a new `protect/` package. |
| **Intent** | "Pause before opening": a breath, a question about why, then a time-boxed opening. | A Shield mode, chosen per app. |
| **Focus** | The existing session timer, now enforced, with notifications and history. | Focus screen, Today hero. |
| **Insights** | Today plus history: week view, trends, Shield and Focus results. | Insights tab. |
| **Schedules** | Time windows when Shield is on without starting a session. | Protect tab. |
| **Time limits** | A daily budget per app. Shield appears when it runs out. | Protect tab, per app. |
| **Strict mode** | A commitment that hides in-app ways to turn protection off until it ends. | Focus setup, schedules. |
| **Light** | aurora's gentle progress system (gamification). | Insights, completion moments. |
| **aurora plus** | The premium subscription. The brand name stays lowercase, as `BRAND.md` requires. | Settings, upgrade moments. |
| **Website filtering** (1.1) | Shield for chosen domains in supported browsers. | Protect tab. |
| **Supported content blocking** (1.1) | Shield for specific in-app surfaces, such as short-video feeds, in supported apps. | Protect tab. |

D1 settles whether the tab is called "Protect" or "Shield".

## 3. Architecture direction

The aim is the smallest structure that holds all the features. Add each piece in the phase that
first needs it, not earlier.

### 3.1 One decision function

Every feature is an input to one pure function:

```kotlin
// protect/Policy.kt (Phase 3), grown by later phases
fun decide(target: Target, now: Long, state: PolicyState): Decision
```

- `Target` is an app package. In 1.1 it can also be a web domain (Phase 12) or a content
  surface (Phase 13).
- `PolicyState` holds the protected set, per-app modes, the active session, schedules, limits,
  today's usage, open grants and strict commitments. It is a plain data snapshot.
- `Decision` is `Allow`, `Shield(reason)`, or `Intent(reason)`. `reason` drives the copy.

The accessibility service only translates events into `Target`, calls `decide`, and acts on the
result. All rules are unit tested through `decide` with no Android in the loop. This is the
most important technical decision in the plan.

### 3.2 Packages, not modules

Stay in one Gradle module. New packages:

```
app/src/main/java/app/aurora/
├── protect/    ShieldService (AccessibilityService), Policy.kt, ProtectionStatus, the Shield surface
├── focus/      Session alarms and notifications (Phase 5)
├── rules/      Schedules, limits, strict mode models and pure evaluation (Phase 6)
├── light/      Progress rules (Phase 8)
├── billing/    Play Billing wrapper and entitlement (Phase 9)
├── web/        Browser URL readers, domain matching (Phase 12, 1.1)
├── content/    Surface rules and matchers (Phase 13, 1.1)
└── data/       Existing repositories, plus the Room database from Phase 5
```

Split into Gradle modules only if build times or ownership make it necessary.

### 3.3 Storage

| Data | Store | Introduced |
| --- | --- | --- |
| Preferences, protected set, active session, per-app mode | DataStore Preferences (existing) | Phase 1 |
| Open grants ("allowed until") | DataStore Preferences | Phase 4 |
| Focus history, Shield and Intent events, schedules, limits, Light | Room | Phase 5 |
| Entitlement cache | DataStore Preferences | Phase 9 |
| Blocked domains, content rules | Room, bundled asset | Phase 12, 13 |

Room arrives in Phase 5, when the first history appears. It adds the Room runtime and the KSP
plugin. Never store a URL, page title or screen content. Only store the matched rule and a timestamp.

### 3.4 Platform mechanisms

Each mechanism below depends on a gate in section 5. None of them is confirmed yet.

| Need | Proposed mechanism | Gate |
| --- | --- | --- |
| Know which app is in front | `AccessibilityService`, `TYPE_WINDOW_STATE_CHANGED` | G2 |
| Show the Shield | An aurora activity started from the service in its own task, or an accessibility overlay window if that fails | G1 |
| Focus countdown outside the app | A notification with a countdown chronometer, no foreground service | G5 |
| Focus end alert | `AlarmManager`: exact when `canScheduleExactAlarms()`, otherwise inexact while-idle | G6 |
| Re-arm after reboot | `RECEIVE_BOOT_COMPLETED` receiver | — |
| Schedules | Evaluated inside `decide` at each foreground event, so no alarm is needed to enforce them | — |
| Time limits | Existing `foregroundTime()` over `UsageStatsManager` events | — |
| Week view | `UsageStatsManager` event history | G7 |
| Subscriptions | Google Play Billing Library | G8 |
| Read a browser URL (1.1) | Accessibility node tree of supported browsers' address bars | G10, G11 |
| Detect an in-app surface (1.1) | Accessibility node signatures per supported app version | G12 |

Usage-stats polling as a Shield fallback for people who refuse accessibility access is deferred.
Add it only if beta data shows many people decline accessibility but still want protection, or if
G1 or G2 fails.

## 4. Brand guardrails for new surfaces

These rules apply to every phase. Reviewers check them in each acceptance pass.

1. **Shield is calm, not a wall.** Use the full-screen dark field, a dim neutral `HorizonGlow`,
   the rising point, one Primary action ("Close app") and one Quiet action. Never use red, sirens,
   a shaking animation or guilt copy. Example: "This is one of your protected apps. Ready to
   head back?"
2. **Intent is a breath.** The delay uses the glow's 4.2 s breath. With reduced motion on, show a
   static glow and a plain countdown label. Never loop.
3. **Light is the reward.** Progress shows as more light: a brighter horizon and points of light.
   There are no badges, confetti, mascots or leaderboards.
4. **No streak anxiety.** Nothing resets to zero because of a missed day.
5. **Honest status everywhere.** Each capability gets a `ProtectionStatus`-style truth source.
   The UI never claims more than the engine does. Unsupported cases and strict-mode limits are
   stated in the interface.
6. **Premium is respectful.** Show the real price and renewal terms. Show no countdown timers,
   fake discounts or pre-ticked trials. Never interrupt a Focus session or a Shield moment with an
   upsell. Turning protection off is never paywalled.
7. **Components first.** Build from `ui/components/`. Draw new icons in `AuroraIcons` (24dp grid,
   1.75 stroke). Retire each `ComingSoonRow` only when the feature behind it is live.
8. **Accessibility.** Every new screen has headings, roles and labels. It works at 200% font
   scale and with TalkBack. Timers announce at minute precision.

## 5. Validation gates

Every Android or Play policy assumption in this plan is unverified. Each one is a **blocking
gate**: its dependent phase must not start implementation until the gate passes.

### 5.1 Rules

1. **Spike, don't build.** Validate a gate with a throwaway spike on its own branch, named
   `spike/<gate-id>`. Never merge spike code into `main`. The phase rebuilds the mechanism properly.
2. **Record the result.** Write the result, the devices and API levels tested, and the date into
   the gate table below. Write the chosen mechanism into `ARCHITECTURE.md`.
3. **A failed gate stops the phase.** Re-plan the phase from the result. Never build around a failed
   gate quietly, and never ship UI that implies a capability a failed gate ruled out.
4. **Policy gates need an authoritative source.** Use the current Google Play policy text and the
   Play Console declaration form, quoted with a date. Where possible, confirm through a Play
   review of a minimal build on a closed testing track (Phase 2 sets up the Play Console app).
5. **Re-check before release.** Policies and platform behaviour change. Phase 11 re-validates
   every passed gate against the current Android release and Play policy.

### 5.2 Gate table

| Gate | Assumption to validate | Blocks | Status |
| --- | --- | --- | --- |
| **G1** | Full-screen interception is feasible (details in 5.3). | **Phase 3, all of it** | Not started |
| G2 | Play allows a digital-wellbeing app to use `AccessibilityService` for app protection, with a prominent disclosure, a Play Console declaration and `isAccessibilityTool=false`. | Phase 3 | Not started |
| G3 | aurora can detect when Android (for example Advanced Protection mode) or an OEM disables or kills a non-tool accessibility service, and can report `NeedsPermission`. | Phase 3 | Not started |
| G4 | Sideloaded test builds on Android 13+ need "Allow restricted settings" before the service can be enabled, and Play installs don't. | Phase 3 tester instructions | Not started |
| G5 | A notification with a countdown chronometer and `setTimeoutAfter` keeps correct time without a foreground service, on API 26–37. | Phase 5 notification work | Not started |
| G6 | Measured delivery delay of inexact while-idle alarms in Doze. It also decides whether aurora can ever qualify for exact-alarm access under Play policy. | Phase 5 alarm work | Not started |
| G7 | `UsageStatsManager` keeps enough event history for a 7-day week view on most devices. | Phase 7 week view | Not started |
| G8 | Play Billing Library works without forcing permissions that break the privacy claim. If it adds network permissions, the privacy copy and Data safety answers are rewritten before Phase 9 ships. | Phase 9 | Not started |
| G9 | Current Play rules for new developer accounts (for example, a closed test with a minimum number of testers for a minimum period before production). | Phase 11 schedule | Not started |
| G10 | Play allows reading browser address bars through accessibility for website filtering. | Phase 12 (1.1) | Not started |
| G11 | The address-bar node is readable in each candidate browser, in normal and private tabs. Each browser passes or fails on its own. | Phase 12, per browser | Not started |
| G12 | Each candidate content surface can be detected reliably through accessibility, and Play policy allows acting on it. | Phase 13, per surface | Not started |

### 5.3 G1: full-screen interception feasibility

This gate comes first. Nothing in Phase 3 is implemented until it passes.

**Questions the spike must answer:**

1. Can a bound `AccessibilityService` start a full-screen aurora activity from the background on
   API 26, 29, 31, 34, 36 and 37? Check the background activity start rules for each level.
2. If not, does a `TYPE_ACCESSIBILITY_OVERLAY` window drawn by the service work as the Shield,
   with focus, TalkBack and the keyboard behaving correctly?
3. How long does it take from the protected app's window event to the visible Shield? Measure
   the P90 over 50 opens on a mid-range device.
4. Is the protected app's content hidden and impossible to use behind the Shield? Check that
   Back, Home, Recents and the notification shade never reveal it or let it take input.
5. Does interception work when the app opens from the launcher, Recents, a notification, a deep
   link, a widget, split-screen and picture-in-picture?
6. Does it work on a Pixel (stock Android), a Samsung (One UI) and one OEM with aggressive battery
   management (Xiaomi or OnePlus), including after a reboot and after aurora is force-stopped?

**Pass criteria:** one mechanism meets 1 or 2, a P90 under 300 ms, and 4–6 on all three devices.

**Output:** a short written result in the gate table and `ARCHITECTURE.md`, with the chosen
mechanism, the measured latency and any device exceptions.

**If G1 fails:** stop Phase 3. Evaluate the fallbacks in this order and present them for a decision:

1. send the person home with `GLOBAL_ACTION_HOME`, then show the Shield inside aurora;
2. a slower, usage-stats-based check with a notification instead of a full-screen Shield.

Either fallback changes the product promise. Update the brand copy before building it.

## 6. Phase overview

| Phase | Name | Depends on | Gates | Size | Release |
| --- | --- | --- | --- | --- | --- |
| 2 | Groundwork: test, CI, permissions UX, Play Console | 1 | — | S | 1.0 |
| 3 | Shield core | 2 | **G1**, G2, G3, G4 | L | 1.0 |
| 4 | Intent | 3 | (Phase 3 gates) | M | 1.0 |
| 5 | Focus, enforced | 3 | G5, G6 | M | 1.0 |
| 6 | Schedules, time limits, strict mode | 3, 5 | — | L | 1.0 |
| 7 | Insights history | 4, 5, 6 | G7 | M | 1.0 |
| 8 | Light (gamification) | 4, 5, 7 | — | M | 1.0 |
| 9 | aurora plus (premium) | 3–8 | G8 | M | 1.0 |
| 10 | Hardening: accessibility, localisation, performance, Android 17 | 2–9 | — | M | 1.0 |
| 11 | Release 1.0 | 2–10 | G9, re-check all | M | 1.0 |
| 12 | Website filtering | 1.0 shipped | G10, G11 | L | 1.1 |
| 13 | Supported content blocking | 12 | G12 | L | 1.1 |

Sizes are relative: S ≈ days, M ≈ 1–2 weeks, L ≈ 2–4 weeks for one developer.

**1.0 priorities (D13).** Reliable app protection, intentional use, Focus and Insights come first.
If the schedule slips, cut from the end of the 1.0 list (Light, then parts of Phase 6) before
cutting reliability work in Phases 3, 5 or 10.

**1.1.** Phases 12 and 13 start after 1.0 is live. Their gates (G10–G12) may be checked earlier,
but only if that doesn't delay 1.0.

**Versions.** `0.2.x` after Phases 3–5, `0.3.x` after Phases 6–8, `1.0.0` at release, `1.1.0` with
website filtering.

---

## Phase 2: Groundwork

**Goal.** Make the next phases safe to build: automated checks, UI test infrastructure, one
consistent way to ask for permissions, and a Play Console app for policy validation.

**Gates:** none.

### Tasks

1. **CI.** Add a GitHub Actions workflow that runs `./gradlew testDebugUnitTest lintDebug
   assembleDebug` on every push and pull request. Cache Gradle. Add a nightly emulator job
   (API 26, 30, 34 and 36) that runs instrumented tests once they exist.
2. **Compose UI tests.** Add `androidx.compose.ui:ui-test-junit4` and the instrumentation runner.
   Write the first smoke tests: onboarding completes, tabs switch, Focus starts and ends early.
3. **Screenshot tests.** Pick one tool (D8). Cover each screen in light, dark, 200% font scale
   and reduced motion. Check the baseline images into the repository.
4. **Permission flow helpers.** Add one reusable "explain, open Settings, re-check on resume"
   pattern. Generalise `rememberUsageAccess()` into small `remember…Access()` helpers for usage
   access, accessibility, notifications and exact alarms. Reuse `EmptyState`, `Notice` and
   `AuroraDialog`.
5. **Play Console.** Create the developer account and the app entry, so policy gates (G2, G9) can
   use a closed testing review. Create no products and publish no listing yet.
6. **Strings.** Keep copy inline. It moves to `strings.xml` in Phase 10, as `ARCHITECTURE.md` says.
7. **Lint.** Turn on `warningsAsErrors` for new code, with a baseline for current warnings if needed.

### Dependencies

- `androidx.compose.ui:ui-test-junit4`, `androidx.test:runner`, `androidx.test.ext:junit`, and
  one screenshot tool.
- GitHub Actions on `github.com/aagamjain463/aurora-android`.

### Acceptance criteria

- CI is green on `main`, and a failing unit test blocks a merge.
- At least 3 Compose UI tests and a screenshot set for all six screens exist and pass.
- The permission helpers replace the ad-hoc checks without changing behaviour.
- The Play Console app exists and can receive a closed testing build.

### Testing

- Existing `LogicTest` keeps passing.
- A person reviews screenshot diffs on every change to `ui/theme/` or `ui/components/`.

---

## Phase 3: Shield core

**Goal.** Opening a protected app shows the aurora Shield, reliably, and every surface tells the
truth about whether protection is on.

**Gates:** **G1 first, before any other Phase 3 work.** Then G2, G3 and G4. If G1 fails, follow
the fallback in section 5.3 and do not start these tasks.

### Tasks

1. **`protect/Policy.kt`.** Add `Target`, `PolicyState`, `Decision` and `decide()` (section 3.1).
   The first rule is: shield a protected package during an active Focus session, or always if the
   person picks "always" for that app (D3).
2. **`ShieldService`.** An `AccessibilityService` that listens to `TYPE_WINDOW_STATE_CHANGED`
   only, with `canRetrieveWindowContent=false` and `isAccessibilityTool=false`.
   - Ignore aurora itself, the launcher, the system UI, the keyboard, and the dialer during
     calls. Never shield emergency or phone flows.
   - Debounce repeated events for the same package.
   - Read `PolicyState` from an in-memory snapshot kept current by collecting the repositories.
     Do not read DataStore on every event.
3. **The Shield surface.** Use the mechanism that G1 chose. It shows the app icon and label, the
   reason ("You're in a focus session until 14:30"), "Close app" (Primary: go home) and, outside
   strict mode, a Quiet "Open anyway" action. Back goes home, not into the protected app.
4. **`ProtectionStatus`.** A flow with the values `Off`, `NeedsPermission`, `Active` and `Paused`,
   derived from "the service is enabled and connected" plus "at least one app protected". It
   uses the detection method that G3 confirmed. It replaces:
   - the "Preview" `Notice` on Protect;
   - "Saved · blocking arrives in an update" on Today;
   - the Focus setup caption "Your apps stay available during a session…";
   - the "App blocking" row in Settings, which becomes a live status row.
5. **Accessibility disclosure.** A prominent in-app disclosure before sending the person to
   Accessibility settings. It states what is read (the name of the app in front), that nothing
   leaves the phone, and how to turn it off. G2 decides the exact required wording. The same text
   goes into the Play declaration.
6. **Onboarding.** Update the "how it works" step to describe what Shield really does. Keep the
   permission optional during onboarding, and ask for it in context on Protect.
7. **Per-app mode.** Replace the checkbox with a mode per app: `Off`, `Shield` and, in Phase 4,
   `Intent`. Store it in DataStore as a map from package to mode. Migrate the existing
   `protectedApps` set to `Shield` mode with a one-time read of the old key.
8. **Uninstalled apps.** Prune package names that no longer resolve, on catalog load.
9. **Tester instructions.** Document how to enable the service on sideloaded builds, based on G4.

### Dependencies

- Phase 2 permission helpers and UI test infrastructure.
- Passed gates G1–G4.

### Acceptance criteria

- With the service enabled and a session running, opening a protected app from every entry point
  listed in G1 shows the Shield. P90 latency is under 300 ms on the reference mid-range device.
- "Close app" lands on the home screen. Back from the Shield never reveals the protected app.
- When the service is disabled (by the person, by the system or after a crash), every surface
  shows `NeedsPermission` within one resume, and nothing claims protection.
- No Shield appears for aurora, the launcher, system UI, the keyboard, or phone and emergency flows.
- The service holds no work when no rule is active: no wake locks and no polling.
- Copy passes the voice rules in `BRAND.md`.

### Testing

- **Unit:** `decide()` truth table: protected or not, session or not, session just ended, app
  uninstalled, aurora itself, launcher.
- **Unit:** the migration from `protectedApps` to per-app modes.
- **Instrumented:** a UiAutomator test that protects a stock emulator app (for example Clock),
  starts a session, opens the app and asserts that the Shield is visible. Enable the service in
  test setup with `adb shell settings put secure enabled_accessibility_services …`.
- **Manual matrix:** the three G1 devices. Check reboot, force-stop of aurora, low-memory kill
  and split-screen.

---

## Phase 4: Intent

**Goal.** For apps in Intent mode, aurora asks for a moment before the app opens, then lets the
person in for a time they choose.

**Gates:** none of its own. It relies on the Shield surface that passed G1.

### Tasks

1. **Intent flow** on the Shield surface:
   1. A breath: the glow brightens over one 4.2 s breath. With reduced motion, a static glow and
      "Take a breath" with a plain 4-second label.
   2. A question: "What are you opening Instagram for?" with quick choices (for example
      "A message", "Something specific", "Just browsing") and an optional short text.
   3. A choice of time: 5, 10 or 15 minutes, using `ChoicePills`.
   4. Two actions: "Open for 5 min" (Secondary) and "Not now" (Primary). The intentional choice
      is the Primary action.
2. **Grants.** Opening creates a grant `{ package, until }` in DataStore. `decide()` returns
   `Allow` while a grant is open. When it expires and the app is still in front, Intent appears
   again with "Your 5 minutes are up".
3. **Events.** Record each Intent outcome (opened or not, reason, minutes) for Insights and Light.
   They are stored in Room from Phase 5. If Phase 4 ships first, keep outcomes in memory only
   and note it in the code.
4. **Retire** the "Pause before opening" `ComingSoonRow`.

### Acceptance criteria

- An app in Intent mode never opens without the breath, unless an open grant exists.
- Grants survive process death and are honoured to the minute.
- "Not now" is the visually primary action. "Open" is always available outside strict mode.
- Reduced motion shows no movement at all in the flow.
- TalkBack reads the question, the choices and the time, and the breath doesn't block focus.

### Testing

- **Unit:** grant expiry at `until - 1`, `until` and `until + 1`, overlapping grants, clock changes.
- **Compose UI:** the full flow in both themes, with reduced motion on and off.
- **Instrumented:** open, wait for expiry (shortened with a debug-only duration), see Intent again.

---

## Phase 5: Focus, enforced

**Goal.** A focus session protects the person even when aurora is closed, ends with a gentle
notification, and is remembered.

**Gates:** G5 blocks the countdown notification. G6 blocks the end-alarm design. The Room and
enforcement work can start without them.

### Tasks

1. **Room.** Add the database with a `focus_session` table (start, planned duration, actual end,
   intention, outcome: completed or ended early) and a `shield_event` table (time, target, kind,
   outcome). Export the schema to `app/schemas/` and test migrations from version 1 onward.
2. **Session lifecycle.** On "Begin focus":
   - write the session (existing DataStore keys) and a Room row;
   - post an ongoing countdown notification (mechanism from G5);
   - schedule the end alarm (mechanism from G6).

   On completion or early end: update the Room row, cancel the alarm and notification, and post
   "Time well spent" on completion.
3. **Permissions.** Ask for `POST_NOTIFICATIONS` (API 33+) in context, at the first session start.
   If it is denied, the session still works and the setup screen says the end won't be announced.
   Ask for exact alarms only if G6 shows aurora qualifies and the person turns on "exact end time"
   in Settings.
4. **Boot.** A `RECEIVE_BOOT_COMPLETED` receiver re-posts the notification and re-arms the alarm
   for a running session, or completes it if it ended while the phone was off.
5. **Enforcement copy.** The Running screen, the Today hero and the notification state which apps
   are shielded, based on `ProtectionStatus`.
6. **Completion.** The Complete phase reads from Room, so the completion moment appears exactly
   once, even if aurora was closed when the session ended.

### Dependencies

- Phase 3. Room runtime and the KSP plugin (new dependencies).

### Acceptance criteria

- A session left with aurora swiped away still shields protected apps and shows a live countdown
  in the notification shade.
- The end is announced on time with exact alarms, or within the delay G6 measured without them.
  The completion moment is correct when aurora next opens either way.
- Reboot during a session keeps the session and the countdown, or completes it correctly.
- Denying notifications never breaks a session.
- Each session is in Room with the correct outcome.

### Testing

- **Unit:** session state machine with an injectable clock: start, early end, completion,
  completion while the device is off, a double start.
- **Room:** `MigrationTestHelper` for every schema version.
- **Instrumented:** start a 1-minute debug session, kill the process, verify the notification and
  alarm (`adb shell dumpsys alarm`), then the completion.
- **Manual:** Doze (`adb shell dumpsys deviceidle force-idle`) and App Standby buckets.

---

## Phase 6: Schedules, time limits and strict mode

**Goal.** Protection that holds without remembering to start a session. This retires the
remaining "On the way" rows.

**Gates:** none of its own.

### Tasks

1. **Schedules.** A schedule has a name, days of the week, a start and end time (it may cross
   midnight), a set of apps and a mode (Shield or Intent). Store schedules in Room. `decide()`
   evaluates them against local time at each event.
2. **Time limits.** A daily minute budget per app, reset at local midnight. Today's usage comes
   from `foregroundTime()` with Usage access. Cache it per package and update it from service
   events, so each app switch does not query a whole day of events. When the budget runs out,
   `decide()` returns Shield with "You've used your 30 minutes of YouTube today". Without Usage
   access, the limits UI explains why limits are unavailable and offers the permission.
3. **Strict mode.** A commitment `{ until, scope }` stored in DataStore. While it is active:
   - "End session", "Open anyway" and the Intent "Open" action are hidden;
   - the scope's apps, schedules and limits can't be edited or deleted in aurora;
   - Settings in aurora shows exactly what strict mode cannot stop: uninstalling aurora, turning
     off the accessibility service in Android Settings, safe mode and `adb`.

   D5 decides whether to also shield Android's own settings screens. The default is no.
4. **Protect tab redesign.** Sections "Apps", "Schedules" and "Limits", each a `RowGroup` with live
   status. A "Websites" `ComingSoonRow` may stay until 1.1. Build the schedule editor from existing
   components: `ChoicePills` for days and a Material 3 time picker themed through `toMaterial()`.
5. **Today.** Show the current or next schedule ("Work until 17:00") in the status pill.
6. **Optional notifications.** A quiet notice when a schedule starts. Off by default.

### Acceptance criteria

- A schedule from 22:00 to 07:00 shields at 23:59 and 00:01 on the right days, across DST changes
  and time-zone changes.
- A limit shields within one app switch after the budget runs out, and resets at local midnight.
- In strict mode, no path inside aurora ends the commitment early, and the UI never implies that
  uninstalling or disabling the service is blocked.
- Overlapping rules resolve in one documented order: strict, then Shield, then Intent, then Allow.

### Testing

- **Unit:** schedule evaluation with fixed `ZoneId` values: midnight crossing, DST
  spring-forward and fall-back, a zone change during a schedule.
- **Unit:** limit accounting against recorded `UsageSignal` fixtures, in the `LogicTest` style.
- **Unit:** rule precedence in `decide()`.
- **Compose UI:** schedule editor, limit editor, the strict-mode locked state.
- **Instrumented:** a schedule that starts one minute from now shields the test app.

---

## Phase 7: Insights history

**Goal.** Insights shows a calm week, not only today: where time went, what Focus achieved, and
how often the person chose not to open an app.

**Gates:** G7 blocks the week view of screen time. The Focus and Intent summaries come from
aurora's own Room data and can start without it.

### Tasks

1. **Week view.** Daily totals for the last 7 days from `UsageStatsManager`, computed with
   `foregroundTime()` per day window. Use the retention G7 measured. If a day is not available,
   say so instead of showing zero.
2. **Focus summary.** Minutes focused per day and completed sessions, from Room.
3. **Shield and Intent summary.** "You chose not to open Instagram 6 times this week", from Room.
   Phrase it as a choice the person made, never as a count of failures.
4. **Per-app detail.** Tap an app to see its week and its rules. "Add a limit" links to Phase 6.
5. **Charts.** Simple bars drawn with Compose `Canvas` in the existing `UsageBar` style. No chart
   library. The brand avoids dense charts.
6. **Privacy.** Usage stays computed and is never stored. Only aurora's own Focus and Shield events
   live in Room. Update the Privacy dialog in Settings to match.
7. **Data controls.** "Delete history" in Settings clears the Room history tables.

### Acceptance criteria

- The week view matches the sum of per-day computations for the same windows.
- Days without available data are labelled, not shown as zero.
- No copy compares the person to others or uses alarming language.
- "Delete history" empties all history tables, and Insights updates immediately.

### Testing

- **Unit:** day-window splitting across midnight and DST, using `foregroundTime()` fixtures.
- **Unit:** aggregation queries with an in-memory Room database.
- **Screenshot:** an empty week, a partial week and a full week, in both themes and at 200% font.

---

## Phase 8: Light (gamification)

**Goal.** Make intentional use feel rewarding in aurora's own language, light rising out of the
dark, with no pressure and no loss.

**Gates:** none.

### Tasks

1. **Points of light.** Each completed Focus session and each "Not now" in Intent adds a point of
   light. Sessions ended early still add light for the minutes focused.
2. **Your sky.** A section in Insights draws the week as a horizon. Points of light sit above it,
   and the `HorizonGlow` intensity rises with the week's light. It is static under reduced motion.
3. **First lights.** Quiet milestones, shown once in the completion moment: the first session, the
   first hour focused, the first week with a session every day. They use warm Dawn colours.
4. **Gentle weekly intention.** An optional goal, for example "Focus 3 times this week", shown as a
   row of dots. Days without a session are simply empty. Nothing resets.
5. **Opt-out.** A "Show light and milestones" toggle in Settings, on by default (D9).
6. **Pure rules.** `light/` holds the rules as pure functions over Room events. Light is computed,
   not stored, so a rule change never corrupts history.

### Acceptance criteria

- No copy, colour or animation implies loss, failure or ranking. A brand review signs this off.
- Missing a day never reduces any number shown.
- Light totals equal a recomputation from raw events.
- Everything works without motion and reads well with TalkBack ("12 points of light this week").

### Testing

- **Unit:** light rules and milestone triggers from event fixtures, including early-ended sessions.
- **Screenshot:** empty, typical and full sky, in both themes and with reduced motion.

---

## Phase 9: aurora plus (premium subscriptions)

**Goal.** A fair subscription that funds aurora, sold with the same honesty as the rest of the
product.

**Gates:** G8 blocks this phase.

### Tasks

1. **Free and plus split (D2, settled).** Basic protection and intentional opening stay free.
   Advanced features are reserved for aurora plus. Exact limits are decided after user testing in
   Phase 11's closed test. The starting split for that test:

   | Free | aurora plus |
   | --- | --- |
   | Shield for apps (basic protection) | Everything in free |
   | Intent (pause before opening) | Schedules beyond a basic one |
   | Focus sessions with Shield | Time limits |
   | Today's Insights | Strict mode |
   | Light | Insights history and per-app detail |
   | | Website filtering and content blocking, when they ship in 1.1 |

   Open questions for user testing: whether free has a cap on protected apps and what it is,
   whether one schedule is free, and the trial length. Keep each limit as one named constant in
   `billing/`, so changing it after testing is a one-line change.
2. **Billing.** Add the Google Play Billing Library behind one small class in `billing/`. It
   connects, queries products, launches the purchase flow, acknowledges purchases within the
   required window, and exposes `entitlement: StateFlow<Entitlement>`.
   Products: monthly and annual subscriptions, with an optional trial on annual (D10).
3. **Entitlement without a backend.** Trust Play Billing's purchase state, cached in DataStore with
   a "last confirmed" time. Recheck on start and on resume, and keep plus for a grace period while
   offline. Record the trade-off: without server verification, a determined person can bypass the
   check. That is acceptable for 1.0.
4. **Lapse behaviour.** If the subscription ends, plus-only rules **pause** with a clear notice.
   They are not deleted, and they come back on renewal. A running Focus session or strict-mode
   commitment always finishes as promised.
5. **Paywall.** One calm screen built from `AuroraScreen`, `RowGroup` and one Primary button. It
   lists what plus adds, the real price from Play, the renewal terms, "Restore purchases" and a
   link to manage the subscription in Play. It opens from upgrade points and from Settings. It
   never opens during Focus or a Shield moment.
6. **Settings.** An "aurora plus" row with status, renewal date and "Manage subscription".
7. **Privacy claims.** Apply the G8 result: if Billing adds network permissions, rewrite every
   privacy claim (README, the Settings Privacy dialog, onboarding, the Play listing and Data
   safety) before release. Do not strip a permission Billing needs.

### Dependencies

- Play Console products and license testers. Phases 3–8 for the features to gate.

### Acceptance criteria

- Purchase, restore, cancel, grace period, account hold and expiry all leave the app in the
  correct state, with no lost rules.
- Every purchase is acknowledged in time.
- Turning protection off, ending a non-strict session and deleting data are never paywalled.
- Basic protection and Intent work fully without a subscription.
- The paywall passes the brand review: no urgency tricks, and the real price is shown.

### Testing

- **Unit:** entitlement state machine (unknown, active, grace, lapsed, restored) with a fake billing
  client, and the "pause, don't delete" logic.
- **Play testing:** license-tester accounts, test cards and accelerated renewal periods on
  internal testing.
- **Manual:** airplane mode during and after a purchase, and a reinstall followed by restore.

---

## Phase 10: Hardening

**Goal.** aurora feels premium on every device: accessible, fast, translated and stable on
Android 17.

**Gates:** none of its own. It re-runs the G1 and G3 device checks on API 37.

### Tasks

1. **Accessibility audit.** TalkBack, Switch Access, 200% font scale and display size, and
   colour contrast on every new screen against the `BRAND.md` thresholds.
2. **Localisation.** Move copy to `strings.xml` with plurals. Start with English, and add languages
   after D11. Test pseudo-locales (`en-XA`, `ar-XB`) for length and right-to-left layout.
3. **Performance.** Add a baseline profile and a Macrobenchmark module for cold start and Shield
   latency. If profiles show it matters, cache `AppCatalog.launchableApps()` with a package-change
   receiver, which resolves the `ponytail:` note in `AppCatalog.kt`.
4. **Battery.** Measure a full day with Shield, schedules and limits on, using Battery Historian.
5. **OEM resilience.** In-app guidance for OEMs that kill or disable accessibility services.
6. **Android 17.** Test on API 37, review its behaviour changes, then raise `targetSdk` to 37.
7. **Backup (D12).** Keep no cloud backup, or add an explicit, opt-in local export of rules and
   history (JSON through the system file picker).
8. **Security review.** Exported components, intent filters, `PendingIntent` mutability, and no
   logging of package activity in release builds.

### Acceptance criteria

- No open accessibility issues of severity "major" or higher.
- Cold start under 500 ms and Shield latency under 300 ms (P90) on the reference mid-range device.
- Measured battery cost of the service under 1% per day of normal use.
- `targetSdk = 37`, with all tests green on API 26 to 37.

### Testing

- Macrobenchmark and baseline profile on an emulator in CI, or on a device lab if available.
- A full regression of the Phase 3 manual matrix on API 37.

---

## Phase 11: Release 1.0

**Goal.** Ship 1.0 on Google Play with every declaration approved and a staged rollout.

**Gates:** G9 sets the schedule. Every passed gate is re-checked against the current Android
release and Play policy before submission.

### Tasks

1. **Signing.** Create the upload key, enrol in Play App Signing, and read the release signing
   config from environment variables. Never commit keys. `.gitignore` already excludes `*.jks`
   and `*.keystore`.
2. **R8.** Check the keep rules for Room, Billing and the accessibility service. Run the release
   build through the full test matrix.
3. **Play Console declarations.**
   - Accessibility API: the declaration form, the in-app prominent disclosure and a video of the
     disclosure and the feature.
   - Usage access: a justification in the listing and the disclosure.
   - Exact alarms: only if G6 showed aurora qualifies and uses them.
   - Data safety: based on the G8 result.
   - A privacy policy URL. This needs a hosted page that follows `BRAND.md`.
4. **Store listing.** Screenshots from the final screenshot-test set, in both themes. Copy in the
   brand voice: lowercase "aurora", no overclaiming, a clear note on what strict mode can and cannot
   do, and no mention of website or content blocking until 1.1 ships.
5. **Testing tracks.** Internal testing, then closed testing (length and size from G9), then
   production with a staged rollout: 5%, 20%, 50%, 100%.
6. **User testing for D2.** Use the closed test to settle the free limits and the trial length.
   Update the constants and this roadmap before production.
7. **Crash visibility without an SDK.** Use Play Console vitals only, which keeps the no-analytics
   promise.
8. **Docs.** Update `README.md`, `ARCHITECTURE.md` (Room, services, billing) and this roadmap.

### Acceptance criteria

- All Play declarations approved, and the listing live.
- Crash-free users at or above 99.5% and ANR rate under 0.47% across the staged rollout.
- The free limits are set from closed-test results and recorded in section 9.
- README, ARCHITECTURE and in-app copy describe the shipped product exactly.

---

## Phase 12: Website filtering (1.1)

**Goal.** Shield chosen websites in supported browsers, using accessibility and no VPN (D4).

**Gates:** G10 blocks the whole phase. G11 blocks each browser on its own: a browser that fails
G11 is not offered.

### Tasks

1. **Service upgrade.** Set `canRetrieveWindowContent=true` and listen to
   `TYPE_WINDOW_CONTENT_CHANGED` **only for the packages of supported browsers**, using a package
   filter set at runtime. Update the disclosure text and the Play declaration.
2. **Browser readers.** One small reader per browser that passed G11. It finds the address-bar node
   and returns the host. Candidates: Chrome, Samsung Internet, Firefox, Edge, Brave and DuckDuckGo
   (D6). Keep the view IDs in one table in code, so an update to a browser is a one-line fix.
3. **Domain matching.** `host == domain || host.endsWith(".$domain")`, after lower-casing and
   removing `www.`. No public-suffix list is needed for this rule.
4. **Action.** On a match, show the Shield with the domain ("example.com is on your list"). Then
   use `GLOBAL_ACTION_BACK`, or go home if the page can't be left.
5. **Protect UI.** A "Websites" section with add, remove and validation (strip the scheme and path,
   reject IP addresses and empty hosts). Each website can be always on, on during Focus or on a
   schedule, through the same rules as apps. It is an aurora plus feature (D2).
6. **Honesty.** A "Works in" list shows the supported browsers installed on this phone. An installed
   but unsupported browser is labelled "Not supported yet". In-app browsers, custom tabs and
   WebViews are named as not covered.
7. **VPN.** Deferred (D4). Revisit only with a separate decision, because a local VPN filter needs
   the `INTERNET` permission, conflicts with other VPNs and with Private DNS, and changes aurora's
   privacy promise.

### Acceptance criteria

- A blocked domain is shielded in every supported browser, in normal and private tabs, whether it
  is typed, opened from a link or restored from a tab.
- Subdomains match. Similar names do not: blocking `x.com` does not block `box.com`.
- URLs, page titles and other screen text are never stored or logged. Only the matched domain rule
  and a timestamp are stored.
- Battery cost stays under 1% per day, measured as in Phase 10.

### Testing

- **Unit:** domain normalisation and matching, including IDNs, trailing dots, ports, `www.` and uppercase.
- **Unit:** reader fixtures. Save `uiautomator dump` XML from each supported browser version,
  parse it into a node model and assert the extracted host. Keep fixtures in `app/src/test/resources/`.
- **Instrumented:** Chrome on the emulator opens a blocked domain, and the Shield appears.
- **Release check:** re-capture fixtures for each browser's current stable version before every release.

---

## Phase 13: Supported content blocking (1.1)

**Goal.** Shield specific distracting surfaces inside apps the person still needs, such as
short-video feeds, without blocking the whole app. Only surfaces aurora has tested are offered.

**Gates:** G12 blocks each surface on its own.

### Tasks

1. **Scope (D7).** Start with the candidates that pass G12, for example YouTube Shorts and
   Instagram Reels. Each entry names the app, the surface, the tested app version range and a node
   signature (view ID, content description or class pattern).
2. **Rules file.** Ship the rules as a versioned asset (`assets/content_rules.json`). aurora has no
   network access, so rules update only with app updates.
3. **Matcher.** A pure function from a node-tree snapshot to the matched surface. The service
   listens to content changes only for packages with an enabled rule.
4. **Action.** `GLOBAL_ACTION_BACK`, then a small, calm Shield moment ("Shorts are paused. The rest
   of YouTube is yours."). Never trap the person in a loop. After two quick back attempts, go home.
5. **Health check.** If the installed app version is newer than the tested range, the Protect UI
   says "May not work with this version of YouTube" instead of claiming coverage.
6. **Protect UI.** A "Content" section that lists only supported surfaces for installed apps.
   It is an aurora plus feature (D2).

### Acceptance criteria

- Every offered surface is blocked on its tested versions, and the rest of the app keeps working.
- No surface is offered for an app or version without a passing fixture.
- The service ignores content events from apps with no enabled rule (checked by logging in debug builds).

### Testing

- **Unit:** matcher against `uiautomator dump` fixtures, both positive (the surface) and negative
  (the home feed, search, the player, messages).
- **Manual:** on a real device with test accounts for each supported app, before each release.

---

## 7. Testing strategy (all phases)

| Layer | Tool | What it covers | When it runs |
| --- | --- | --- | --- |
| Pure logic | JUnit 4 (existing) | `decide()`, schedules, limits, grants, light rules, entitlement; domain and content matchers in 1.1 | Every push |
| Persistence | Room in-memory and `MigrationTestHelper` | Queries, migrations | Every push |
| UI | Compose UI tests | Onboarding, Focus, Intent, editors, paywall | Every push or nightly |
| Visual | Screenshot tests (D8) | Every screen, both themes, 200% font, reduced motion | Every push |
| System | UiAutomator on emulators | Service and Shield, alarms, boot; browsers in 1.1 | Nightly, API 26 / 30 / 34 / 36 / 37 |
| Fixtures (1.1) | `uiautomator dump` XML | Browser readers, content surfaces | Every push, re-captured before each release |
| Performance | Macrobenchmark | Cold start, Shield latency | Weekly and before release |
| Gates | Spikes on `spike/<gate-id>` branches | Platform and policy assumptions (section 5) | Before the dependent phase, and again before release |
| Manual | Device matrix | OEM behaviour, real apps, billing | Before each milestone build |

Rules:

- Every rule that can go wrong silently goes through a pure function with a unit test, as
  `foregroundTime()` does today.
- Inject the clock and the time zone into all time logic. No test sleeps for real minutes.
- Fixtures never contain personal data. Capture them with test accounts.

## 8. Out of scope

- The aurora launcher, device-owner and device-admin features (`ARCHITECTURE.md` keeps them for the
  launcher product).
- VPN-based filtering (deferred by D4).
- iOS, desktop and web apps beyond the privacy-policy page.
- Accounts, sync and any server.
- Parental control or monitoring of another person. aurora is for people managing their own attention.

## 9. Decisions

### Settled

| ID | Decision | Date |
| --- | --- | --- |
| D2 | Basic protection (Shield for apps) and intentional opening (Intent) stay free. Advanced features are reserved for aurora plus. Exact limits are finalised after user testing (Phase 11). | 2026-10-28 |
| D4 | Website filtering starts with accessibility-based reading of supported browsers, subject to feasibility (G11) and Play policy validation (G10). VPN integration is deferred. | 2026-10-28 |
| D13 | aurora 1.0 launches without website filtering and partial-app content blocking. 1.0 prioritises reliable app protection, intentional use, Focus and Insights. Website and content blocking move to 1.1. | 2026-10-28 |

### Open

Each open decision has a recommended default. A phase can start with the default. The decision is
confirmed before that phase's acceptance.

| ID | Decision | Recommended default | Needed by |
| --- | --- | --- | --- |
| D1 | Keep the "Protect" tab and call the moment "the shield", or rename the tab "Shield"? | Keep "Protect" (matches `BRAND.md` and the icon). Use "shield" in copy. | Phase 3 |
| D3 | Can apps be shielded "always", or only during sessions and schedules? | Allow "always" per app | Phase 3 |
| D5 | Should strict mode shield Android's own settings for aurora (accessibility, app info)? | No. It reads as hostile and raises policy risk. Say honestly what strict mode can't stop. | Phase 6 |
| D6 | Candidate browsers for G11 | Chrome, Samsung Internet, Firefox, Edge, Brave, DuckDuckGo | Phase 12 |
| D7 | Candidate content surfaces for G12 | YouTube Shorts, Instagram Reels | Phase 13 |
| D8 | Screenshot test tool | Google's Compose Preview Screenshot Testing plugin, or Roborazzi if the plugin isn't stable enough | Phase 2 |
| D9 | Light on by default? | On, with an opt-out | Phase 8 |
| D10 | Prices and trial | Monthly and annual, with a trial on annual. Prices set in Play after the closed test. | Phase 9 |
| D11 | First languages after English | Decide from the beta audience | Phase 10 |
| D12 | Backup and export | Opt-in local JSON export. Still no cloud backup. | Phase 10 |
