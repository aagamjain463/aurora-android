# Handoff: Aurora product reinvention with Intentional App Opening addendum

- Task ID: aurora-product-reinvention-with-intentional-app-opening-adde-20261029-111633
- Task name: Aurora product reinvention with Intentional App Opening addendum
- Current agent: Claude Code (Opus 5.5)
- Status: completed (local commits only; not pushed or merged; separate launch gates in docs/REINVENTION_VALIDATION.md remain)
- Last updated: 2026-10-29T16:50

## Objective
Execute the founder's "AURORA — COMPLETE PRODUCT REINVENTION" prompt plus the "OFFICIAL PRODUCT ADDENDUM" (Intentional App Opening + 8 approved strings). Fix real Shield blocking, correct four modes, remove Lockdown, custom duration picker everywhere, schedule wizard, full app list, Today time-away metric, brand redesign, centralized copy, tests, emulator E2E, screenshots, docs, final report. No push/merge.

## Branch and commits (local only, nothing pushed)
Branch `feat/aurora-product-reinvention` from `feat/aurora-complete` (2bc833e):
- 9b432e1 feat: overlay Shield, four-mode sessions, Intentional App Opening, schedules redesign
- 3f5a7d5 fix(shield): close and stale-event races, night hero, post-start navigation
- 65e041e fix(intent): exact foreground time from usage records; Intent prompt polish
- 489d4a0 fix(insights): don't count aurora itself as screen time
Uncommitted (intentionally): `docs/screenshots/aurora-reinvention/` (24 real emulator PNGs), `.ai-state/phone-backup/` (founder phone DataStore backup + `ui.sh` adb helper).

## ROOT CAUSE of "Instagram not blocked" (verified on founder's phone, read-only)
Phone: Redmi 22041219I, HyperOS 1.0 (V816), Android 14, adb serial `ayeqz595b6g6u4ey`. Phone was locked; HyperOS blocks `adb input` (INJECT_EVENTS) so it can't be driven unattended.
1. MIUIOP(10021) "background start activity" = ignore with recent rejectTime: old `ShieldActivity` launched from the service was silently refused (no exception) → Instagram stayed usable, diagnostics counted it as presented.
2. `dumpsys accessibility`: ShieldService enabled but in "Crashed services", not bound → no events at all (process killed by MIUI; Android didn't rebind).
3. Founder's saved data: `protected_items` held only `web:reddit.com`; Instagram existed only in `rules.apps` (mode Block) → not enforced anyway (old picker toggled it off / list state mismatch).
4. Old onboarding app list had `ordered.take(30)` → apps after ~30 alphabetically missing.
5. Raw package names shown while launcher list loaded (`ProtectData.label` fallback).
Fix: Shield is now a `TYPE_ACCESSIBILITY_OVERLAY` window owned by the bound service (`protect/ShieldOverlay.kt`), no BAL/MIUI gate; holds transient audio focus; Xiaomi-specific "Not running" status + Autostart/battery guidance; diagnostics show 7-link chain. Spike build was installed on the phone with `adb install -r` (data preserved; backup at `.ai-state/phone-backup/aurora.preferences_pb.before-reinvention`); install cleared the crashed state and Android re-bound the service. **Real-phone blocking with the overlay is NOT yet verified** (phone locked).

## Completed work (all compile; 170 unit tests pass, 0 failures)
- Domain (`protect/Rules.kt`, `protect/Policy.kt`, `Actions.kt`): `Mode {Flexible, Friction, Cooldown, AlwaysBlock}` (old Block/RulesOnly → Flexible via @JsonNames). `ProtectionSession` timed sessions with fixed keys; Always Block non-cancellable (`turnOff` refuses), survives edits/removal/downgrade/reboot (wall-clock fallback), 120-min/day Free allowance (`plan/AlwaysBlockBudget.kt`, renamed from Lockdown.kt). Lockdown runtime migrated to Always Block session (`migrated`, `migrateRuntime` at startup). Schedules carry keys/groupIds/mode/friction/cooldown; Always Block windows committed to sessions (`commitScheduleWindows`) before any edit; on Free a scheduled AB window = Flexible block beyond allowance. Precedence: AlwaysBlock > Flexible > Focus > Limit > Cooldown > Friction > IntentCooldown > Intent; grants lift only own level and weaker.
- Friction: math (easy/med/hard, `mathProblem`/`checkAnswer`), written intention (2+ words, read-back confirm), reflection (2 questions + stored pause). Then usage time picker → `Grant`.
- Cooldown: wait before access, stored `CooldownWait` per restriction id (reopen doesn't restart), then usage picker.
- Intentional App Opening: `IntentConfig` per app (reflection secs, reason, alternative, default usage, expiry Reminder/Cooldown, cooldown mins, freshEachOpen). `IntentUse` counts foreground time only (Interceptor `onForeground`, screen-off receiver) and is corrected from UsageStats when usage access granted (`correctIntentUse`). Reminder at expiry while app open; post-usage cooldown blocks; after cooldown fresh decision. Free 3 apps / Plus unlimited.
- Today: night-sky hero (both themes), approved headline, "Time away from protected apps" from real usage (`data/TimeAway.kt`: union of protection spans minus covered-app foreground; screen-off counts as away; disclosed). Protection and Progress as separate pillars.
- Brand: new palette/type (`ui/theme`), First Light symbol (`AuroraMark`, adaptive + monochrome icon XML), Aurora Light lit-horizon (`ui/components/Light.kt`), Shield always night. Reusable `DurationPicker` + `Chip`/`ChoiceCard`/`PillarHeading` (`ui/components/Kit.kt`).
- Screens rewritten: Protect, StartScreen (5-step start flow with AB commit "I'm ready to commit"), ItemScreen, Schedules list + 7-step ScheduleEditorScreen, Limits, Groups, IntentScreen, IntentAppScreen, Onboarding (8 steps, full searchable app list), ShieldContent (all reasons), ShieldStatus/diagnostics. Lockdown screen/route removed.
- Copy: ~420 strings in `res/values/strings.xml`, 8 founder strings exact (CopyTest asserts them + no "Lockdown"/"moment of awareness").
- Tests added: PolicyTest (4 modes + 7 Intent scenarios + precedence), RulesTest (schedules overlap/overnight/DST/commit/reboot/migration), MigrationTest (founder's real JSON), TimeAwayTest, DurationTest, FrictionTest, CopyTest, InterceptorTest regressions.

## Emulator E2E verified (AVD `aurora_pixel`, API 36, `emulator-5554`, Shield enabled via Settings UI earlier; usage access toggled via Settings UI)
- Flexible on Clock (custom 7 min): blocked via launcher intent, recents, back, rapid opens; survives process recreation (reinstall); Turn off → Clock opens; re-enable → blocked.
- Always Block 30 min: blocks; no turn-off button; turning off overlapping Flexible keeps Clock blocked.
- Friction (Contacts): wrong answer rejected + new problem; correct → usage picker; custom 1 min; Shield returned at expiry while app stayed open.
- Cooldown (Calendar, custom 1-min wait): wait started at first attempt, unchanged after leave/reopen, then "wait is over" + usage picker; 5-min grant stored.
- Intent (YouTube): reflection countdown, alternative shown, custom 1-min usage; with usage access, expiry after ~60 s real foreground; post-usage 1-min cooldown screen; after cooldown fresh "Opening YouTube?".
- App picker: all 15 launchable apps, names + icons, through "YouTube Music".
- Overlay keyboard input works (math answer).
- Cold start (am start -W) 869 ms on emulator.

## Remaining work
1. Schedule wizard E2E (was mid-flow: Protect → Schedules → "New schedule" at bounds [437,1235][717,1293]); verify window enforces and AB window can't be skipped.
2. Dark mode + 200% font screenshots; onboarding screenshots; Insights/Settings visual polish (Insights/Settings/Focus/Plus/Web/Groups only partially restyled, mostly old layouts with new tokens).
3. Verify Reminder expiry path on emulator (only Cooldown expiry was run).
4. Daily limit E2E; Focus enforcement E2E (Focus screen uses DurationPicker but not re-verified).
5. Docs: `docs/BRAND_SYSTEM.md`, `docs/AURORA_COPY_REVIEW.md` (8 strings APPROVED — FOUNDER; Intent section PROPOSED — FOUNDER REVIEW PENDING; generate table from strings.xml), update PRODUCT.md/SHIELD.md (overlay, modes, precedence, MIUI), real-phone checklist.
6. Roborazzi baselines need re-recording (`./gradlew recordRoborazziDebug`), lint run (`lintDebug`, warningsAsErrors + baseline), release build.
7. Physical phone: unlock phone, `adb install -r -t app/build/outputs/apk/debug/app-debug.apk`, add Instagram, start Flexible, open Instagram. If service shows "crashed", toggle aurora Shield off/on in Accessibility. Mark as pending until done.
8. Commit docs/screenshots; final consolidated report to founder.

## Technical decisions
- Overlay over activity: avoids OEM background-start gates; app stays underneath so grants need no background launch.
- Late window-event grace (`Interceptor.LeftGraceMs` 1 s) after Return home / aurora screen, else Shield reappeared.
- Saved preferences never protect; only sessions/schedules/Focus/limits/Intent do.
- Intent time = foreground only; exact via UsageStats, estimate otherwise (UI says so).
- deleteAllData keeps running Always Block sessions.

## Known issues / risks
- Process death on MIUI still stops enforcement (Android limitation); mitigated by truthful status + Autostart guidance.
- Free AB-schedule semantics (partial commit) need founder confirmation.
- `com.miui.securitycenter` added to SystemExempt.
- Screenshot test baselines stale (verify will fail until re-recorded).

## Files modified (main)
protect/{Rules,Policy,Interceptor,ShieldService,ShieldOverlay(new),ShieldContent(new),ShieldTrace,ContentService,ProtectedItems}.kt; removed ShieldActivity.kt, ShieldScreen.kt, ShieldActivityTest.kt; Actions.kt; AuroraApplication.kt; plan/{Plan,AlwaysBlockBudget}.kt; progress/Progress.kt; data/{ScreenTime,TimeAway(new)}.kt; ui/theme/*; ui/components/{Brand,Light,Kit(new),Rows,Buttons,Controls,Scaffold}.kt; ui/protect/* (ProtectParts, StartScreen, IntentScreens new); ui/home/HomeScreen.kt; ui/onboarding/OnboardingScreen.kt; ui/AuroraNavHost.kt; res/values/{strings,colors}.xml, res/values-night/colors.xml, res/drawable/ic_launcher_*.xml; tests listed above.

## Tests and results
`./gradlew :app:testDebugUnitTest` → 170 tests, 0 failures (12:31). `assembleDebug` OK. Lint, release build, roborazzi verify not run.

## Exact next steps
1. `git checkout feat/aurora-product-reinvention`; `./gradlew :app:testDebugUnitTest :app:assembleDebug`.
2. Boot emulator: `~/Library/Android/sdk/emulator/emulator -avd aurora_pixel -no-window -no-audio &`; helper `.ai-state/phone-backup/ui.sh` (set path vars). Launch apps with `adb shell "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n <component>"` (monkey doesn't launch here).
3. Finish schedule E2E, Reminder E2E, dark/large-text screenshots.
4. Write docs (BRAND_SYSTEM, AURORA_COPY_REVIEW, checklist), re-record roborazzi, run lint + release build, commit.
5. Ask founder to unlock phone for real-device test; report honestly.

<!-- Observable progress between checkpoints: `ai-handoff recorded aurora-product-reinvention-with-intentional-app-opening-adde-20261029-111633.md` -->

## Continuation checkpoint
- Verified branch and four recorded commits; product tree clean. Emulator and physical phone both connected.
- Current work: lint, release build, unit tests; remaining E2E, screenshots and docs.

## Checkpoint 2026-10-29T12:51
- First rerun: 170 tests, one async ProtectLimitTest notice race. Added wait for notice; focused test passes.
- Lint found stale ShieldActivity manifest/theme, obsolete audio API guard, locale/context/modifier API issues and quantity copy. Fixed; full lint/release/Robo recording running.
- Schedule wizard completed for Clock, weekdays 09:00–17:00 Always Block. Overlay blocks; disabling schedule retains committed session. Screenshots captured.
- Disk was briefly full; attempted cache deletion rejected by automatic review; no deletion occurred. Free space recovered externally to ~10 GiB.
- Current work: remaining Intent reminder, limits/Focus, dark/large text, docs.

## Continuation checkpoint 2026-10-29T13:41
- Current agent: Claude Code (Opus 5.5)
- Verified existing uncommitted lint/copy/screen/docs/baseline work; preserving it.
- Current work: final lint/release/unit/screenshot checks; remaining emulator E2E, copy inventory, physical-phone verification.
- Physical phone initially unauthorized; founder reports unlocked and connected.
- No push or merge. No founder-data clearing.

## Checkpoint 2026-10-29T13:56
- Fixed Plus compile failure (package name shadowed by local app); fixed Insights quantity grammar using plurals. Centralized Web/content screen copy.
- lintDebug, assembleDebug, assembleRelease and full unit run passed. Screenshot fixtures stabilized (no random math/live reflection timer or fresh-event history writes; wait for loaded Insights). Record and verify Roborazzi now pass.
- Copy inventory created: 601 entries, 8 founder-approved; Intent copy proposed.
- Emulator Focus enforcement verified on Contacts; screenshot shield_focus.png.
- Physical Redmi current APK installed with data preserved; USB authorization/security setting enabled by founder. Continuing service/Instagram E2E. Temporarily locked phone rotation to portrait; restore free at end.
- Remaining: daily limit, Reminder evidence review, dark/large-text/onboarding captures, final docs/graph update/commit/report, physical checks.

## Checkpoint 2026-10-29T14:30
- Redmi Instagram blocking now verified with attached TYPE_ACCESSIBILITY_OVERLAY under existing Study hours Flexible schedule. Back reaches Niagara launcher; https Instagram deep link is also shielded. Safe screenshot redmi_instagram_schedule_block.png. Founder data preserved; Instagram added through picker.
- Founder explicitly approved current Free Always Block schedule fallback. PRODUCT and copy review updated.
- Emulator Focus and custom 1-minute Contacts daily-limit enforcement verified. Reminder expiry running now (old similarly named screenshot did not actually show a reminder; do not count it as evidence).
- Latest lint/debug/release builds and Roborazzi record/verify pass. Copy centralization completed for Web and remaining Insights labels.
- Remaining: physical timed session/repeated opens, actual Reminder expiry, dark/large-font/onboarding evidence, copy inventory refresh, validation report, final checks/graph/commit.

## Checkpoint 2026-10-29T14:56
- Fixed two real E2E bugs with failing-before/passing-after regressions: usage interval queries now seed an already-open app from retained history and clip both edges; stale-event grace distinguishes rapid real reopenings by observed departure timestamps.
- Actual Reminder expiry verified while YouTube stayed open after 1 minute; replaced misleading old screenshot. Redmi 5/5 rapid reopen checks now pass, including Back to launcher each time.
- 173 tests, lint/debug/release and screenshot verification passed before final visual changes.
- Saved reference contract reviewed: added reusable art-only mountain/lake backdrop using built-in ImageGen, integrated with Today/Shield and dark Focus; native text/controls preserved. Progress Light remains separate and data-driven. Dark primary buttons use restrained green-to-dawn gradient. Visual baselines rebuilding.
- Phone rotation restored to original free mode. No data cleared; original schedule/limit settings preserved, Instagram added.
- Remaining: inspect final screenshots, final build/verify, documentation/asset prompt, commit task files only.

## Native emulator regression checkpoint
- Native instrumentation exposed Android 16 BadParcelableException when retrieving a large UsageEvents list; JVM tests did not reproduce Binder transfer. Replaced unbounded epoch query with one-day foreground seed and hour-sized query pages, preserving past-day unavailable semantics. Added repository regression for an already-open app across pages.
- Android read/permission failures now return unavailable; Today and Insights show unavailable rather than fabricated zero or requesting already-granted permission.
- Current work: final native rerun, final gates and delivery commits.

## Continuation checkpoint 2026-10-29T16:20
- Current agent: Claude Code (Opus 5.5)
- State: 4 commits; ~130 files staged; unstaged NavigationTest.kt + REINVENTION_VALIDATION.md. Emulator instrumentation result still unrecorded in validation doc.
- Current work: final gates (unit, lint, release, Roborazzi verify), emulator-only instrumentation (never on founder phone), then local commits. No push/merge.

## Checkpoint 2026-10-29T16:40 (Claude Code)
- Root cause of native `focusStartsAndEndsEarly` failure (thread dump via emulator-only adb root): all 64 Dispatchers.IO threads blocked in `ScreenTimeRepository.records` (TodayModel.refresh stacked under Compose test clock); Store writes (now on IO) starved, so Begin focus write never landed within 5 s.
- Fix: `ScreenTimeRepository` usage reads on `Dispatchers.IO.limitedParallelism(1)`; `TodayModel.refresh` skips while a measurement runs. Temporary probe removed from NavigationTest.
- Emulator instrumentation: 3 tests, OK, 3/3 consecutive runs. Emulator adb unrooted after.
- Current work: full gates rerun, validation doc, commits.

## Final checkpoint 2026-10-29T16:50 (Claude Code) — COMPLETED
- Commits on `feat/aurora-product-reinvention` (local, not pushed): 9b432e1, 3f5a7d5, 65e041e, 489d4a0, b384980 (carry-over snapshot of all staged work, made by a concurrent brand-studio task at 16:22), 972a162 (IO starvation fix + validation doc).
- Final gates: 175 unit tests 0 failures; lintDebug pass (warnings as errors, baseline unchanged); assembleRelease pass; verifyRoborazziDebug pass; assembleDebugAndroidTest pass; emulator instrumentation 3 tests OK x3; git diff --check clean; graphify update done.
- Physical Redmi: Instagram blocking, deep link, 5/5 rapid reopen verified earlier this task; not re-run after 972a162 (change only touches usage-read concurrency and Today refresh).
- Not committed on purpose: `.ai-state/` (handoffs, phone-backup with founder DataStore backup), `g1-results/`, `.DS_Store`, `gradle/gradle-daemon-jvm.properties`.
- Remaining outside this task: launch gates listed in docs/REINVENTION_VALIDATION.md (Play billing, OEM matrix, latency, TalkBack, founder copy review). No push/merge done.
- Note: another agent is working on the same branch (brand-studio); coordinate before further edits.
