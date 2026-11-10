> **Visual identity superseded (2026-11-07).** Brand, colour, type and art guidance in this document describes the retired First Light identity. The active spec is `brand/Aurora_Brand_Guidelines.md`; where they disagree, the guidelines win.

# Aurora: the 10/10 plan

**Review date:** 31 October 2026  
**Reviewed build:** `1.0.0` (`versionCode 1`), commit `1084667`  
**Verdict:** **8.2 / 10** — exceptional craft, not yet an exceptional *first sixty seconds*, and not yet proven where it matters most: on real phones, with real accessibility tools, at real speed.

Aurora is already better designed than most wellbeing apps and more honest than almost all of them. The gap to 10/10 is not a missing feature list. It is the last 1.8 points of **proof, simplicity and speed**.

---

## 1. The scorecard

| Dimension | Score | Why it earns this | What would make it a 10 |
|---|---:|---|---|
| Brand and visual craft | **9.3** | One coherent world: First Light, Newsreader/Geist, custom icons, glass surfaces, considered motion, a real logo system. It does not look like a template. | Remove the last palette and asset inconsistencies; make every state, including error and empty, feel authored. |
| UX and information architecture | **7.2** | Four clear pillars. But Home can present several filled primary actions, onboarding is eight steps, and the product's internal vocabulary leaks into the UI. | One obvious action per screen; one vocabulary; first value in under a minute. |
| Core functionality | **8.4** | Four protection modes, schedules, daily limits, Intent, Focus, goals, XP, widgets, tile, ongoing notification. The policy engine is pure Kotlin and heavily tested. | Close the outcome loop: intention → session → reclaimed time → reflection → next session. Make coverage limits visible and understandable. |
| Enforcement reliability | **7.0** | The accessibility overlay is a smart, hard-won answer to OEM background restrictions. Repeat handling, confirm retries and stale-Shield logic are thoughtful. | Prove Shield latency and reliability on Pixel, Samsung, Xiaomi and OnePlus. Target p95 under 300 ms, not a 1.2 s emulator median. |
| Accessibility | **6.8** | Large text, reduced motion, screen-reader skip on the opening, semantic headings in places, 48 dp targets in the system. | A complete TalkBack script, AA contrast proof on glass, 200% font proof, switch access, and no motion that cannot be stopped. |
| Performance | **6.3** | A baseline profile exists. The UI is Compose and mostly lightweight. | Cold start under 2.5 s to interactive; no blank frame; a branded opening that respects the return; macrobenchmarks in CI. |
| Trust and privacy | **9.6** | Merged release manifest has **no `INTERNET` and no `ACCESS_NETWORK_STATE`**. No account, analytics or ads. Backup disabled. Numbers say “unavailable” rather than inventing a value. | Make the proof visible inside the app, explain every sensitive permission in context, and decide the billing-verification posture explicitly. |
| Onboarding | **6.6** | Beautiful, skippable, saves its place, and explains the four modes. | Four steps to first value, permissions requested only when needed, and a first session the person can finish before the novelty fades. |
| Monetization | **7.4** | Honest Free/Plus split, local Play Billing, restore, no fake urgency. | Server-side or Play-side fraud monitoring, graceful billing edge cases, and a Plus promise stated as outcomes rather than features. |
| Release readiness | **6.2** | 307 unit tests pass with 0 failures; docs are unusually thorough. | Current screenshots in every theme, no broken README links, real-device store assets, CI that verifies screenshots, and a physical-device QA matrix. |

**Weighted overall: 8.2 / 10.**

Weights: brand 20%, UX 20%, functionality 15%, enforcement 10%, accessibility 10%, performance 5%, trust 10%, onboarding 5%, monetization 5%, release 5%.

---

## 2. What is already 10/10 — protect it

These are not “nice to have”. They are the reason Aurora can become a 10 instead of a different app.

1. **A real brand world.** First Light is not decoration; it is the product's emotional argument. The logo, type, palette, iconography and motion belong together.
2. **Privacy as architecture, not marketing.** The release manifest genuinely removes network permissions. `allowBackup="false"`. No account. No analytics. No ads. That is rare and valuable.
3. **Honesty in the interface.** “Unavailable” instead of a fabricated number. Explicit “what Aurora can't block” surfaces. No fake social proof. No dark-pattern paywall.
4. **A serious policy engine.** Pure Kotlin, explicit rules for cooldowns, grants, budgets, schedules, strict commitments and time changes, with 307 passing tests across the suite.
5. **Craft in the small moments.** The ongoing notification, Live Update, Quick Settings tile, widget, finish card, haptic confirmation and reduced-motion path are the work of a team that cares.
6. **Documentation that tells the truth.** The brand bible, architecture notes, enforcement feasibility study and copy review are stronger than most funded teams produce.

The plan below should not sand these down. Every change must leave them intact.

---

## 3. The 1.8-point gap, in five sentences

1. **The first minute costs more than it gives.** A 5.5-second opening, an eight-step onboarding and a permission wall stand between the person and the first reclaimed minute.
2. **Home asks the person to choose too often.** “Normal Session”, “Custom Session”, “Turn on Shield” and a full-width “Completed” report can all look like the next required action.
3. **The language is the architecture, and it leaks.** *Session, setup, configuration, activation, run, preset, mode* describe the same few ideas. A premium product uses one word for one thing.
4. **Protection is clever but not yet proven.** The emulator's Shield median is about 1.2 seconds; there is no physical-device matrix, no latency budget in CI, and no published coverage boundary.
5. **The release surface does not match the product.** The README links Light screenshots that do not exist, store screenshots use fixture data, and the docs still describe some shipped work as unbuilt.

---

## 4. What “10/10” means here

Not “more features”. Ten out of ten means these six tests pass:

| Test | Pass condition |
|---|---|
| **The five-second test** | A new person can say what Aurora is, what it will do for them, and what to tap next — in five seconds, without reading a paragraph. |
| **The sixty-second test** | From install to a completed first protected session takes under 60 seconds and no more than two permission dialogs. |
| **The three-hundred-millisecond test** | A protected app is covered by Shield at p95 under 300 ms on a mid-range phone, with no flash of unprotected content. |
| **The TalkBack-only test** | Every core task — start a session, add an app, read progress, change a setting — can be completed with TalkBack alone, with no unlabeled control and no focus trap. |
| **The airplane-mode week** | Every core feature except purchase and restore works for a week with no network. The app never implies otherwise. |
| **The fresh-eyes trust test** | A privacy-conscious stranger can find, in under 30 seconds, what Aurora can see, what it cannot, and why it asks for accessibility. |

---

## 5. Design principles for the work

1. **Remove before you add.** The best version of Aurora is quieter, not busier.
2. **One primary action per screen.** Everything else is secondary or quiet. If two things are filled, neither is primary.
3. **Prove, don't claim.** A latency trace, a TalkBack recording and a merged-manifest screenshot are worth more than an adjective.
4. **Respect the return.** The opening is a welcome, not a toll. Brand moments are for arrivals, not every app switch.
5. **Local-first is a feature.** Never add a network dependency that weakens the promise without saying so in the product.
6. **Every number has a definition.** “Time Reclaimed” must be explainable in one sentence and must never contradict another number on the same screen.
7. **Premium is calm.** No confetti, no shame streaks, no red badges, no invented urgency.

---

## 6. Phase 0 — Instrument the truth (week 0)

You cannot make the last 1.8 points without a baseline. This phase adds no user-facing features.

### 6.1 Measure the things the rating depends on

| Metric | Tool | Baseline to record |
|---|---|---|
| Cold start to first frame | Macrobenchmark + Perfetto | Currently about 5.2 s on the emulator, including the 5.5 s opening |
| Cold start to interactive | Macrobenchmark | Not measured |
| Shield present latency | Existing `ShieldTrace` + new benchmark harness | About 1.2 s median on the emulator |
| Jank | Macrobenchmark `FrameTimingMetric` | Not measured |
| APK / AAB size | Gradle | Record per variant |
| Screen-reader task success | TalkBack script | Not measured |
| Contrast | Accessibility Scanner + manual | Not measured |
| Screenshot currency | Roborazzi | Only Dark / LargeText / ReducedMotion baselines exist |

Add a `benchmark` module with startup, scroll and Shield-latency benchmarks. Keep it out of the app's runtime dependencies.

### 6.2 Fix the truth surface before changing the product

- Regenerate every missing baseline, including Light. The README currently links `home_navigation_Light.png`, `library_shield_Light.png` and `track_Light.png`, none of which exist.
- Correct `docs/BUSINESS_MODEL.md` where it says nothing is implemented; billing, Plus limits and the paywall exist.
- Correct `docs/ROADMAP.md` and `README.md` where shipped work is still described as future.
- Add a “Docs are current as of” line and a link-check step to CI.
- Replace store screenshots 02 and 03 that use Robolectric fixtures and placeholder icons with real-device captures.

### 6.3 Define done for a screen

A screen is done only when all of these are true:

- One primary action, visually unambiguous.
- Empty, loading, error and offline states are designed.
- 200% font and TalkBack pass.
- Light and Dark screenshots are current.
- Every number has a definition or an explainer.
- No string is hardcoded in Kotlin when it belongs in `strings.xml`.

**Exit criteria:** a one-page baseline report with the numbers above, and a green docs link check.

---

## 7. Phase 1 — The first sixty seconds (weeks 1–2)

This is the highest-leverage work in the plan. It converts Aurora's craft into felt value.

### 7.1 The opening: from 5.5 s to a welcome

Today `Unveil.kt` runs a 5.5-second formation on every arrival after five minutes away. The animation is beautiful. It is also the most expensive thing the app asks of a returning person.

Keep the full First Light formation for the moments that deserve it:

| Moment | Treatment |
|---|---|
| First launch after install | Full formation, **3.2 s**, tap to skip |
| First launch of a new day | Brief formation, **1.8 s** |
| Return after 5+ minutes | Finished logo fade, **1.2 s** |
| Warm return / activity recreation | No opening |
| Reduced motion or TalkBack active | Immediate, no formation |
| “Replay the opening” in About | Full 5.5 s, on request only |

Implementation notes:

- In `MainActivity.kt`, replace `opensWithFirstLight(...)` with a policy that distinguishes first install, new day, real absence and recreation.
- In `Unveil.kt`, separate `FormMs`, `PartAt` and `ShowAt` into named presets. Do not hardcode a single total.
- Never hide the page for more than 600 ms after preferences are ready. Today the page can stay hidden until `ShowAt` (4.25 s).
- Keep the tap-to-hurry path, and make the first tap skip immediately rather than shortening to 350 ms.
- Add a startup trace marker at `setContent`, first frame, preferences ready, and opening finished.

**Acceptance:** Pixel 6a-class device, cold start to interactive ≤ 2.5 s; first-install opening ≤ 3.2 s; return opening ≤ 1.2 s; no frame longer than 200 ms during the opening; TalkBack users never hear or wait for it.

### 7.2 Onboarding: eight steps to four

The current flow is Welcome → Motivation → Apps → Modes → Intent → Shield → Keep → First Light. It is thoughtful, but it asks for reflection, decisions and permissions before the person has felt anything.

Collapse it to four screens:

1. **Promise + what matters.** One line of brand, one question (“What do you want more time for?”), three suggested answers and “Something else”. This merges the current Welcome and Motivation steps.
2. **Choose your apps.** Searchable list, recent apps first, multi-select, “Skip for now”. No mode explanation yet.
3. **Choose your default Shield.** Four modes as large, calm cards with one sentence each and a “Recommended” marker on Flexible. The person can change this later.
4. **Turn on Shield + start your first session.** One permission explainer, one system button, then a prefilled 25-minute session with one chosen app. Intent is offered after the first session, not before it.

Move the remaining education into context:

- Usage access is requested when the person first opens Track, not during onboarding.
- Notification permission is requested when the first timed session starts.
- Battery optimization guidance appears only on OEMs that need it, after the first session.
- “Keep” becomes a compact card on Home after the first session: “Keep Aurora running in the background.”

Keep the skip path, keep the saved step, and keep the final First Light moment — but make it the reward for finishing, not another gate.

**Acceptance:** median time to first session under 60 s; onboarding completion ≥ 80%; accessibility permission grant ≥ 65%; no permission dialog before the person understands why.

### 7.3 The first session

The first session should be the product's best demo:

- Prefill 25 minutes, one app, Flexible mode.
- Primary button: **Start 25 minutes**. Secondary: **Change**.
- While it runs, the ongoing notification and the Home card show the same countdown and the same words.
- At the end, one warm card: “25 minutes back.” Then two choices: **Do it again tomorrow** (creates a schedule) and **Done**.
- Never show XP, level or streak on this first finish card. The reward is the time, not the token.

**Acceptance:** first-session completion ≥ 60%; “Do it again tomorrow” chosen by ≥ 25% of finishers; no first-session dead end.

---

## 8. Phase 2 — One Home, one language (weeks 2–4)

### 8.1 Home hierarchy

Home should answer three questions in order: *Am I protected? What should I do now? What have I gained?*

Proposed first viewport:

```
┌─────────────────────────────────────┐
│ Aurora                    Settings  │
│                                     │
│ Shield is on · until 18:30          │  ← status, one line
│                                     │
│ ┌─────────────────────────────────┐ │
│ │  Start a session                │ │  ← the only filled action
│ │  25 min · Flexible · 1 app      │ │
│ └─────────────────────────────────┘ │
│                                     │
│ Time reclaimed                      │
│ Today 42m        This week 4h 10m   │
│                                     │
│ Now                                 │
│ Deep Work · 18 min left   [Open]    │  ← only when something runs
│                                     │
│ Next                                │
│ Evening wind-down · 21:30  [Edit]   │  ← only when something is scheduled
└─────────────────────────────────────┘
```

Rules:

- Exactly one filled primary on Home. If a session is running, the primary becomes **Open session**; do not add a second filled “Turn off”.
- The finished-session report is a compact card with a quiet action, never a full-width filled button competing with the next session.
- “Normal Session” and “Custom Session” become one primary **Start a session** with a secondary **Use a saved setup** link. The chooser can be a bottom sheet, not two permanent peer cards.
- Reserve bottom padding for the floating dock so no primary content sits under it at rest.
- Move “Time Reclaimed” above the fold; it is the outcome, not a footnote.
- Remove the empty-state repetition: one explanation, one action.

**Acceptance:** five-second test with five fresh people: ≥ 4/5 can name the next action; one filled primary on every Home state; no content obscured by the dock at first paint.

### 8.2 One vocabulary

The UI should use five nouns and no internal jargon.

| Concept | UI word | Never say in UI |
|---|---|---|
| One period of protection | **Session** | run, activation, occurrence |
| A saved combination of Shield + Intent + Focus | **Setup** | configuration, config, preset |
| The app/site restriction system | **Shield** | policy, blocklist |
| A pause before opening an app | **Intent** | intentional opening flow |
| Timed deep work | **Focus** | focus profile, focus plan |
| Recurring time | **Schedule** | recurring timing, window |
| Verified protected time | **Time reclaimed** | time set, shielded time |

Add a `docs/GLOSSARY.md` and make it the source of truth for strings, docs and store copy. Rename UI strings before renaming code; code can keep its precise internal names.

Specific copy fixes:

- “Photos is blocked.” is accurate in Always Block but too absolute in Cooldown or Friction. Use one verb per mode — **blocked**, **paused**, **cooling down** — and keep the same word on the Shield, the notification and the post-session report.
- “Normal Session” → **Quick session** (or simply **Start a session**).
- “Custom Session” → **Saved setup**.
- “Time set” → **Time protected**.
- “First glow” / XP → keep the mechanic, but call it **Momentum** and keep violet as the rare secondary accent. It must never be the most saturated thing on Track; Time Reclaimed is the headline.

**Acceptance:** a string audit finds zero uses of “configuration”, “activation”, “run” or “preset” in user-facing copy; the glossary is linked from the README.

### 8.3 Screen-by-screen cleanup

**Shield library**

- One primary: **New Shield**.
- Cards: tap to edit; overflow for Duplicate/Delete; never make Delete a peer of Edit.
- Each card states mode, app count, and whether it is active now.
- Empty state: one sentence and one action.

**Intent library**

- Lead with the promise (“A pause before the apps you open by habit”), not the configuration.
- Show the pause length and what happens after it on the card.
- Fix the black app icon in the baseline; verify icons in Light and Dark.

**Track**

- Lead with one number and one sentence: “You protected 4h 10m this week.”
- Move XP/Momentum below the outcome.
- Explain “Time reclaimed” with a one-tap definition.
- Never show “6h” next to “0 sessions” without an explanation; if the fixture is wrong, fix the fixture.
- Make Goals a first-class tab only when the person has a goal; otherwise show a single “Set a goal” card.

**Settings**

- Four groups: **Protection**, **Privacy & data**, **Appearance**, **About**.
- Permission status at the top of Protection, with a green/amber dot and a one-line fix.
- Diagnostics behind a long-press or “Advanced”, not in the main list.
- Add **Privacy proof**: a screen that shows the merged manifest permissions, what each is for, and a link to the policy. This is a brand asset, not a compliance chore.

**Plus**

- One hero, three outcomes, one price, one restore link.
- State the free limits plainly at the moment they are reached, not as a surprise.
- No countdowns, no pre-selected annual plan, no “limited time”.

**Acceptance:** SUS ≥ 85 with 8–12 target users; task success ≥ 90% for “start a session”, “block an app”, “see my progress”; zero screens with two filled primaries.

---

## 9. Phase 3 — Protection you can feel (weeks 3–5)

### 9.1 Shield latency budget

The current emulator median is about 1.2 s. That is long enough to see the protected app before the Shield covers it. Premium protection must feel instantaneous.

Targets:

| Metric | Target |
|---|---|
| Shield first frame after protected app resumes, p50 | ≤ 150 ms |
| p95 | ≤ 300 ms |
| p99 | ≤ 600 ms |
| Flash of unprotected content | 0 frames on the test matrix |
| Overlay re-assert after a repeat event | ≤ 100 ms |

Work:

- Pre-warm the overlay host when the accessibility service connects, not on the first protected event.
- Reuse the `ComposeView` and the First Light artwork; avoid rebuilding the shader on every present.
- Keep the overlay content lightweight: no network, no disk, no catalog lookups on the first frame. Resolve the app label and icon asynchronously and draw the frame first.
- Instrument `ShieldTrace` with a budget assertion in debug builds.
- Add a “Shield speed” line to diagnostics: p50/p95 over the last 50 presents.

**Acceptance:** the benchmark harness fails CI if p95 exceeds 300 ms on the reference device.

### 9.2 Physical-device matrix

The emulator cannot prove an accessibility overlay on OEM software. Test at least:

- Pixel 8/9, Android 16 (AOSP baseline)
- Samsung Galaxy S24/S25, One UI 7/8
- Xiaomi 14/15, HyperOS 2
- OnePlus 13 / Oppo Find X8, ColorOS 15
- One mid-range device (e.g. Pixel 8a or Galaxy A55)

For each: install, grant accessibility, start a strict session, open a protected app, press Home, recents, split screen, picture-in-picture, lock/unlock, reboot, force-stop Aurora, revoke accessibility, change time zone, change clock, enable battery saver, enable dark mode, enable 200% font, enable TalkBack.

Record a short screen capture for each. Put the matrix in `docs/DEVICE_MATRIX.md` with pass/fail and dates.

### 9.3 Coverage honesty

Aurora can only see what Android exposes. Say so precisely:

- **Supported browsers** for web protection: list them in Settings.
- **In-app browsers and private tabs** are not covered; say this on the Web & content Shield screen.
- **Short-form video** inside a browser is covered only when the address bar exposes the domain.
- **What Aurora cannot block**: Settings, calls, emergency services, other accessibility overlays, a determined person with developer tools. Put this on one honest page, linked from Shield.

This is not a weakness. It is the difference between a premium product and a promise that breaks.

### 9.4 Strict commitments and anti-bypass

Keep the current restraint: do not claim unbreakable protection. Do add:

- A clear “During a strict session” explainer before the person commits.
- A single place to see what is locked and when it ends.
- A safe exit for emergencies that is documented, not hidden.
- A post-session report if the service was killed or accessibility was revoked: “Protection paused for 12 minutes.”

**Acceptance:** 50 scripted bypass attempts on the matrix: every one either blocked or disclosed in the report; zero crashes; zero silent failures.

---

## 10. Phase 4 — Accessibility proof (weeks 4–5)

Accessibility is currently a design intention, not a verified outcome. Make it a gate.

### 10.1 TalkBack script

Write and run a scripted pass for twelve tasks:

1. Complete onboarding.
2. Start a session.
3. Add an app to a Shield.
4. Create a schedule.
5. Read Time Reclaimed.
6. Read a Shield while it covers an app.
7. Leave a Shield for Home.
8. Grant access from a Shield.
9. Find and change a setting.
10. Restore purchases.
11. Read the ongoing notification.
12. Use the widget and Quick Settings tile.

For each: focus order, labels, state announcements, custom actions, error recovery, and whether the task can be completed without sighted help.

### 10.2 Visual accessibility

- AA contrast (4.5:1 body, 3:1 large) on every glass surface, in Light and Dark. Glass is the risk: test the worst-case backdrop, not the average.
- 200% font and 200% display size: no clipping, no overlapping, no hidden primary action.
- Color is never the only signal: pair green/amber with text and icon.
- Touch targets ≥ 48 dp, including the dock and icon buttons.
- Reduced motion: no First Light, no shader, no parallax, no spring overshoot. Haptics remain optional.
- RTL: mirror layout, not just text.

### 10.3 The overlay

The Shield overlay is a separate window with its own lifecycle. Test:

- TalkBack can read the Shield and reach both actions.
- Focus is not trapped after the Shield closes.
- Back and predictive back behave.
- The overlay does not cover the status bar in a way that hides the clock or battery.
- The overlay respects display cutouts and landscape.

**Acceptance:** Accessibility Scanner reports zero critical issues; 12/12 TalkBack tasks pass; 200% screenshots for every core screen; zero unlabeled interactive elements.

---

## 11. Phase 5 — Trust, billing and the Plus story (weeks 5–7)

### 11.1 Make privacy provable in the product

Aurora's privacy claim is its strongest differentiator. Do not hide it in a README.

Add **Settings → Privacy & data → Privacy proof**:

- “Aurora has no internet permission.” Show the merged release manifest permission list, with a short explanation of each.
- “Your data stays in this app.” List what is stored: preferences, rules, runtime, history, entitlement cache.
- “Backup is off.” Explain what that means for switching phones.
- “Export my data” and “Delete everything” actions, with a typed confirmation for delete.
- A link to the privacy policy and a “last updated” date.

Add contextual permission explainers before every system dialog:

- Accessibility: “Aurora needs to see which app is opening so it can cover it. It does not read your messages, keystrokes or screen content.”
- Usage access: “Aurora reads Android's usage log on this phone to show your screen time. Nothing is uploaded.”
- Notifications: “Aurora shows one ongoing notification while protection runs, so you always know.”
- Battery optimization: OEM-specific, only when needed.

### 11.2 Billing posture

`Billing.kt` is explicit: there is no server; purchases are checked on the phone. That is consistent with the privacy promise, but it is not proof against a modified app. Decide the posture and write it down.

Recommendation for 1.0:

- **Keep client-only verification** and the no-network promise.
- Use Play Console's voided-purchase reporting and refund data as the fraud signal.
- Add a documented threshold: if Plus fraud exceeds a set percentage of revenue, revisit a minimal verification service and disclose the network use.
- Do not quietly add `INTERNET` for “analytics” or “diagnostics”. That would break the brand's central promise.

Handle the billing edge cases properly:

- Pending purchases and slow payment methods.
- Grace period and account hold.
- Pause and resubscribe.
- Price changes and proration display.
- Restore on a new device.
- Offline grace: make the current 14-day cache explicit in the UI.
- License testers for every SKU and region.

**Acceptance:** purchase success ≥ 98% in closed testing; restore succeeds 100% on a clean install; no entitlement loss inside the documented grace window; every billing error has a human sentence and a next step.

### 11.3 The Plus promise

Plus should be sold as outcomes, not feature counts:

1. **See what actually changed.** Advanced insights that connect sessions to screen time, with one clear weekly reflection.
2. **Stay with it.** Challenges and gentle momentum that never shame.
3. **Remove the ceilings.** Unlimited setups, schedules, protected items and Intent apps.

Show the comparison at the moment of need. When a free limit is reached, explain it in one sentence and show the exact Plus benefit, not a generic paywall.

**Acceptance:** in usability testing, ≥ 90% of people can explain what Plus adds and what stays free; refund rate < 2%; no dark-pattern checklist failures.

---

## 12. Phase 6 — The release surface (weeks 6–8)

### 12.1 Screenshots and store assets

- Regenerate every baseline in Light, Dark, 200% font and reduced motion.
- Add 360 dp, tablet, widget, tile, Shield overlay, onboarding and Plus.
- Fix the README links; add a CI link checker.
- Capture store screenshots on a real phone with real data (or clearly labeled demo data), never fixture icons.
- Refresh the feature graphic and the five Play screenshots from the current build.
- Add a short preview video: 15 seconds, the first session, no voiceover, captions.

### 12.2 CI and release engineering

- `./gradlew testDebugUnitTest lintDebug` on every PR.
- `verifyRoborazziDebug` on a stable runner with deterministic clocks and icon loading.
- Macrobenchmark on a nightly schedule; fail on startup or Shield-latency regression.
- Release checklist: version bump, changelog, privacy policy date, data safety form, store listing, rollback plan.
- Keep `versionCode` monotonic; tag every release.
- Add a crash-free and ANR dashboard using Play Console vitals only. Do not add a third-party SDK that contradicts the privacy promise.

### 12.3 Docs truth pass

Update:

- `README.md`: current screenshots, current commands, current claims.
- `docs/PRODUCT.md`: shipped vs planned.
- `docs/ROADMAP.md`: mark the 10/10 pass.
- `docs/BUSINESS_MODEL.md`: billing exists; state the client-only verification decision.
- `docs/RELEASE.md`: the actual release gates.
- `docs/design-studio/08-ten-out-of-ten.md`: add this plan as the next pass.

**Acceptance:** zero broken links; 100% of screenshot baselines current; 10 consecutive green CI runs; store assets approved by a fresh reviewer.

---

## 13. Phase 7 — The 10/10 gate (weeks 9–10)

Do not call it 10/10 because the checklist is done. Call it 10/10 when the gates pass.

| Gate | Metric | Threshold |
|---|---|---|
| First value | Median time to first completed session | ≤ 60 s |
| Onboarding | Completion rate | ≥ 80% |
| Permission | Accessibility grant after explainer | ≥ 65% |
| Home clarity | 5-second test, correct next action | ≥ 4/5 |
| Usability | SUS | ≥ 85 |
| Shield speed | p95 present latency, reference device | ≤ 300 ms |
| Shield reliability | Scripted bypass attempts blocked or disclosed | 50/50 |
| Stability | Crash-free sessions | ≥ 99.5% |
| ANR | ANR rate | < 0.3% |
| Accessibility | TalkBack tasks passed | 12/12 |
| Accessibility | Critical scanner issues | 0 |
| Performance | Cold start to interactive, reference device | ≤ 2.5 s |
| Privacy | Merged release manifest network permissions | 0 |
| Billing | Purchase success, closed testing | ≥ 98% |
| Billing | Restore success | 100% |
| Release | Current screenshot baselines | 100% |
| Release | Broken docs links | 0 |
| Delight | NPS after 14 days | ≥ 50 |
| Retention | D30 retained installs | ≥ 25% |

Rollout: internal → 5% → 20% → 100%, with a 48-hour soak and a guardrail review at each step. If crash-free, ANR or refund rate moves outside the guardrail, stop and fix before expanding.

---

## 14. The 10/10 checklist

### Brand and visual
- [ ] First Light, type and palette used consistently in every state.
- [ ] Light and Dark baselines current for every screen.
- [ ] Violet stays a rare secondary accent, never the dominant surface.
- [ ] Every icon has a Light and Dark proof.
- [ ] Empty, loading, error and offline states are authored.
- [ ] Motion has a reduced-motion equivalent.

### UX and language
- [ ] One filled primary per screen.
- [ ] Home answers “protected? what now? what gained?” in one viewport.
- [ ] Five-noun glossary enforced in UI copy.
- [ ] Onboarding is four steps to first value.
- [ ] Every permission has a contextual explainer.
- [ ] Settings is four groups, not a list of everything.

### Functionality and reliability
- [ ] Shield p95 ≤ 300 ms on the reference device.
- [ ] Physical-device matrix completed and recorded.
- [ ] Web coverage boundaries stated in the app.
- [ ] Strict-session lock and exit are documented.
- [ ] Service-killed and permission-revoked reports exist.
- [ ] Billing edge cases tested.
- [ ] Data export and delete work.

### Accessibility
- [ ] 12/12 TalkBack tasks pass.
- [ ] 200% font and display size pass.
- [ ] AA contrast on glass, Light and Dark.
- [ ] 48 dp targets everywhere.
- [ ] No focus traps in the overlay.
- [ ] Reduced motion honored everywhere.

### Trust
- [ ] Release manifest has zero network permissions.
- [ ] Privacy proof screen ships.
- [ ] Backup-off consequence explained.
- [ ] Billing verification posture documented.
- [ ] No analytics, ads or third-party trackers.

### Release
- [ ] Every screenshot baseline current.
- [ ] README and docs links green.
- [ ] Store assets from a real device.
- [ ] Macrobenchmark and screenshot verification in CI.
- [ ] Closed beta with 50 people for two weeks.
- [ ] Rollback plan and release checklist.

---

## 15. What not to do

1. **Do not add an account, cloud sync or social feed.** They would break the privacy promise and the calm.
2. **Do not add more protection modes.** Four is already the right number; make them easier to understand.
3. **Do not make the opening longer.** The brand moment is strong enough. Respect the return.
4. **Do not add shame-based streaks or red badges.** Momentum should be quiet and secondary, not loud and anxious.
5. **Do not add an AI coach that needs a network.** If you add guidance, make it local, deterministic and explainable.
6. **Do not chase Material You dynamic color.** Aurora's palette is the brand.
7. **Do not hide the free limits.** The honesty is the premium.
8. **Do not call it 10/10 before the device matrix and TalkBack pass are done.**

---

## 16. Risks and mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| OEM accessibility behavior differs or regresses | High | High | Physical matrix, per-OEM guidance, Shield trace export, conservative claims |
| Shield latency remains above budget on mid-range phones | Medium | High | Pre-warm overlay, lightweight first frame, benchmark gate |
| Client-only billing is abused | Medium | Medium | Play voided-purchase monitoring, threshold for server verification, clear docs |
| Accessibility overlay policy changes on Play | Low | High | Keep an activity-based fallback design ready; document the dependency |
| Screenshot tests stay flaky | High | Low | Freeze clock, wait for icons, deterministic fixtures |
| Scope creep delays the gate | High | Medium | One primary metric per phase; no new features during Phase 7 |
| Privacy promise conflicts with server verification | Medium | High | Decide explicitly; if server is added, disclose it and update the promise |
| Founder review becomes a bottleneck | Medium | Medium | Batch copy and art reviews at phase boundaries, not per screen |

---

## 17. Ten-week calendar

| Week | Focus | Output |
|---|---|---|
| 0 | Instrument and truth pass | Baseline report, benchmark module, docs link check |
| 1 | Opening and onboarding | 3.2 s / 1.2 s opening, four-step onboarding prototype |
| 2 | First session and Home hierarchy | First-session flow, one-primary Home, five-second test |
| 3 | Vocabulary and screen cleanup | Glossary, Shield/Intent/Track/Settings/Plus pass |
| 4 | Shield speed and OEM matrix | p95 trace, pre-warmed overlay, device matrix v1 |
| 5 | Accessibility | TalkBack script, 200% proof, contrast fixes |
| 6 | Billing and trust | Privacy proof screen, billing edge cases, Plus copy |
| 7 | Release surface | Current screenshots, store assets, docs truth pass |
| 8 | CI, beta and soak | Green CI, 50-person beta, crash/ANR review |
| 9 | Gate rehearsal | All metrics measured; fix the bottom three |
| 10 | 10/10 gate and rollout | Gate review, 5% → 100% rollout |

---

## 18. The next seven days

1. Add the benchmark module and record cold start, jank and Shield latency.
2. Put the opening behind a preset flag; test 3.2 s first-install and 1.2 s return on the emulator and one phone.
3. Merge onboarding steps 0+1 and 4+5 into a four-step prototype; run five people through it.
4. Remove the second filled primary from Home; add dock-safe padding; capture Light and Dark.
5. Fix the three broken README Light links and regenerate the missing baselines.
6. Run a TalkBack pass on Home, Shield, Track and Settings; log every issue.
7. Book the physical-device matrix and run the first Pixel + Xiaomi pass.
8. Write the billing-verification decision note (client-only vs server) and get it approved.
9. Define “Time Reclaimed” in one sentence and add the explainer.
10. Run a five-person five-second test on the current Home and the proposed Home.

---

## 19. How we will know it worked

**North star:** weekly verified protected time per active person.

**Supporting metrics:**

- Time to first completed session.
- Onboarding completion and permission grant.
- Session completion rate and “do it again” rate.
- Shield p95 latency and silent-failure rate.
- TalkBack task success and accessibility scanner issues.
- Crash-free sessions and ANR rate.
- D7 / D30 retention.
- Plus conversion, refund rate and restore success.
- NPS and “would be disappointed without it” score.

**Guardrails:** no increase in uninstall rate, no decrease in permission grant, no billing complaints, no accessibility regressions.

---

## 20. Evidence appendix

| Finding | Evidence |
|---|---|
| 5.5 s opening, five-minute reopen threshold | `app/src/main/java/app/aurora/ui/components/Unveil.kt:213-231` |
| Page hidden until 4.25 s | `Unveil.kt:222`; `MainActivity.kt:95-100` |
| Eight onboarding steps | `app/src/main/java/app/aurora/ui/onboarding/OnboardingScreen.kt:107` |
| Multiple filled Home actions | `app/src/main/java/app/aurora/ui/home/HomeSections.kt:397`; `HomeScreen.kt:269` |
| No server-side billing | `app/src/main/java/app/aurora/plan/Billing.kt:44-50` |
| No network permissions in release | `app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml` |
| Backup disabled | `app/src/main/AndroidManifest.xml:55` |
| 307 unit tests, 0 failures | `./gradlew testDebugUnitTest` |
| Missing Light baselines | `app/src/test/screenshots/` contains only Dark/LargeText/ReducedMotion; README links Light files |
| Stale business-model doc | `docs/BUSINESS_MODEL.md` says billing is not implemented; billing code ships |
| Shield latency | `app/src/main/java/app/aurora/protect/Interceptor.kt:248-250` notes a 1.2 s emulator median |
| Baseline profile exists, no macrobenchmark | `app/src/main/baseline-prof.txt`; no benchmark module |

---

*This plan is deliberately a plan, not a rewrite. Aurora's soul is already right. The work is to make the first minute effortless, the protection provable, the language singular, and the release surface as trustworthy as the product.*
