# aurora: the complete product (feat/aurora-complete)

This document describes the full product built on `feat/aurora-complete`, on top of the Phase 3
Shield (`feat/phase-3-shield`, b631b04). It covers what each feature does, how it is enforced, how it
was verified, and what still depends on things outside this repository. `SHIELD.md`, `BRAND.md` and
`ARCHITECTURE.md` remain the detailed references for their areas.

Status language used below:

- **Implemented and tested**: the behaviour exists and unit, Robolectric or screenshot tests cover it.
- **Verified on emulator**: also exercised by hand on the API 36 emulator (2026-10-29).
- **Not end-to-end verified**: implemented, but the path couldn't be exercised here.
- **External dependency**: needs Play Console, a physical phone, policy review or credentials.

## 1. Feature verification matrix

| Feature | Status | Tests | Known limitations |
| --- | --- | --- | --- |
| Onboarding (8 saved steps) | Implemented, verified on emulator | `NavigationTest`, screenshots | Step is saved in preferences; choices are saved as they're made |
| Today with Aurora Light | Implemented, verified on emulator (light and dark) | screenshots | Light is drawn from XP history only; a first day is intentionally dark |
| Shield, four modes + RulesOnly | Implemented, Friction verified on emulator | `RulesTest`, `PolicyTest`, `InterceptorTest` | Flexible/Cooldown/Block not hand-tested; same code path as Friction |
| Friction pause that can't be skipped | Implemented, verified on emulator | `RulesTest.aPauseContinues…` | Pause start persists 5 min; leaving and returning continues it |
| Timed access (5/15/30 min) | Implemented, verified on emulator (grant opens the app, stays open, Shield returns at expiry) | `RulesTest`, `InterceptorTest` | Re-check timing on phones in deep sleep unmeasured |
| Intent (reason, alternative, reflection) | Implemented, rendered in screenshots | `RulesTest.intentAppsAreLimitedOnFree`, screenshots | Not hand-tested on the emulator |
| Cooldown | Implemented | `RulesTest.cooldownFollowsTheGrant…` | Not hand-tested |
| Lockdown with daily Free budget | Implemented, verified on emulator (overrode an active grant) | `RulesTest`, `PlanTest` (budget) | No in-app early exit; Android can still turn Shield off (by design) |
| Focus with profiles, enforced by Shield | Implemented | `NavigationTest`, `RulesTest.lockdownAndFocusInTheSnapshot` | Free has 1 profile |
| Schedules (weekdays, overnight, DST) | Implemented | `RulesTest` (overnight, DST in Europe/London) | Not hand-tested |
| Daily limits | Implemented | `RulesTest` | Needs Usage access; re-measured at least once a minute while the app is open |
| Groups | Implemented | `RulesTest.snapshotEnforces…` | Deleting a group points its rules back to "everything" |
| Insights (today, 7 days, 30 days on Plus) | Implemented, verified on emulator (no usage access state) | screenshots | Android keeps usage events for a limited time; missing days show "—" |
| Goals | Implemented | `ProgressTest.goalsAreHonest…` | "Under" goals count as met only once the day is over |
| XP, levels, steady days | Implemented | `ProgressTest` | Capped daily; no XP for opening aurora or ending Focus early |
| Website blocking | Implemented, **not end-to-end verified** | `WebTest` | Separate opt-in service; 8 named browsers only (section 6) |
| Shorts / Reels exit | Implemented, **experimental, not verified** | none on device | View IDs change with app updates |
| Free / Plus entitlements | Implemented and tested | `PlanTest`, `EntitlementTest`, `RulesTest` | Paused items are kept and chosen by the person |
| Google Play Billing | Implemented, **external dependency** | `EntitlementTest` (pure parts) | Billing service didn't bind on this emulator; no products exist in Play Console |
| Settings, data controls | Implemented, verified on emulator | screenshots | "Delete all data" keeps Play purchases (they're Google's) |
| Permission diagnostics | Implemented, verified on emulator | `AccessTest` | `Blocked` still not detected |
| Notifications | Not implemented | — | Settings says so honestly |

## 2. Rule precedence

`decide()` in `protect/Policy.kt` applies these in order; the first that matches wins.

1. No package → `Invalid`. Configuration unknown or unreadable → `Unavailable` (shield nothing, report `ConfigError`).
2. Exempt (aurora, system UI, Settings, installers, phone, emergency, this phone's launcher/keyboard/dialer) → `Exempt`.
3. Not an *active* protected item → `Allow`.
4. **Lockdown** covering the item → Shield, no way through.
5. **Schedule** window covering the item → Shield, no way through.
6. **Focus** session covering the item → Shield, no way through.
7. **Daily limit** spent → Shield until midnight, no way through.
8. **Grant** the person chose → allowed until it ends.
9. **Cooldown** running → Shield until it ends.
10. **Mode**: Flexible / Friction / Cooldown → Shield with a way through; Block → Shield with none; RulesOnly → allowed.

A grant is checked *after* every timed restriction, so it can never lift a Lockdown, schedule, Focus
session or spent budget. Verified on the emulator: a Clock grant made at 02:35 did not open Clock
during a Lockdown started at 02:37. `nextChange()` returns the next moment a decision can change, and
the interceptor re-checks the app in front then (and at least every 60 s).

The plan never reaches `decide()`. It decides which items, schedules, limits, Intent notes and profiles
are *active* before the snapshot is built, so Free and Plus get the same enforcement strength.

### Modes, precisely

| Mode | Each opening | After continuing |
| --- | --- | --- |
| Flexible | Shield, continue at once | Grant for the chosen minutes |
| Friction | Shield with a pause (5–30 s); continue only after it | Grant |
| Cooldown | Shield, continue at once | Grant, then the item rests for its cooldown (15 min–2 h) |
| Block | Shield, close only | — |
| RulesOnly | Nothing | — (schedules, Focus, Lockdown and limits still apply) |

Intent adds the person's reason and alternative to the Shield and a reflection pause; the pause is the
longer of Friction's and Intent's. Friction spaces out every opening; Cooldown spaces out sessions.

## 3. Persistence

One DataStore Preferences file (`aurora`), as before:

| Key | Contents | If unreadable |
| --- | --- | --- |
| `protected_items` | Phase 3 list (unchanged format) | `ConfigError`, nothing shielded, nothing overwritten |
| `rules` | JSON: per-item rules, groups, schedules, limits, profiles, goals, motivations, content toggles | Same as above (strict) |
| `runtime` | JSON: grants, pauses, cooldowns, Focus, Lockdown, Lockdown ledger | Starts over |
| `history` | JSON: daily counts, Focus records, counted Lockdowns (400 days) | Starts over |
| `entitlement` | JSON: what Play last confirmed and when | Treated as Free |

JSON is kotlinx.serialization with unknown keys ignored, so older versions read newer data. Values are
held in memory (`data/Store.kt`) and every write updates memory as soon as it's stored, so the Shield
never reads storage on the event path and sees a grant the moment it is written.

Items saved before modes existed use the default mode (Friction, 10 s) until the person changes them.

## 4. Lifecycle and recovery

| Event | Behaviour |
| --- | --- |
| Activity recreation, process death | All state is stored; sessions are timestamps |
| Reboot | Sessions continue on the wall clock; Android rebinds Shield some time after unlock |
| Wall-clock change | Focus and Lockdown count on `elapsedRealtime` within a boot (`Session.elapsed`), so changing the clock neither ends nor extends them |
| Time zone / DST | Schedules are computed from local date-times each decision; windows keep their clock times |
| Midnight | Daily limits reset (`nextMidnight`); a Lockdown counts toward the day it started |
| Grant ends while the app is open | The interceptor re-checks at `nextChange` and shows the Shield |
| Permission revoked | Status changes on the next resume; nothing claims protection |
| App update | Emulator: Android kept Shield enabled on most updates; once it turned it off. The UI reported it truthfully |
| Uninstalled protected app | Pruned when Protect opens, freeing its slot |
| Force-stop | Android may disable the service; aurora doesn't work around it |

## 5. Permissions and privacy

The release manifest requests only `PACKAGE_USAGE_STATS` (special access, granted by the person),
`com.android.vending.BILLING`, and AndroidX's internal receiver permission. **There is no `INTERNET`
permission.** Play Billing 8+ brings Google's `datatransport` library, which adds `INTERNET` and
`ACCESS_NETWORK_STATE` to upload billing-library diagnostics; aurora removes both in its manifest.
Purchases still flow through the Play Store app over IPC. This must be confirmed with license testers
before launch (section 8). If it breaks purchases, the fallback is to keep the permissions and update
the privacy copy and the Play data-safety form.

Two accessibility services, each turned on only by the person after an in-app disclosure:

- **aurora Shield** (`canRetrieveWindowContent=false`): reads the package in front. Unchanged from Phase 3.
- **aurora Web & content Shield** (optional): `canRetrieveWindowContent=true`, limited by `packageNames`
  to 8 browsers plus YouTube and Instagram. Reads the address field and view IDs. Nothing is stored.

Settings › Privacy & data shows what is stored, clears history, and deletes everything.

## 6. Websites and content

- Enforcement reads the address bar of: Chrome (and Beta), Samsung Internet, Firefox, Edge, Brave,
  Opera, DuckDuckGo. Not covered: other browsers, in-app browsers (Custom Tabs inside other apps were
  not evaluated), incognito tabs that a browser hides from accessibility, and typing in the address bar.
- A domain matches itself and subdomains (`WebTest`).
- No VPN, DNS filtering or certificate interception is used. A local VPN would cover all browsers but
  needs a VPN consent dialog, a foreground service and a Play policy review; it was not built.
- Shorts and Reels: when the person enables it, the service goes back once when those surfaces' view
  IDs appear. Marked experimental in the UI. Not tested against real app versions here (no signed-in
  YouTube or Instagram on the emulator).

## 7. Entitlements and billing

- Free: 5 protected items (apps + sites), 3 Intent apps, 3 daily limits, 1 schedule, 1 Focus profile,
  120 Lockdown minutes a day, Today and 7-day Insights. Plus: unlimited, plus 30-day Insights.
- Over the limit (downgrade): the oldest stay active, the rest show "paused"; nothing is deleted, and
  resuming a protected item is the person's explicit choice.
- Billing (`plan/Billing.kt`): Play Billing 9.1.0, one subscription product
  (`BuildConfig.PLUS_PRODUCT_ID`, default `aurora_plus`, override with `-Paurora.plusProductId=`) with
  monthly and yearly base plans. Prices shown are Play's formatted prices. Pending purchases never grant
  Plus. Purchases are acknowledged. "Restore purchases" re-queries Play. Plus is cached for 7 days
  offline. There is no fabricated purchase and no debug unlock.
- Not done: server-side purchase verification (Real-time developer notifications / Play Developer API).
  Client-only checks unlock features but are not proof against a modified APK.

## 8. External requirements before launch

| Needs | What |
| --- | --- |
| Physical Android phones | G1 latency (target ≥ 90% of opens shielded within 300 ms), OEM behaviour, every entry point (Recents, notifications, split-screen, PiP), TalkBack, reboot recovery |
| Play Console | Create `aurora_plus` with `monthly` (₹149) and `yearly` (₹1,199) base plans; license testers; verify purchase, pending, cancel, expiry and restore with network permissions removed |
| Policy review | AccessibilityService declaration for both services (non-accessibility-tool use), data-safety form, prominent disclosures (in-app text is ready) |
| Signing | Upload key and Play App Signing; release builds here are unsigned |
| Server (optional but recommended) | Purchase verification and RTDN |
| Real apps | Shorts/Reels view IDs on current YouTube and Instagram builds; address-bar IDs on current browser builds |

## 9. Emulator validation (2026-10-29)

API 36 emulator (`sdk_gphone64_arm64`, SwiftShader), debug build installed with `adb install -r` over
the existing build (no data cleared). Shield was turned on through aurora's disclosure and Android's
Accessibility screen by hand-driven taps. Screenshots: `docs/screenshots/emulator-2026-10-29/`.

| Check | Result |
| --- | --- |
| Phase 3 data carried over (Clock protected) | Pass |
| Status: off → "Turn on Shield"; enabled → "Active" | Pass |
| Clock opens a Friction Shield; 10 s pause; then 5/15/30 min choices | Pass |
| Choosing 5 min opens Clock; reopening within the grant stays open | Pass |
| Lockdown started from Today; Clock shows "Clock is locked" despite the active grant | Pass |
| Close app goes home | Pass |
| Insights without usage access: honest empty state plus aurora's own counts | Pass |
| Dark theme Today | Pass |
| Onboarding 8 steps, motivation and app choices saved | Pass |
| Plus screen with Play unavailable: honest message, no prices invented | Pass |
| 5-minute grant at 03:01:16 with Clock left open; Shield back in front by 03:06:42 without any new window event | Pass |

Notes from the run:

- `uiautomator dump` suppresses accessibility services while it runs; Shield then (truthfully) reports
  "isn't running". Device tests of Shield must not use UiAutomation-based tooling during measurement.
- Cold start of the debug build took about 6.7 s on this emulator, which was itself dropping frames.
  A branded loading surface and the Android 12 splash now cover the wait. Release-build startup on a
  phone is unmeasured.
- The Play Billing service did not bind on this emulator (`SERVICE_DISCONNECTED`), before and after
  the permission change.

## 10. Architecture additions

```
protect/Policy.kt     decide(), nextChange(), TimedBlock, ShieldReason (pure)
protect/Rules.kt      Rules, AppRule, Mode, IntentNote, Group, Schedule, DailyLimit, FocusProfile,
                      Session (monotonic clock), Runtime (grants, pauses, cooldowns), policyState() (pure)
protect/Web.kt        address parsing, domain matching, content view IDs (pure)
protect/ContentService.kt  the optional Web & content accessibility service
progress/Progress.kt  DayStats, History, XP, levels, Aurora Light, goals (pure)
plan/Entitlement.kt   purchase evaluation and offline plan (pure)
plan/Billing.kt       Play Billing adapter
data/Store.kt         JSON values in DataStore, kept in memory
Actions.kt            grant, pause, Focus, Lockdown, settle, delete-all
ui/protect/           Protect tab, item page, schedules, limits, groups, Lockdown, websites, app picker
ui/plus/              aurora plus
ui/components/Light.kt  Aurora Light
```

Still no DI framework, no database and no network client. Total main source: about 8,900 lines.
