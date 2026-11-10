> **Visual identity superseded (2026-11-07).** Brand, colour, type and art guidance in this document describes the retired First Light identity. The active spec is `brand/Aurora_Brand_Guidelines.md`; where they disagree, the guidelines win.

# Aurora reinvention: validation and delivery

Updated 29 October 2026. Branch: `feat/aurora-product-reinvention`. This closes the selected product-reinvention handoff; it does not certify a Play launch or every Android/OEM combination. No push or merge.

## Implementation completed

The product has four modes, a service-owned Shield overlay, custom durations, the schedule wizard, a full searchable app picker, Intentional App Opening, measured time away, and separate protection/progress surfaces. Legacy runtime and rule migration remain covered by tests.

This continuation preserved the previous four implementation commits. It completed lint and copy cleanup, the remaining screen styling, the brand/copy documentation, real-device validation and the following two runtime fixes:

- **Intent time stopped counting under an overlay.** Android does not necessarily pause the underlying app when an accessibility overlay appears. A query starting at the chosen session time missed the earlier resume event, so correction reset use to zero. Interval queries now seed the app already in front from the prior day's retained events, then clip both ends to the requested interval. Hour-sized query pages avoid Android 16's large UsageEvents Binder transfer failure; unavailable or revoked reads do not crash Today or fabricate measured zeros. A repository regression also covers an already-open app across several query pages. A failing-before/passing-after regression covers pre-window starts, pre-window closed apps and both clipping boundaries. A real 1-minute YouTube session now shows its Reminder without leaving the app.
- **Rapid reopening could bypass Shield.** The original stale-event grace also swallowed real launches inside one second. An observed launcher or Aurora window now establishes a departure timestamp: older events remain ignored, newer genuine reopenings are shielded. Two failing-before/passing-after tests cover launcher and Aurora departures. Five rapid reopenings passed on the Redmi after the fix.

Copy is inventoried in [AURORA_COPY_REVIEW.md](AURORA_COPY_REVIEW.md): 610 resource/plural entries, with the eight founder-approved strings exact. All other copy remains proposed for founder review. The founder approved retaining the disclosed Free Always Block schedule fallback on 29 October.

Storage transactions now run on IO, preserving the existing mutex, strict decoding and error propagation. Native navigation fixtures use reduced motion; visual animation variants remain separate.

The native Focus test exposed IO starvation: stacked Today refreshes filled every IO thread with blocking usage-record reads, so storage writes (starting Focus) waited. Usage reads now run one at a time on a single IO lane, and Today skips a refresh while one is still measuring.

## Automated gates

| Gate | Result |
| --- | --- |
| Debug APK | PASS — `:app:assembleDebug` |
| Minified release APK | PASS — `:app:assembleRelease`; unsigned |
| Lint | PASS — `:app:lintDebug`, warnings treated as errors; existing baseline unchanged |
| JUnit / Robolectric | PASS — 175 tests, no failures, errors or skips |
| Roborazzi | PASS — record followed by independent verification; 40 baselines across light, dark, 200% text and reduced motion |
| Instrumentation test APK | PASS — `:app:assembleDebugAndroidTest` |
| Emulator instrumentation | PASS — 3 tests (onboarding, tabs, Focus start/end early) on `aurora_pixel` API 36, three consecutive runs |
| Diff whitespace | PASS — `git diff --check` |
| Source navigation graph | Updated with `graphify update .`; generated graph remains local |

Screenshot fixtures now use deterministic content, a named Photos fixture, loaded Insights and no fresh-event history writes. Obsolete generic Shield/Lockdown baselines were removed. Recordings were followed by a separate verify run, rather than treating recording as verification.

## Functional evidence

Earlier results below are retained from the selected handoff; completed work was not needlessly repeated. Continuation results were observed during this run.

| Flow | Coverage and evidence |
| --- | --- |
| Flexible | Earlier emulator custom 7-minute session: launcher/Recents/Back/repeated openings, turn-off and re-enable. Redmi schedule/Instagram blocking verified in continuation. |
| Always Block | Earlier emulator 30-minute commitment: no early-exit button, overlapping Flexible turn-off does not lift it. |
| Friction | Earlier emulator wrong/correct math, custom 1-minute access and re-shield while Contacts stayed open. |
| Cooldown | Earlier emulator persisted 1-minute wait across reopen, then chosen access. |
| Intent cooldown expiry | Earlier emulator foreground usage, post-use cooldown and a fresh decision after cooldown. |
| Intent Reminder expiry | Continuation: custom 1-minute YouTube use, app remained open, actual Reminder appeared; [capture](screenshots/aurora-reinvention/shield_intent_reminder_expiry.png). This replaces the misleading earlier file with the same name. |
| Schedule wizard | Continuation checkpoint: weekdays 09:00–17:00 Always Block on Clock; disabling the schedule kept its committed session; [capture](screenshots/aurora-reinvention/shield_schedule_disabled_still_committed.png). |
| Daily limit | Continuation: Contacts custom 1-minute limit uses measured usage and blocks once spent; [capture](screenshots/aurora-reinvention/shield_daily_limit.png). Midnight reset is covered by policy tests, not by waiting overnight. |
| Focus | Continuation: a Focus session blocked Contacts; ended early through confirmation; [capture](screenshots/aurora-reinvention/shield_focus.png). |
| App picker | Earlier emulator complete launchable list; continuation Redmi search found Instagram by name among 102 launchable apps and added it. |
| Onboarding | Shared navigation tests and native emulator smoke test; [light capture](screenshots/aurora-reinvention/onboarding_light.png). |
| Migration, precedence and time away | Policy/Rules/Migration/TimeAway regression suite, including the founder's legacy JSON fixture. |

Functional screenshots from intermediate builds establish the named behaviour; final appearance is shown by the current Roborazzi baselines and final preview captures.

## Redmi / HyperOS result

Device: Redmi 22041219I, Android 14, HyperOS V816. The founder unlocked the phone, authorized USB debugging and enabled the additional HyperOS debugging security setting. The current debug APK was installed with `adb install -r -t`. Existing data was preserved; no app-data reset or test instrumentation ran on this phone.

- Shield was enabled, bound and absent from Android's crashed-service list.
- Instagram was added through the real picker. The existing Study hours Flexible schedule then blocked its launcher opening with an attached, focused full-screen accessibility overlay. The short Flexible start flow was also completed without altering the existing schedule or daily-limit settings.
- An Instagram HTTPS deep link was also blocked.
- Five rapid Back/home/reopen cycles passed after the event-grace fix. Every Back reached Niagara launcher and every reopening showed Shield. This is a correctness check, not a calibrated latency benchmark.
- [Initial safe blocking capture](screenshots/aurora-reinvention/redmi_instagram_schedule_block.png) contains Aurora's overlay only. Founder backups remain private and uncommitted.
- The phone's temporary portrait rotation lock was restored to its original free mode.

The old background-activity-start failure is therefore resolved for these real-phone opening paths. This does not establish long-term OEM process survival, every notification entry point or all Android versions.

## Visual delivery

The five saved references are in [aurora-design-reference](aurora-design-reference/README.md). Native screens retain real data, explicit mode semantics and exact approved copy. Original mountain/lake dawn art is used beneath native text and controls; it is not a screenshot used as an interface. Asset, provenance and the complete ImageGen prompt are in [BRAND_SYSTEM.md](BRAND_SYSTEM.md#landscape-asset-and-implementation).

Reviewed the current light/dark baselines and 200% text layouts. Headlines grow with content; Shield and setup flows scroll. Focus retains a real native progress arc. Protection and progress remain separate. Decorative scenery is not presented as measured progress. The reference's sample graphs/numbers are not fabricated in the real interface. No pixel-perfect identity claim is made.

Foreground seeding uses one prior day. An uninterrupted foreground session spanning multiple days would need an older seed; Android retention also limits historical measurement.

## Separate launch gates

These need external accounts, hardware coverage or longer runs and are not represented as completed by this local build:

- Play Console subscription products, license-tester purchase/restore/renewal/refund flows, signing and accessibility-policy approval; server verification before a commercial launch.
- OEM reboot/unlock and extended idle/process-kill reliability across a broader device matrix.
- Calibrated event-to-visible latency measurements, notifications, split-screen, PiP, cooperative media behaviour and full TalkBack review on real devices.
- Current signed-in browser/Shorts/Reels compatibility. Web & content remains optional and experimental where stated.
- Founder review of proposed copy beyond the eight already approved strings.

Android can still revoke, force-stop or uninstall an accessibility app. Aurora states these limits; it does not claim device-owner control.
