# aurora Android roadmap

This plan takes aurora from the Phase 1 foundation to a 1.0 release on Google Play, and then to
1.1. It has 13 phases. **Phase 2 is the next implementation phase.**

Read it with:

- [`BUSINESS_MODEL.md`](BUSINESS_MODEL.md): aurora Free and aurora plus, the limits, how they are
  counted, and the entitlement system;
- [`Aurora_Brand_Guidelines.md`](../brand/Aurora_Brand_Guidelines.md): the brand and design system;
- [`ARCHITECTURE.md`](ARCHITECTURE.md): the current architecture.

The brand guidelines win over this plan. `BUSINESS_MODEL.md` wins on what each plan includes.

- **Status:** approved plan. Phase 2 is in progress (see its status note).
- **Settled decisions:** D2 (business model), D4 (website filtering approach), D10 (India prices)
  and D13 (1.0 scope). See [section 9](#9-decisions).
- **Rule:** every unverified Android or Play assumption is a **blocking gate** for the phase that
  depends on it. See [section 5](#5-validation-gates).
- **Last reviewed against the code:** 2026-10-28, commit `1270c69`.

---

## 1. Where aurora is today

Phase 1 is a working, honest shell. It is one Gradle module of about 3,500 lines. This roadmap
keeps all of it and builds on it.

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

Full definitions are in `BUSINESS_MODEL.md` section 3. In short:

| Term | Meaning |
| --- | --- |
| **Shield** | The protection engine, and the full-screen moment that appears over a protected item. Configured on the Protect tab (D1). |
| **Protected item** | An app (1.0) or a website (1.1) that aurora watches. Free allows 5 in total. |
| **Protection modes** | Each item's everyday behaviour: Flexible, Friction, Cooldown, Intent, or Only during focus. |
| **Intent** | Intentional opening: a breath, a question about why, then a time-boxed opening. Free allows 3 apps. |
| **Focus session** | A timed session that shields a profile's items behind a Friction wait. Unlimited on Free. |
| **Focus profile** | A saved session setup: name, length, items, Lockdown on or off. Free allows 1. |
| **Lockdown** | The only state with no in-app way through. Free allows 120 minutes per local day. It replaces "strict mode" from earlier drafts. |
| **Daily app-limit rule** | A daily budget for one app or a group of apps. Free allows 3. |
| **Recurring schedule** | Starts a Focus profile on chosen days and times. Free allows 1. |
| **Light** | aurora's gentle progress system: points of light, goals, gentle streaks (Free) and challenges (plus). |
| **Entitlement** | The current plan (`Free` or `Plus`) and its limits, from one central module. |
| **aurora plus** | The premium subscription. Brand stays lowercase. |

## 3. Architecture direction

The aim is the smallest structure that holds all the features. Add each piece in the phase that
first needs it, not earlier.

### 3.1 One decision function

Every protection feature is an input to one pure function:

```kotlin
// protect/Policy.kt (Phase 3), grown by later phases
fun decide(target: Target, now: Long, state: PolicyState): Decision
```

- `Target` is an app package. In 1.1 it can also be a web domain or a content surface.
- `PolicyState` holds the protected items and their modes, the active session, Lockdown,
  schedules, limits, today's usage and open grants. It is a plain data snapshot.
- `Decision` is `Allow`, `Shield(reason, wayThrough)`, or `Intent(reason)`. `wayThrough` is
  `Immediate` (Flexible), `AfterWait(ms)` (Friction) or `None` (Lockdown only).
- When several rules apply, the strongest wins: Lockdown, then an active Cooldown or Focus
  session or spent limit (Friction), then the item's everyday mode, then Allow.

The accessibility service only translates events into `Target`, calls `decide`, and acts on the
result. All rules are unit tested through `decide` with no Android in the loop.

**`decide` never reads the plan.** Both plans get the same protection strength
(`BUSINESS_MODEL.md` principle 1). A test runs every policy case under `Free` and `Plus` and
expects identical decisions.

### 3.2 Entitlements

One central, pure module, `plan/Plan.kt`, built in Phase 2 (`BUSINESS_MODEL.md` section 5):

- `Plan` (`Free`, `Plus`), `Allowance`, `Feature`, the `FreeLimits` table, `canAdd()` and `has()`.
- One source of the current plan: always `Free` in release builds until Phase 11, with a debug-only
  switch to `Plus` for testing. From Phase 11, Play Billing feeds it.
- **Limits are implemented with their features.** Each feature phase checks its own allowance in the
  ViewModel action that adds or enables something. No limit is checked inside the protection engine.
- Before Phase 11, aurora never mentions plus at a limit, because it can't be bought yet.

### 3.3 Packages, not modules

Stay in one Gradle module. New packages:

```
app/src/main/java/app/aurora/
├── plan/       Plan, allowances, FreeLimits, the plan source (Phase 2)
├── protect/    ShieldService (AccessibilityService), Policy.kt, ProtectionStatus, the Shield surface (Phase 3)
├── focus/      Session alarms, notifications, profiles (Phase 5)
├── rules/      Schedules, limits, Lockdown and its daily ledger (Phase 6)
├── light/      Goals, streaks, challenges (Phase 8)
├── billing/    Play Billing wrapper that feeds the plan source (Phase 11)
├── web/        Browser URL readers, domain matching (Phase 13, 1.1)
├── content/    Surface rules and matchers (Phase 13, 1.1)
└── data/       Existing repositories, plus the Room database from Phase 5
```

Split into Gradle modules only if build times or ownership make it necessary.

### 3.4 Storage

| Data | Store | Introduced |
| --- | --- | --- |
| Preferences, protected items and modes, active session | DataStore Preferences (existing) | Phase 1, extended in Phase 3 |
| Debug plan switch | DataStore Preferences (debug builds only) | Phase 2 |
| Open grants, Cooldown timers | DataStore Preferences | Phase 4 |
| Focus history, shield and Intent events, profiles | Room | Phase 5 |
| Schedules, limit rules, Lockdown ledger | Room, DataStore for the ledger | Phase 6 |
| Daily per-app usage totals (only if D14 says yes) | Room | Phase 7 |
| Goals, streaks, challenges | Room (computed from events where possible) | Phase 8 |
| Entitlement cache | DataStore Preferences | Phase 11 |
| Blocked domains, content rules | Room, bundled asset | Phase 13 |

Never store a URL, page title or screen content. Store only the matched rule and a timestamp.

### 3.5 Platform mechanisms

Each mechanism depends on a gate in section 5. None of them is confirmed yet.

| Need | Proposed mechanism | Gate |
| --- | --- | --- |
| Know which app is in front | `AccessibilityService`, `TYPE_WINDOW_STATE_CHANGED` | G2 |
| Show the Shield | An aurora activity started from the service in its own task. The spike rejected an accessibility overlay as the primary Shield: the app underneath stays resumed and Recents removes it | G1 |
| Detect a disabled service | Service connection state plus `AccessibilityManager` checks on resume | G3 |
| Focus countdown outside the app | A notification with a countdown chronometer, no foreground service | G5 |
| Focus and Lockdown end alert | `AlarmManager`: exact when `canScheduleExactAlarms()`, otherwise inexact while-idle | G6 |
| Re-arm after reboot | `RECEIVE_BOOT_COMPLETED` receiver | — |
| Schedules | Evaluated inside `decide` at each foreground event, so no alarm is needed to enforce them | — |
| Daily limits | Existing `foregroundTime()` over `UsageStatsManager` events | — |
| Week view and longer history | `UsageStatsManager` history, plus local daily totals if D14 says yes | G7 |
| Subscriptions | Google Play Billing Library | G8, G13 |
| Read a browser URL (1.1) | Accessibility node tree of supported browsers' address bars | G10, G11 |
| Detect an in-app surface (1.1) | Accessibility node signatures per supported app version | G12 |

## 4. Brand guardrails

These rules apply to every phase. Reviewers check them in each acceptance pass.

1. **Shield is calm, not a wall.** Use the full-screen dark field, a dim neutral `HorizonGlow`, the
   rising point, one Primary action ("Close app") and one Quiet way through where the mode allows
   one. Never use red, sirens, a shaking animation or guilt copy.
2. **Friction and Intent are a breath.** Waits use the glow's 4.2 s breath. With reduced motion on,
   show a static glow and a plain countdown label. Never loop.
3. **Light is the reward.** Progress shows as more light: a brighter horizon and points of light.
   There are no badges, confetti, mascots or leaderboards.
4. **Streaks are gentle.** A missed day ends a run but never erases it. The best run and total days
   stay visible. One rest day a week doesn't break a run. No copy says "lost" or "broken", and no
   notification asks the person to protect a streak (`BUSINESS_MODEL.md` 3.7).
5. **Honest status everywhere.** The UI never claims more than the engine does. Lockdown always
   says what it can't stop.
6. **Limits are calm.** At a limit, aurora explains it and offers a way forward that doesn't involve
   paying. From Phase 11 it may add a Quiet link to plus. Never show an upsell during Focus, a
   shield moment or Lockdown. Turning protection off is never paywalled.
7. **Premium is respectful.** Show the real price and renewal terms. No countdown timers, fake
   discounts or pre-ticked trials. Never advertise a feature that doesn't exist, including cloud sync.
8. **Components first.** Build from `ui/components/`. Draw new icons in `AuroraIcons` (24dp grid,
   1.75 stroke). Retire each `ComingSoonRow` only when the feature behind it is live.
9. **Accessibility.** Every new screen has headings, roles and labels. It works at 200% font scale
   and with TalkBack. Timers announce at minute precision.

## 5. Validation gates

Every Android or Play policy assumption in this plan is unverified. Each one is a **blocking
gate**: its dependent phase, or the named part of it, must not start implementation until the gate
passes.

### 5.1 Rules

1. **Spike, don't build.** Validate a gate with a throwaway spike on its own branch, named
   `spike/<gate-id>`. Never merge spike code into `main`. The phase rebuilds the mechanism properly.
2. **Record the result.** Write the result, the devices and API levels tested, and the date into
   the gate table. Write the chosen mechanism into `ARCHITECTURE.md`.
3. **A failed gate stops the phase.** Re-plan from the result. Never build around a failed gate
   quietly, and never ship UI that implies a capability a failed gate ruled out.
4. **Policy gates need an authoritative source.** Use the current Google Play policy text and the
   Play Console declaration form, quoted with a date. Where possible, confirm through a Play review
   of a minimal build on a closed testing track. Phase 2 sets up the Play Console app.
5. **Re-check before release.** Phase 12 re-validates every passed gate against the current Android
   release and Play policy.

### 5.2 Gate table

| Gate | Assumption to validate | Blocks | Status |
| --- | --- | --- | --- |
| **G1** | Full-screen interception is feasible (details in 5.3). | **Phase 3, all of it** | In progress. Works on the API 36 emulator (activity Shield). Devices, other API levels and latency pending. See [`ENFORCEMENT_FEASIBILITY.md`](ENFORCEMENT_FEASIBILITY.md) |
| G2 | Play allows a digital-wellbeing app to use `AccessibilityService` for app protection, with a prominent disclosure, a Play Console declaration and `isAccessibilityTool=false`. | Phase 3 | Not started |
| G3 | aurora can detect when Android (for example Advanced Protection mode) or an OEM disables or kills a non-tool accessibility service, and can report `NeedsPermission`. | Phase 3 | In progress. Revoke, force-stop (which turns the service off), process kill and reboot detected on the API 36 emulator. Android 17 AAPM and OEMs untested |
| G4 | Sideloaded test builds on Android 13+ need "Allow restricted settings" before the service can be enabled, and Play installs don't. | Phase 3 tester instructions | Not started. `adb install` builds are not restricted (API 36 emulator), so test a file-manager install |
| G5 | A notification with a countdown chronometer and `setTimeoutAfter` keeps correct time without a foreground service, on API 26–37. | Phase 5 notification work | Not started |
| G6 | Measured delivery delay of inexact while-idle alarms in Doze, and whether aurora can qualify for exact-alarm access under Play policy. | Phase 5 alarm work | Not started |
| G7 | How much usage history `UsageStatsManager` keeps (events, and daily and weekly aggregates) on common devices. This sets what basic insights can show and whether advanced insights need local totals (D14). | Phase 7 | Not started |
| G8 | Play Billing Library works without forcing permissions that break the privacy claim. If it adds network permissions, every privacy claim and Data safety answer is rewritten before Phase 11 ships. | Phase 11 | Not started |
| G9 | Current Play rules for new developer accounts, for example a closed test with a minimum number of testers for a minimum period before production. | Phase 10 plan, Phase 12 date | Not started |
| G10 | Play allows reading browser address bars through accessibility for website filtering. | Phase 13 websites | Not started |
| G11 | The address-bar node is readable in each candidate browser, in normal and private tabs. Each browser passes or fails on its own. | Phase 13, per browser | Not started |
| G12 | Each candidate content surface can be detected reliably through accessibility, and Play policy allows acting on it. | Phase 13, per surface | Not started |
| G13 | Play India pricing: ₹149 and ₹1,199 are valid price points, Play shows them to buyers including tax, and the service fee on subscriptions. | Phase 11 product setup | Not started |

### 5.3 G1: full-screen interception feasibility

This gate comes first. Nothing in Phase 3 is implemented until it passes.

**Questions the spike must answer:**

1. Can a bound `AccessibilityService` start a full-screen aurora activity from the background on
   API 26, 29, 31, 34, 36 and 37? Check the background activity start rules for each level.
2. If not, does a `TYPE_ACCESSIBILITY_OVERLAY` window drawn by the service work as the Shield,
   with focus, TalkBack and the keyboard behaving correctly?
3. How long does it take from the protected app's window event to the visible Shield? Measure the
   P90 over 50 opens on a mid-range device.
4. Is the protected app's content hidden and impossible to use behind the Shield? Check that Back,
   Home, Recents and the notification shade never reveal it or let it take input.
5. Does interception work when the app opens from the launcher, Recents, a notification, a deep
   link, a widget, split-screen and picture-in-picture?
6. Does it work on a Pixel (stock Android), a Samsung (One UI) and one OEM with aggressive battery
   management (Xiaomi or OnePlus), including after a reboot and after aurora is force-stopped?

**Pass criteria:** one mechanism meets 1 or 2, a P90 under 300 ms, and 4–6 on all three devices.

**Output:** a short written result in the gate table and `ARCHITECTURE.md`, with the chosen
mechanism, the measured latency and any device exceptions.

**If G1 fails:** stop Phase 3. Evaluate these fallbacks in order and present them for a decision:

1. send the person home with `GLOBAL_ACTION_HOME`, then show the Shield inside aurora;
2. a slower, usage-stats-based check with a notification instead of a full-screen Shield.

Either fallback changes the product promise. Update the brand copy and `BUSINESS_MODEL.md` before
building it.

## 6. Phase overview

| Phase | Name | Depends on | Gates | Free limits wired | Size | Release |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Foundation | — | — | — | done | — |
| **2** | **Groundwork and entitlement core** | 1 | — | none (core only) | S | 1.0 |
| 3 | Shield core | 2 | **G1**, G2, G3, G4 | 5 protected items | L | 1.0 |
| 4 | Protection modes and Intent | 3 | (Phase 3 gates) | 3 Intent apps | M | 1.0 |
| 5 | Focus, enforced, with profiles | 3 | G5, G6 | 1 Focus profile | M | 1.0 |
| 6 | Schedules, daily limits and Lockdown | 4, 5 | — | 1 schedule, 3 limit rules, 120 Lockdown min/day | L | 1.0 |
| 7 | Insights: basic and advanced | 4, 5, 6 | G7 | advanced insights (plus feature) | M | 1.0 |
| 8 | Light: goals, streaks, challenges | 5, 7 | — | challenges (plus feature) | M | 1.0 |
| 9 | Hardening | 2–8 | — | — | M | 1.0 |
| 10 | Closed beta | 9 | G9 | — | M | 1.0 |
| 11 | aurora plus: billing and subscriptions | 2–10 | G8, G13 | plan source switches to Play | M | 1.0 |
| 12 | Release 1.0 | 2–11 | re-check all | — | M | 1.0 |
| 13 | Website filtering and supported content blocking | 12 | G10, G11, G12 | websites join the 5-item pool | XL | 1.1 |

Sizes are relative: S ≈ days, M ≈ 1–2 weeks, L ≈ 2–4 weeks, XL ≈ 4–8 weeks, for one developer.

**1.0 priorities (D13).** Reliable app protection, intentional use, Focus and Insights come first.
If the schedule slips, cut from Light (Phase 8) and advanced insights before cutting reliability
work in Phases 3, 5 or 9.

**Why billing comes after the beta.** Every feature and its Free limit is built and tested on Free
first. The closed beta (Phase 10) can then start the Play testing period that G9 may require while
billing is built in Phase 11. Billing is tested on internal testing and in a second closed round
inside Phase 12.

**1.1.** Phase 13 starts after 1.0 is live. It may ship in two releases: websites first, content
blocking after. Its gates may be checked earlier, but only if that doesn't delay 1.0.

**Versions.** `0.2.x` after Phases 3–5, `0.3.x` after Phases 6–8, `0.9.x` for the beta, `1.0.0` at
release, `1.1.0` with Phase 13.

---

## Phase 2: Groundwork and entitlement core

**Goal.** Make the next phases safe to build: automated checks, UI test infrastructure, one way to
ask for permissions, a Play Console app for policy gates, and the central entitlement module that
every later phase uses.

**Gates:** none. **Free limits wired:** none. This phase builds the core only.

**Status (2026-10-28, branch `feat/phase-2b-foundation`, not yet merged):**

- Done: task 1 without the nightly emulator job, tasks 2, 3, 4 (usage access, accessibility and
  notifications; exact alarms wait for Phase 5), task 5's `plan/` core plus the Lockdown budget,
  and task 8. GitHub Actions passes on the feature branch.
- Deferred: task 5's plan source and debug Plus switch, until a phase wires the first limit.
- Not done: task 6 (Play Console) needs the account owner.
- Acceptance still open: "CI is green on `main`" (needs the merge), "a failing unit test blocks a
  merge" (branch protection needs GitHub Pro or a public repository; until then, merge only on a
  green check), and the Play Console criterion.

### Tasks

1. **CI.** Add a GitHub Actions workflow that runs `./gradlew testDebugUnitTest lintDebug
   assembleDebug` on every push and pull request. Cache Gradle. Add a nightly emulator job
   (API 26, 30, 34 and 36) that runs instrumented tests once they exist.
2. **Compose UI tests.** Add `androidx.compose.ui:ui-test-junit4` and the instrumentation runner.
   Write the first smoke tests: onboarding completes, tabs switch, Focus starts and ends early.
3. **Screenshot tests.** Pick one tool (D8). Cover each existing screen in light, dark, 200% font
   scale and reduced motion. Check the baseline images into the repository.
4. **Permission flow helpers.** Add one reusable "explain, open Settings, re-check on resume"
   pattern. Generalise `rememberUsageAccess()` into small `remember…Access()` helpers for usage
   access, accessibility, notifications and exact alarms. Reuse `EmptyState`, `Notice` and
   `AuroraDialog`.
5. **Entitlement core.** Add `plan/Plan.kt` as described in `BUSINESS_MODEL.md` section 5:
   `Plan`, `Allowance`, `Feature`, `FreeLimits`, `limit()`, `canAdd()` and `has()`, all pure.
   Add a plan source to `AuroraApplication`: always `Free` in release builds, and in debug builds a
   DataStore-backed switch shown as a debug-only row in Settings. No feature uses it yet.
6. **Play Console.** Create the developer account and the app entry, so policy gates (G2, G9) can
   use a closed testing review. Create no products and publish no listing yet.
7. **Strings.** Keep copy inline. It moves to `strings.xml` in Phase 9, as `ARCHITECTURE.md` says.
8. **Lint.** Turn on `warningsAsErrors` for new code, with a baseline for current warnings if needed.

### Dependencies

- `androidx.compose.ui:ui-test-junit4`, `androidx.test:runner`, `androidx.test.ext:junit`, and one
  screenshot tool. No new runtime dependency for the entitlement core.
- GitHub Actions on `github.com/aagamjain463/aurora-android`.

### Acceptance criteria

- CI is green on `main`, and a failing unit test blocks a merge.
- At least 3 Compose UI tests and a screenshot set for all six existing screens pass.
- The permission helpers replace the ad-hoc checks without changing behaviour.
- `plan/Plan.kt` exists with the Free limits from `BUSINESS_MODEL.md`, and all limit numbers live
  only there.
- A release build has no way to switch to `Plus`. The debug switch is absent from release builds.
- The Phase 1 app looks and behaves exactly as before: screenshot baselines match the current UI.
- The Play Console app exists and can receive a closed testing build.

### Testing

- Existing `LogicTest` keeps passing.
- **Unit:** `canAdd()` and `limit()` for every allowance under both plans: below, at and above each
  limit, and `null` (unlimited) for `Plus`.
- **Build check:** a test or lint check confirms the debug switch is not compiled into release
  (for example, the source set lives in `src/debug/`).
- A person reviews screenshot diffs on every change to `ui/theme/` or `ui/components/`.

---

## Phase 3: Shield core

**Goal.** Opening a protected app shows the aurora Shield, reliably, and every surface tells the
truth about whether protection is on.

**Gates:** **G1 first, before any other Phase 3 work.** Then G2, G3 and G4. If G1 fails, follow the
fallback in section 5.3 and do not start these tasks.
**Free limit wired:** 5 protected items.

**Status (2026-10-29, `feat/phase-3-shield`, not merged).** The product owner approved building Phase 3
before G1 passes, with the Shield mechanism behind a replaceable `ShieldPresenter`. G1 and the Play
policy gate stay open. The build differs from the tasks below as follows (`SHIELD.md`):
- One baseline behaviour: a protected app is always shielded, with Close app as the only action. Modes
  (task 1 and task 6) and the Focus-session Friction wait move to Phases 4 and 5.
- Over the limit (task 7): the oldest 5 stay active and the rest are paused and shown, rather than
  all migrated apps staying active. A freed slot stays free until the person resumes the paused app
  they choose. The legacy set migrates in package-name order.
- Tasks 2–5, 7–9 are built. Task 10 (sideload instructions) waits for G4. `Blocked` (task 4) and
  `Paused` aren't detected yet.

### Tasks

1. **`protect/Policy.kt`.** Add `Target`, `PolicyState`, `Decision` and `decide()` (section 3.1).
   This phase supports two modes: **Flexible** and **Only during focus**. The existing Focus session
   shields items behind a Friction wait.
2. **`ShieldService`.** An `AccessibilityService` that listens to `TYPE_WINDOW_STATE_CHANGED` only,
   with `canRetrieveWindowContent=false` and `isAccessibilityTool=false`.
   - Ignore aurora itself, the launcher, the system UI, the keyboard, and the dialer during calls.
     Never shield emergency or phone flows.
   - Treat repeated events from the same opening as one opening, but show the Shield again on every
     protected event. An app can start its own activity over the Shield during its background-start
     grace period (seen with Chrome in the G1 spike).
   - Read `PolicyState` from an in-memory snapshot kept current by collecting the repositories.
     Do not read DataStore on every event.
3. **The Shield surface.** Use the mechanism that G1 chose. It shows the app icon and label, the
   reason ("You're in a focus session until 14:30"), "Close app" (Primary: go home) and the mode's
   way through as a Quiet action. Back goes home, not into the protected app.
4. **`ProtectionStatus`.** A flow with the values `Off`, `NeedsPermission`, `NotRunning` (enabled but
   not connected, for example just after a reboot), `Blocked` (Android 17+ Advanced Protection), `Active`
   and `Paused`,
   from the service state (using the detection method G3 confirmed) plus "at least one item
   protected". It replaces:
   - the "Preview" `Notice` on Protect;
   - "Saved · blocking arrives in an update" on Today;
   - the Focus setup caption "Your apps stay available during a session…";
   - the "App blocking" row in Settings, which becomes a live status row.
5. **Accessibility disclosure.** A prominent in-app disclosure before sending the person to
   Accessibility settings. It states what is read (the name of the app in front), that nothing
   leaves the phone, and how to turn it off. G2 decides the exact wording. The same text goes into
   the Play declaration.
6. **Per-item mode.** Replace the checkbox on Protect with a mode per app. Store it in DataStore as
   a map from package to mode. Migrate the existing `protectedApps` set to "Only during focus",
   which matches what Phase 1 promised, with a one-time read of the old key.
7. **5-item limit.** `ProtectViewModel` checks `canAdd(plan, ProtectedItems, count)` before
   protecting another app. At the limit, show the calm message from `BUSINESS_MODEL.md` section 4,
   with no mention of plus. If the migrated set has more than 5 apps, keep them all and block only
   new additions until the count drops below 5.
8. **Uninstalled apps.** Prune package names that no longer resolve, on catalog load. This frees slots.
9. **Onboarding.** Update the "how it works" step to describe what Shield really does. Keep the
   permission optional during onboarding, and ask in context on Protect.
10. **Tester instructions.** Document how to enable the service on sideloaded builds, based on G4.

### Dependencies

- Phase 2: permission helpers, UI test infrastructure, entitlement core.
- Passed gates G1–G4.

### Android limitations

- Shield works only while the accessibility service is on. The person, the OEM or Android itself
  can turn it off (G3).
- The service sees window changes, not intentions. Some OEM launchers and split-screen setups may
  report events late. G1 measures this.
- Force-stopping aurora turns the accessibility service off. Only the person can turn it back on.
- After a reboot, the service connects some time after boot. It took about 29 s on the emulator. Apps
  opened before that are not shielded.

### Acceptance criteria

- With the service enabled, opening a protected app from every entry point listed in G1 shows the
  Shield. P90 latency is under 300 ms on the reference mid-range device.
- "Close app" lands on the home screen. Back from the Shield never reveals the protected app.
- When the service is disabled, every surface shows `NeedsPermission` within one resume, and
  nothing claims protection.
- No Shield appears for aurora, the launcher, system UI, the keyboard, or phone and emergency flows.
- The service holds no work when no rule is active: no wake locks and no polling.
- On Free, a 6th item can't be added, and the message offers removing an item.
- Copy passes the voice rules in the brand guidelines.

### Testing

- **Unit:** `decide()` truth table: protected or not, mode, session or not, session just ended, app
  uninstalled, aurora itself, launcher.
- **Unit:** every `decide()` case gives the same result under `Free` and `Plus`.
- **Unit:** migration from `protectedApps`, including a migrated set larger than 5.
- **Compose UI:** the limit message at 5 items. With the debug switch on `Plus`, a 6th item is added.
- **Instrumented:** a UiAutomator test that protects a stock emulator app (for example Clock), opens
  it and asserts that the Shield is visible. Enable the service in test setup with
  `adb shell settings put secure enabled_accessibility_services …`. Create `UiAutomation` with
  `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`. Otherwise it turns the service off, and so does
  `uiautomator dump`.
- **Manual matrix:** the three G1 devices. Check reboot, force-stop of aurora, low-memory kill and
  split-screen.

---

## Phase 4: Protection modes and Intent

**Goal.** Every protected item gets the everyday behaviour the person chooses: Flexible, Friction,
Cooldown or Intent.

**Gates:** none of its own. It relies on the Shield surface that passed G1.
**Free limit wired:** 3 Intent apps.

### Tasks

1. **Friction.** The Shield's "Continue" appears after a wait: one 4.2 s breath by default, or a
   length the person chooses. With reduced motion, a static glow and a plain countdown label.
2. **Cooldown.** Each opening allows a set time (for example 10 minutes). Then the app closes and
   stays shielded for a cooldown (for example 30 minutes). The Shield shows when the app opens
   again. Opening during the cooldown needs a Friction wait (`BUSINESS_MODEL.md` Q9). Store the
   cooldown timers in DataStore.
3. **Intent flow** on the Shield surface:
   1. A breath, as in Friction.
   2. A question: "What are you opening Instagram for?" with quick choices ("A message",
      "Something specific", "Just browsing") and an optional short text.
   3. A choice of time: 5, 10 or 15 minutes, using `ChoicePills`.
   4. Two actions: "Open for 5 min" (Secondary) and "Not now" (Primary). The intentional choice is
      the Primary action.
4. **Grants.** Opening through Intent creates a grant `{ package, until }` in DataStore. `decide()`
   returns `Allow` while a grant is open. When it expires and the app is still in front, Intent
   appears again with "Your 5 minutes are up".
5. **3 Intent apps.** The mode picker checks `canAdd(plan, IntentApps, count)` before switching an
   app to Intent. At the limit, Intent shows as unavailable with the reason. The other modes stay
   available for every item.
6. **Events.** Record each shield and Intent outcome (continued or not, reason, minutes) for
   Insights and Light. They are stored in Room from Phase 5. If Phase 4 ships first, keep outcomes
   in memory only and note it in the code.
7. **Retire** the "Pause before opening" `ComingSoonRow`.

### Android limitations

- A timer set by aurora can't close another app by itself. When a grant or Cooldown opening ends,
  the next foreground event shows the Shield. If the person stays in the app with no new window
  events, the service re-checks on its next event, or aurora's own alarm wakes it (decided with G6).

### Acceptance criteria

- Each mode behaves as defined in `BUSINESS_MODEL.md` 3.2, and the same way on both plans.
- An Intent app never opens without the breath, unless an open grant exists.
- Grants and cooldowns survive process death and are honoured to the minute.
- "Not now" is the visually primary action.
- On Free, a 4th Intent app can't be set, and the other modes still can.
- Reduced motion shows no movement at all in Friction or Intent.
- TalkBack reads the question, the choices and the time, and the breath doesn't block focus.

### Testing

- **Unit:** Friction wait, Cooldown timing at the boundaries, grant expiry at `until - 1`, `until`
  and `until + 1`, overlapping grants, clock changes.
- **Unit:** the Intent limit under both plans.
- **Compose UI:** each mode's Shield in both themes, with reduced motion on and off.
- **Instrumented:** open, wait for expiry (shortened with a debug-only duration), see the Shield again.

---

## Phase 5: Focus, enforced, with profiles

**Goal.** A Focus session protects the person even when aurora is closed, ends with a gentle
notification, is remembered, and can be saved as a profile.

**Gates:** G5 blocks the countdown notification. G6 blocks the end-alarm design. Room, enforcement
and profiles can start without them.
**Free limit wired:** 1 Focus profile. Basic sessions stay unlimited.

### Tasks

1. **Room.** Add the database with `focus_session` (start, planned duration, actual end, intention,
   profile, outcome), `shield_event` (time, target, kind, outcome) and `focus_profile` (name,
   default length, items, Lockdown flag) tables. Export the schema to `app/schemas/` and test
   migrations from version 1 onward.
2. **Session lifecycle.** On "Begin focus":
   - write the session (existing DataStore keys) and a Room row;
   - post an ongoing countdown notification (mechanism from G5);
   - schedule the end alarm (mechanism from G6).

   On completion or early end: update the Room row, cancel the alarm and notification, and post
   "Time well spent" on completion.
3. **Profiles.** The default profile covers all protected items and the person's default length.
   Free can edit it but can't add a second (`canAdd(plan, FocusProfiles, count)`). The Lockdown flag
   on a profile is built in Phase 6.
4. **Permissions.** Ask for `POST_NOTIFICATIONS` (API 33+) in context, at the first session start.
   If it is denied, the session still works and the setup screen says the end won't be announced.
   Ask for exact alarms only if G6 shows aurora qualifies and the person turns on "exact end time".
5. **Boot.** A `RECEIVE_BOOT_COMPLETED` receiver re-posts the notification and re-arms the alarm for a
   running session, or completes it if it ended while the phone was off.
6. **Enforcement copy.** The Running screen, the Today hero and the notification state which items
   are shielded, based on `ProtectionStatus`.
7. **Completion.** The Complete phase reads from Room, so the completion moment appears exactly once,
   even if aurora was closed when the session ended.

### Dependencies

- Phase 3. Room runtime and the KSP plugin (new dependencies).

### Android limitations

- Without exact-alarm access, Android may deliver the end alert late in Doze (G6 measures how late).
- Notifications need the person's permission on Android 13+.

### Acceptance criteria

- A session left with aurora swiped away still shields its items and shows a live countdown in the
  notification shade.
- The end is announced on time with exact alarms, or within the delay G6 measured without them. The
  completion moment is correct when aurora next opens either way.
- Reboot during a session keeps the session and the countdown, or completes it correctly.
- Denying notifications never breaks a session.
- Each session is in Room with the correct outcome.
- On Free, any number of sessions can run (one at a time), and a second profile can't be added.

### Testing

- **Unit:** session state machine with an injectable clock: start, early end, completion, completion
  while the device is off, a double start.
- **Unit:** the profile limit under both plans.
- **Room:** `MigrationTestHelper` for every schema version.
- **Instrumented:** start a 1-minute debug session, kill the process, verify the notification and
  alarm (`adb shell dumpsys alarm`), then the completion.
- **Manual:** Doze (`adb shell dumpsys deviceidle force-idle`) and App Standby buckets.

---

## Phase 6: Schedules, daily limits and Lockdown

**Goal.** Protection that holds without remembering to start a session, and a Lockdown that holds
when it matters. This retires the remaining "On the way" rows.

**Gates:** none of its own.
**Free limits wired:** 1 recurring schedule, 3 daily app-limit rules, 120 Lockdown minutes per local day.

### Tasks

1. **Recurring schedules.** A schedule has a name, days of the week, a start and end time (it may
   cross midnight) and a Focus profile. Store schedules in Room. `decide()` evaluates them against
   local time at each event. Free can create 1 (`canAdd(plan, Schedules, count)`).
2. **Daily app-limit rules.** A daily minute budget for one app or a group, reset at local midnight.
   Usage comes from `foregroundTime()` with Usage access. Cache it per package and update it from
   service events, so each app switch doesn't query a whole day of events. A spent budget shields
   with Friction until midnight (`BUSINESS_MODEL.md` Q3). Free can create 3 rules. Without Usage
   access, the limits screen explains why limits are unavailable and offers the permission.
3. **Lockdown.** A commitment `{ until, scope }` in DataStore. It starts from a profile with the
   Lockdown flag, from a schedule, or from "Lock now". While it is active:
   - `decide()` returns `Shield(wayThrough = None)` for items in scope;
   - "End session" and every way through are hidden;
   - the scope's items, profiles, schedules and limits can't be edited or deleted in aurora;
   - Settings and the Lockdown screen say plainly what Lockdown can't stop: uninstalling aurora,
     turning off the accessibility service, safe mode and `adb`.

   D5 decides whether to also shield Android's own settings screens. The default is no.
4. **Lockdown ledger.** A DataStore record of the local date and minutes used, following the rules in
   `BUSINESS_MODEL.md` 3.4: reserve at start, refund early ends outside aurora's control, reset at
   local midnight, and for schedules run Lockdown for the minutes left and then continue as a basic
   session. The length picker shows the minutes left today on Free.
5. **Protect tab redesign.** Sections "Items", "Schedules", "Limits" and "Lockdown", each a
   `RowGroup` with live status. A "Websites" `ComingSoonRow` may stay until 1.1. Build the schedule
   editor from existing components: `ChoicePills` for days and a Material 3 time picker themed
   through `toMaterial()`.
6. **Today.** Show the current or next schedule ("Work until 17:00") and any active Lockdown in the
   status pill.
7. **Optional notifications.** A quiet notice when a schedule starts. Off by default.

### Android limitations

- Lockdown can't stop uninstalling, disabling the service, safe mode or `adb`, on either plan.
- Daily limits and the Lockdown budget use the device clock. Changing the date or time can reset
  them. This is accepted for 1.0 (`BUSINESS_MODEL.md` section 7).
- Limits need Usage access.

### Acceptance criteria

- A schedule from 22:00 to 07:00 shields at 23:59 and 00:01 on the right days, across DST and
  time-zone changes.
- A limit shields within one app switch after the budget runs out, and resets at local midnight.
- During Lockdown, no path inside aurora opens an item in scope or ends Lockdown early, and the UI
  never implies that uninstalling or disabling the service is blocked.
- On Free: a Lockdown can't be longer than the minutes left today; the 121st minute of a day can't
  be reserved; a Lockdown crossing midnight finishes as promised; a schedule longer than the budget
  shows its "then Friction" notice and behaves that way.
- On `Plus` (debug switch), all of these limits are absent, and protection behaves identically.
- Overlapping rules resolve in the documented order (section 3.1).

### Testing

- **Unit:** schedule evaluation with fixed `ZoneId` values: midnight crossing, DST spring-forward and
  fall-back, a zone change during a schedule.
- **Unit:** limit accounting against recorded `UsageSignal` fixtures, in the `LogicTest` style.
- **Unit:** Lockdown ledger: reserve, refund, midnight reset, crossing midnight, time-zone change,
  partial schedule coverage.
- **Unit:** rule precedence in `decide()`, and identical decisions under both plans.
- **Compose UI:** schedule editor, limit editor, the Lockdown picker with minutes left, the locked state.
- **Instrumented:** a schedule that starts one minute from now shields the test app.

---

## Phase 7: Insights: basic and advanced

**Goal.** Basic insights for everyone: today and the last 7 days. Advanced insights for plus:
longer history, trends and patterns.

**Gates:** G7 blocks this phase's history work. The Focus and shield summaries come from aurora's
own Room data and can start without it.
**Free limit wired:** advanced insights are a plus `Feature`.

### Tasks

1. **Basic insights (Free).**
   - Today, as now.
   - The last 7 days of screen time, computed with `foregroundTime()` per day window from the
     history G7 confirmed. If a day isn't available, say so instead of showing zero.
   - Focus minutes per day and completed sessions, from Room.
   - "You chose not to open Instagram 6 times this week", from Room. Phrase it as the person's
     choice, never as a count of failures.
2. **Daily totals (D14).** If D14 says yes, store one row per app per day (package, date, minutes) in
   Room, for every person, so plus has history from the day it is bought. Update the Settings
   Privacy dialog, `ARCHITECTURE.md` and onboarding, which currently say screen time is never stored.
3. **Advanced insights (plus).** 30-day and 90-day views, a trend for each app, time-of-day patterns
   and comparisons with the person's own past weeks. Shown when `has(plan, AdvancedInsights)`.
   Before Phase 11, Free sees no mention of them.
4. **Per-app detail.** Tap an app to see its week (and longer on plus) and its rules. "Add a limit"
   links to Phase 6.
5. **Charts.** Simple bars drawn with Compose `Canvas` in the existing `UsageBar` style. No chart
   library. The brand avoids dense charts.
6. **Data controls.** "Delete history" in Settings clears every history table.

### Android limitations

- Android keeps limited usage history (G7). History before aurora started storing daily totals can't
  be recovered.
- aurora's totals can differ slightly from Digital Wellbeing. The existing footnote stays.

### Acceptance criteria

- The 7-day view matches the sum of per-day computations for the same windows.
- Days without available data are labelled, not shown as zero.
- Advanced views appear only under `Plus`, and the basic views are identical on both plans.
- No copy compares the person to others or uses alarming language.
- "Delete history" empties every history table, and Insights updates immediately.

### Testing

- **Unit:** day-window splitting across midnight and DST, using `foregroundTime()` fixtures.
- **Unit:** aggregation queries with an in-memory Room database.
- **Screenshot:** an empty, partial and full week, and an advanced 30-day view, in both themes and at
  200% font.

---

## Phase 8: Light: goals, streaks and challenges

**Goal.** Make intentional use feel rewarding in aurora's own language, light rising out of the
dark, with no pressure and no loss.

**Gates:** none.
**Free limit wired:** challenges are a plus `Feature`. Goals and streaks are Free.

### Tasks

1. **Points of light.** Each completed Focus session and each "Not now" adds a point of light.
   Sessions ended early still add light for the minutes focused.
2. **Your sky.** A section in Insights draws the week as a horizon. Points of light sit above it, and
   the `HorizonGlow` intensity rises with the week's light. It is static under reduced motion.
3. **Goals (Free).** Personal targets such as "Focus 3 times this week" or "Under 2 hours on social
   apps a day", with progress shown as a row of dots.
4. **Gentle streaks (Free).** Consecutive days with a goal met, following the rules in
   `BUSINESS_MODEL.md` 3.7 and brand guardrail 4.
5. **First lights.** Quiet milestones, shown once in the completion moment: the first session, the
   first hour focused, the first week with a goal met. They use warm Dawn colours.
6. **Challenges (plus).** Guided multi-day programs such as "A week of calm mornings", built from
   schedules, goals and Lockdown. Shown when `has(plan, Challenges)`. A challenge can use Lockdown
   only within the person's plan.
7. **Opt-out.** A "Show light and milestones" toggle in Settings, on by default (D9).
8. **Pure rules.** `light/` holds the rules as pure functions over Room events. Light, goals and
   streaks are computed, not stored, so a rule change never corrupts history.

### Acceptance criteria

- No copy, colour or animation implies loss, failure or ranking. A brand review signs this off.
- A missed day never erases a streak's best run or total days, and no notification mentions streaks.
- Light totals and streaks equal a recomputation from raw events.
- Challenges appear only under `Plus`.
- Everything works without motion and reads well with TalkBack ("12 points of light this week").

### Testing

- **Unit:** light, goal and streak rules from event fixtures, including early-ended sessions, rest
  days, a missed day, time-zone changes.
- **Unit:** a challenge's progress from fixtures.
- **Screenshot:** empty, typical and full sky; a streak after a missed day; a challenge in progress;
  in both themes and with reduced motion.

---

## Phase 9: Hardening

**Goal.** aurora feels premium on every device: accessible, fast, translated and stable on
Android 17.

**Gates:** none of its own. It re-runs the G1 and G3 device checks on API 37.

### Tasks

1. **Accessibility audit.** TalkBack, Switch Access, 200% font scale and display size, and colour
   contrast on every new screen against the brand guidelines (WCAG AA).
2. **Localisation.** Move copy to `strings.xml` with plurals. Start with English, and add languages
   after D11. Test pseudo-locales (`en-XA`, `ar-XB`) for length and right-to-left layout. Format
   rupee prices with the locale's currency formatting.
3. **Performance.** Add a baseline profile and a Macrobenchmark module for cold start and Shield
   latency. If profiles show it matters, cache `AppCatalog.launchableApps()` with a package-change
   receiver, which resolves the `ponytail:` note in `AppCatalog.kt`.
4. **Battery.** Measure a full day with Shield, schedules, limits and Lockdown on, using Battery
   Historian.
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

## Phase 10: Closed beta

**Goal.** Put the complete free product in real hands, prove reliability across devices, and get
the Play policy declarations reviewed before billing is added.

**Gates:** G9 sets the length and size of the test.

### Tasks

1. **Signing.** Create the upload key, enrol in Play App Signing, and read the release signing config
   from environment variables. Never commit keys. `.gitignore` already excludes `*.jks` and
   `*.keystore`.
2. **R8.** Check the keep rules for Room and the accessibility service. Run the release build through
   the full test matrix.
3. **Declarations.** Submit the Accessibility API declaration (form, in-app disclosure, video), the
   Usage access justification, and a draft Data safety form, so Play reviews them on the closed track.
4. **Privacy policy page.** A hosted page that follows the brand guidelines. Play requires a URL.
5. **Closed testing track.** Recruit testers as G9 requires. Give them a short, calm feedback channel
   outside the app (aurora has no analytics). Use Play Console vitals for crashes and ANRs.
6. **Business checks.** Watch where testers hit Free limits and what they say about them. Report this
   for the D2 review. The limits themselves change only by a decision recorded in `BUSINESS_MODEL.md`.

### Acceptance criteria

- The closed track runs for the period G9 requires, with no open crash or ANR above the Phase 12
  thresholds.
- Play has reviewed the accessibility declaration with no unresolved issue.
- Shield reliability holds on at least 10 different device models.

### Testing

- Full manual matrix on testers' devices, guided by a written test script.
- Weekly regression of the nightly emulator suite on the beta build.

---

## Phase 11: aurora plus: billing and subscriptions

**Goal.** Make aurora plus purchasable and switch the plan source from the constant `Free` to Play
Billing, with the honesty the rest of the product has.

**Gates:** G8 blocks the phase. G13 blocks product setup.

### Tasks

1. **Products.** Create two subscriptions in Play Console: monthly at ₹149 and yearly at ₹1,199 in
   India. Set other markets and any trial from `BUSINESS_MODEL.md` (Q5, Q6) before launch.
2. **Billing.** Add the Google Play Billing Library behind one small class in `billing/`. It
   connects, queries products, launches the purchase flow, acknowledges purchases within the required
   window, and feeds the plan source from Phase 2. Nothing else in the app talks to Billing.
3. **Entitlement without a backend.** Trust Play Billing's purchase state, cached in DataStore with a
   "last confirmed" time. Recheck on start and on resume, and keep plus for a grace period while
   offline. Record the trade-off: a modified app can bypass the check (`BUSINESS_MODEL.md` section 7).
4. **Downgrade.** Implement `BUSINESS_MODEL.md` section 5.4: running sessions and Lockdowns finish,
   extras pause (the person chooses which stay active, oldest first by default), nothing is deleted,
   and renewal restores everything.
5. **Paywall.** One calm screen built from `AuroraScreen`, `RowGroup` and one Primary button. It lists
   what plus adds (never cloud sync until it exists), the real price from Play, the renewal terms,
   "Restore purchases" and a link to manage the subscription in Play. It opens from limit messages
   (as a Quiet link) and from Settings. It never opens during Focus, a shield moment or Lockdown.
6. **Limit messages.** Add the Quiet "aurora plus" link to every limit message built in Phases 3–8.
7. **Settings.** An "aurora plus" row with status, renewal date and "Manage subscription".
8. **Privacy claims.** Apply the G8 result. If Billing adds network permissions, rewrite every privacy
   claim (README, the Settings Privacy dialog, onboarding, the store listing and Data safety).

### Dependencies

- Play Console products and license testers. Phases 2–10.

### Android limitations

- Entitlement is checked on the phone, with no server verification.
- Play Billing needs the Play Store app. Devices without Play can only use Free.

### Acceptance criteria

- Purchase, restore, cancel, grace period, account hold and expiry all leave the app in the correct
  plan, with no lost rules.
- Every purchase is acknowledged in time.
- Downgrade follows `BUSINESS_MODEL.md` 5.4 exactly, including during a running Lockdown.
- Protection decisions are identical before and after a purchase.
- Turning protection off, ending a non-Lockdown session and deleting data are never paywalled.
- The paywall passes the brand review: no urgency tricks, the real price is shown, and nothing
  unbuilt is advertised.

### Testing

- **Unit:** entitlement state machine (unknown, active, grace, lapsed, restored) with a fake billing
  client, and the downgrade logic with more items, rules, schedules and profiles than Free allows.
- **Play testing:** license-tester accounts, test cards and accelerated renewal periods on internal
  testing.
- **Manual:** airplane mode during and after a purchase, a reinstall followed by restore, and a
  subscription lapsing during a Lockdown.

---

## Phase 12: Release 1.0

**Goal.** Ship 1.0 on Google Play with every declaration approved and a staged rollout.

**Gates:** every passed gate is re-checked against the current Android release and Play policy
before submission.

### Tasks

1. **Second closed round.** Run the billing build on the closed track until purchase and downgrade
   flows are proven.
2. **Final declarations.** Accessibility API, Usage access, exact alarms (only if G6 showed aurora
   qualifies), Data safety based on G8, subscription disclosures.
3. **Store listing.** Screenshots from the final screenshot-test set, in both themes. Copy in the
   brand voice: lowercase "aurora", no overclaiming, what Lockdown can and can't do, the Free and plus
   comparison from `BUSINESS_MODEL.md`, and no mention of websites, content blocking or cloud sync.
4. **Rollout.** Production with a staged rollout: 5%, 20%, 50%, 100%.
5. **Docs.** Update `README.md`, `ARCHITECTURE.md` (Room, services, entitlements, billing),
   `BUSINESS_MODEL.md` and this roadmap.

### Acceptance criteria

- All Play declarations approved, and the listing live.
- Crash-free users at or above 99.5% and ANR rate under 0.47% across the staged rollout.
- README, ARCHITECTURE, BUSINESS_MODEL and in-app copy describe the shipped product exactly.

---

## Phase 13: Website filtering and supported content blocking (1.1)

**Goal.** Shield chosen websites in supported browsers, and specific distracting surfaces inside
supported apps, with no VPN and no network access (D4). It may ship as two releases.

**Gates:** G10 blocks websites. G11 blocks each browser on its own: a browser that fails isn't offered.
G12 blocks each content surface on its own.
**Free limit wired:** websites join the 5-item pool, so apps and websites share it.

### Tasks: websites

1. **Service upgrade.** Set `canRetrieveWindowContent=true` and listen to `TYPE_WINDOW_CONTENT_CHANGED`
   **only for the packages of supported browsers**, using a package filter set at runtime. Update the
   disclosure text and the Play declaration.
2. **Browser readers.** One small reader per browser that passed G11. It finds the address-bar node and
   returns the host. Candidates: Chrome, Samsung Internet, Firefox, Edge, Brave and DuckDuckGo (D6).
   Keep the view IDs in one table in code, so an update to a browser is a one-line fix.
3. **Domain matching.** `host == domain || host.endsWith(".$domain")`, after lower-casing and removing
   `www.`. No public-suffix list is needed for this rule.
4. **Action.** On a match, show the Shield with the domain ("example.com is on your list") and the
   item's mode. Then use `GLOBAL_ACTION_BACK`, or go home if the page can't be left.
5. **Protect UI.** A "Websites" section with add, remove and validation (strip the scheme and path,
   reject IP addresses and empty hosts). Adding a website checks `canAdd(plan, ProtectedItems, count)`
   with apps and websites counted together.
6. **Honesty.** A "Works in" list shows the supported browsers installed on this phone. An installed but
   unsupported browser is labelled "Not supported yet". In-app browsers, custom tabs and WebViews are
   named as not covered.
7. **VPN.** Deferred (D4). A local VPN filter needs the `INTERNET` permission, conflicts with other VPNs
   and with Private DNS, and changes aurora's privacy promise.

### Tasks: supported content blocking

8. **Scope (D7).** Start with the candidates that pass G12, for example YouTube Shorts and Instagram
   Reels. Each entry names the app, the surface, the tested version range and a node signature.
9. **Rules file.** A versioned asset (`assets/content_rules.json`). aurora has no network access, so
   rules update only with app updates.
10. **Matcher.** A pure function from a node-tree snapshot to the matched surface. The service listens
    to content changes only for packages with an enabled rule.
11. **Action.** `GLOBAL_ACTION_BACK`, then a calm Shield moment ("Shorts are paused. The rest of YouTube
    is yours."). After two quick back attempts, go home, so the person is never trapped in a loop.
12. **Health check.** If the installed app version is newer than the tested range, the UI says "May not
    work with this version of YouTube".
13. **Plan.** Content surfaces are a plus feature (D2). Add `Feature.ContentBlocking` to `plan/Plan.kt`
    in this phase, not before.

### Android limitations

- Only supported browsers and tested app versions are covered. Browser and app updates can break
  detection until aurora ships updated readers or rules.
- Reading screen content needs a broader accessibility capability and declaration than Phase 3.

### Acceptance criteria

- A blocked domain is shielded in every supported browser, in normal and private tabs, whether it is
  typed, opened from a link or restored from a tab.
- Subdomains match. Similar names don't: blocking `x.com` doesn't block `box.com`.
- On Free, 5 items in total across apps and websites.
- Every offered content surface is blocked on its tested versions, and the rest of the app keeps working.
- URLs, page titles and screen text are never stored or logged.
- Battery cost stays under 1% per day, measured as in Phase 9.

### Testing

- **Unit:** domain normalisation and matching, including IDNs, trailing dots, ports, `www.` and uppercase.
- **Unit:** readers and matchers against `uiautomator dump` fixtures in `app/src/test/resources/`,
  positive and negative.
- **Unit:** the shared item pool under both plans.
- **Instrumented:** Chrome on the emulator opens a blocked domain, and the Shield appears.
- **Release check:** re-capture fixtures for each browser's and app's current stable version before
  every release.

---

## 7. Testing strategy (all phases)

| Layer | Tool | What it covers | When it runs |
| --- | --- | --- | --- |
| Pure logic | JUnit 4 (existing) | `decide()`, modes, grants, schedules, limits, Lockdown ledger, entitlements, light rules; matchers in 1.1 | Every push |
| Plan parity | JUnit 4 | Every `decide()` case gives the same result under `Free` and `Plus` | Every push |
| Limits | JUnit 4 and Compose UI | Each allowance below, at and above its limit, on both plans | Every push |
| Persistence | Room in-memory and `MigrationTestHelper` | Queries, migrations | Every push |
| UI | Compose UI tests | Onboarding, Focus, modes, Intent, editors, limit messages, paywall | Every push or nightly |
| Visual | Screenshot tests (D8) | Every screen, both themes, 200% font, reduced motion | Every push |
| System | UiAutomator on emulators | Service and Shield, alarms, boot; browsers in 1.1 | Nightly, API 26 / 30 / 34 / 36 / 37 |
| Fixtures (1.1) | `uiautomator dump` XML | Browser readers, content surfaces | Every push, re-captured before each release |
| Performance | Macrobenchmark | Cold start, Shield latency | Weekly and before release |
| Billing | Fake billing client, Play license testers | Entitlement states, downgrade | Every push (fake), each release (Play) |
| Gates | Spikes on `spike/<gate-id>` branches | Platform and policy assumptions | Before the dependent phase, and again before release |
| Manual | Device matrix | OEM behaviour, real apps, billing | Before each milestone build |

Rules:

- Every rule that can go wrong silently goes through a pure function with a unit test, as
  `foregroundTime()` does today.
- Inject the clock and the time zone into all time logic, including the Lockdown ledger. No test
  sleeps for real minutes.
- Test plus behaviour through the debug plan switch until Phase 11.
- Fixtures never contain personal data. Capture them with test accounts.

## 8. Out of scope

- Cloud sync. It is a future aurora plus feature that needs its own decision on accounts, a backend,
  encryption and the `INTERNET` permission (`BUSINESS_MODEL.md` section 6). It isn't advertised
  until it exists.
- The aurora launcher, device-owner and device-admin features.
- VPN-based filtering (D4).
- iOS, desktop and web apps beyond the privacy-policy page.
- Parental control or monitoring of another person. aurora is for people managing their own attention.

## 9. Decisions

### Settled

| ID | Decision | Date |
| --- | --- | --- |
| D2 | The business model in `BUSINESS_MODEL.md`: aurora Free with 5 protected items, 120 Lockdown minutes a day, unlimited basic Focus, Flexible, Friction and Cooldown modes, 3 Intent apps, 3 limit rules, 1 schedule, 1 profile, and basic insights, goals and streaks. aurora plus removes those limits and adds advanced insights and challenges. Both plans get the same protection strength. | 2026-10-28 |
| D4 | Website filtering starts with accessibility-based reading of supported browsers, subject to feasibility (G11) and Play policy (G10). VPN integration is deferred. | 2026-10-28 |
| D10 | India prices: ₹149 a month, ₹1,199 a year. Trial and other markets are open (`BUSINESS_MODEL.md` Q5, Q6). | 2026-10-28 |
| D13 | 1.0 launches without website filtering and partial-app content blocking. 1.0 prioritises reliable app protection, intentional use, Focus and Insights. | 2026-10-28 |
| D3 | Superseded by D2: protected items are always on in their chosen mode, and "Only during focus" covers the session-only case. | 2026-10-28 |

### Open

Each open decision has a recommended default. A phase can start with the default. The decision is
confirmed before that phase's acceptance. Business questions Q1–Q9 are in `BUSINESS_MODEL.md` section 8.

| ID | Decision | Recommended default | Needed by |
| --- | --- | --- | --- |
| D1 | Keep the "Protect" tab and call the moment "the shield", or rename the tab "Shield"? | Keep "Protect" (matches the icon). Use "shield" in copy. | Phase 3 |
| D5 | Should Lockdown shield Android's own settings for aurora (accessibility, app info)? | No. It reads as hostile and raises policy risk. Say honestly what Lockdown can't stop. | Phase 6 |
| D6 | Candidate browsers for G11 | Chrome, Samsung Internet, Firefox, Edge, Brave, DuckDuckGo | Phase 13 |
| D7 | Candidate content surfaces for G12 | YouTube Shorts, Instagram Reels | Phase 13 |
| D8 | Screenshot test tool | Google's Compose Preview Screenshot Testing plugin, or Roborazzi if the plugin isn't stable enough | Phase 2 |
| D9 | Light on by default? | On, with an opt-out | Phase 8 |
| D11 | First languages after English | Decide from the beta audience | Phase 9 |
| D12 | Backup and export | Opt-in local JSON export. Still no cloud backup. | Phase 9 |
| D14 | Store daily per-app usage totals on the phone, so advanced insights can show more than Android keeps? | Yes, for everyone, local only, deletable. It changes the "never stored" privacy copy. | Phase 7 |
