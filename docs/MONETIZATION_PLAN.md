# Monetization: audit, gap analysis and plan (2026-11-10)

Scope: account-linked 14-day trial, device-wide Free quotas, Plus subscriptions (monthly, annual), US Founding
Lifetime (1,000 slots, both stores), anti-abuse, paywalls. This file is the plan; each stage below updates it with
what was built and what was actually tested.

## 1. What exists (verified by reading the code, 2026-11-10)

| Area | State | Where |
|---|---|---|
| Backend | Cloudflare Worker + D1, no dependencies. Firebase ID tokens (verified email required), revocation check, RSA-signed entitlement snapshots, 5 Plus seats per account, rate limits, 30-day deletion grace, daily housekeeping cron. 85/85 tests pass (`npm test`, node:sqlite stand-in for D1). | `server/` (branch `feat/device-quota-anti-abuse`, partly uncommitted) |
| Google Play billing (server) | `subscriptionsv2` verification, RTDN via Pub/Sub token, account binding via `obfuscatedExternalAccountId`, token hashing, conflict detection, transfer route. Subscriptions only. | `server/src/worker.js` (`verify`, `rtdn`) |
| App Store billing (server) | JWS chain validation pinned to Apple G3, App Store Server API status read, Notifications V2, `appAccountToken` binding, sandbox isolation, transfers disabled. **Not merged**: lives on `feat/app-store-backend` (worktree `~/Desktop/aurora-backend-staging`, whose `.git` pointer is broken since the repo moved into `Desktop/Aurora /`). | `feat/app-store-backend`: `server/src/apple.js`, migration `0004_app_store.sql` |
| Device-wide Free quota | Two ledgers (account + installation), 120 min/day for Cooldown + Always Block (= the 7,200 s strict allowance), atomic dual-revision commit, idempotency keys, signed time-boxed authorizations, carried targets across account switches, server clock decides. | `server/src/quota.js`, `worker.js` `reserve`; Android `plan/CommittedQuota.kt`, `mergeCarried` |
| Device proof | Keystore (Android) / Keychain P-256 (iOS) key, TOFU-bound per install id, signs path + token hash + body hash. | `server/src/device.js`, `DeviceCredential.kt`, iOS `Account.swift` |
| Play Integrity | Classic token on server nonce, decoded server-side, one-use nonce, package + cert pin, verdict kept 7 days. Flag `INTEGRITY=off` in production config. | `device.js`, `worker.js` `attest` |
| Free limits (client) | 5 Shield targets, 3 Intent apps, 1 active Goal, 120 committed min/day. Plan from signed snapshot only. | Android `plan/Plan.kt`, `EntitlementPolicy.kt`; iOS `Shared/Domain/Plan.swift` |
| Client offline rule | Snapshot valid 7 days; `!autoRenewing && now >= paidThrough` ⇒ Free; clock set before issue ⇒ Free. Same on both platforms. | `plan/Entitlement.kt`, `Entitlement.swift` |
| Android billing client | Billing Library 9.1.0, SUBS only, acknowledges only after server confirmation, pending never verified. | `plan/Billing.kt` |
| iOS billing client | StoreKit 2, subscriptions, server-signed snapshot only. 7 StoreKit scenario tests intermittently fail (empty product list; see `aurora-ios/docs/STABILIZATION_REPORT.md`). | `Aurora/Plus/Billing.swift` |

## 2. Gaps against the requested configuration

| # | Requirement | Gap |
|---|---|---|
| G1 | One billing source of truth | Play and App Store backends are on two diverged branches. Must be merged before anything else. |
| G2 | 14-day no-card trial, backend-granted | Nothing exists. No `trial` concept on server or clients. |
| G3 | One trial per account, survives deletion/recreation | Account rows are purged after 30 days; nothing remembers a trial. Needs a claim record keyed by a keyed hash of the normalized verified email and Firebase UID that outlives the account. |
| G4 | One trial per device | Only the install id exists, which resets on reinstall. Android: Device Recall not integrated (beta, needs approval). iOS: DeviceCheck / App Attest not integrated. |
| G5 | Lifetime (US, $89.99, 1,000 slots, both stores) | Nothing exists. Play verification is `subscriptionsv2` only; Apple side allows only auto-renewables. No inventory. |
| G6 | Pricing | Docs say ₹149 / ₹1,199 and "no lifetime plan". Requested: ₹199 / ₹999; $14.99 / $99.99; $89.99 lifetime US. Prices live in the store consoles; clients already show store prices. Docs and console change only. |
| G7 | Effective entitlement with precedence and states | Server picks "best paid-through purchase". No lifetime, trial, revoked states, no audit trail of transitions. |
| G8 | Webhook dedupe / replay | RTDN orders by `eventTimeMillis` only; no event-id dedupe table. Apple branch has its own. |
| G9 | Paywall screens | Trial activation, countdown, expiry, lifetime offer, sold-out, eligibility-denied screens don't exist on either platform. |
| G10 | Monitoring | `abuse_events` only; no alerting on inventory, webhook failures or entitlement anomalies. |
| G11 | Physical device evidence | None recorded for billing. Emulator/simulator only. |

Already satisfied, keep as is: device-wide 7,200 s quota and 5-target cap across accounts (G-quota), clock rollback
handling on the server, idempotent reservations, signed snapshots, acknowledgement only after server verification.

## 3. Design

### 3.1 Effective entitlement (server only)

Computed on every read from durable rows; no stored "is plus" flag.

```
REVOKED (admin/fraud hold)  > LIFETIME_PLUS > SUBSCRIPTION_PLUS > TRIAL_PLUS > FREE
```

- `SUBSCRIPTION_PLUS`: production purchase row, store state active / grace / canceled, `paid_through > now`.
- `LIFETIME_PLUS`: committed lifetime slot or verified non-consumable, not refunded/revoked.
- `TRIAL_PLUS`: `trial_claims` row for the account with `granted_at <= now < expires_at`.
- `EXPIRED` and `ACCOUNT_HOLD` are display states that resolve to Free.
- Seat rule (5 installations) applies to every source.

Snapshot stays wire-compatible: `tier: 'plus'`, plus `source` (`trial|subscription|lifetime`), `trialEndsAt`.
Trial: `autoRenewing=false, paidThrough=expires_at`, so **already-shipped clients end the trial offline on time**.
Lifetime: `autoRenewing=false, paidThrough=253402300799000` (year 9999).

### 3.2 Trial claim (atomic)

`POST /v1/trial/claim` (fresh sign-in, verified email, proved device key, rate limited, idempotent per account):

1. Account eligibility: no `trial_claims` row for the account, the UID hash, or the email hash (HMAC with a server
   secret over the normalized address; Gmail dots/plus normalized).
2. Device eligibility (see 3.3). Inconclusive ≠ abuse; the policy is explicit per verdict.
3. One D1 batch: `INSERT ... trial_claims` guarded by `UNIQUE(account_id)`, `UNIQUE(email_hash)`, and
   `UNIQUE(device_ref)` per evidence kind; the losing racer gets the winner's row back (idempotent) or `409`.
4. Expiry = server `now + 14 × 86,400,000 ms`. Never recomputed. Reinstall/new device returns the same row.

Claims are kept after account deletion (account link set NULL), retaining only the two hashes and the device refs.
Retention period and privacy-policy wording are decisions for the founder (section 6).

### 3.3 Device eligibility signals

| Platform | Signal | Survives | Use |
|---|---|---|---|
| Android | Install id + Keystore key | Nothing beyond the install | Same-install dedupe only |
| Android | Play Integrity standard verdict | n/a | Gate: trial needs `MEETS_DEVICE_INTEGRITY` + `PLAY_RECOGNIZED` + `LICENSED` |
| Android | **Device Recall** bit "trial claimed" | Reinstall and factory reset (Google, beta) | Hard device gate, **only after Play approves access**; flag `DEVICE_RECALL=off|shadow|enforce` |
| iOS | DeviceCheck bit "trial claimed" | Reinstall (persists until we reset it) | Hard device gate; server-to-server with a DeviceCheck key |
| iOS | App Attest assertion | Not an identity | Genuine-app check on the claim request |
| iOS | Keychain record | Usually reinstall, not erase | Supplementary only |

Without Device Recall approval, Android "reinstall + new verified email ⇒ second trial" stays **open** and is only
rate-shaped by the Integrity gate and `many_accounts` detection. This is documented, not hidden.

### 3.4 Lifetime inventory (global, both stores)

- `lifetime_inventory(cap=1000, committed)` single row; `lifetime_slots(slot_no UNIQUE CHECK 1..cap,
  purchase_key UNIQUE, account_id UNIQUE)`.
- Before the store sheet: `POST /v1/lifetime/reserve` holds a slot for 15 minutes (`lifetime_holds`, counted against
  the cap). No hold ⇒ the app does not show the purchase button ("sold out" state).
- After verified purchase (Play `purchases.products` or Apple JWS, storefront `USA`/region `US`): one batch commits
  the slot iff `committed < cap`, keyed by the purchase. Duplicate verification ⇒ same slot.
- Store checkout and the ledger can't be one transaction. A verified purchase that arrives with no slot is
  **over-cap**: handled by the policy chosen in section 6 and alerted.
- Refunded slots stay consumed (not recycled). At `committed = cap` the API reports sold out, and the console product
  must be deactivated (manual step).
- Country: Play `regionCode` on the purchase / product availability limited to US; Apple `storefront == "USA"`.
  Never IP or locale.

### 3.5 Webhooks

`store_events(store, event_id PRIMARY KEY)` dedupe for RTDN (`messageId`) and Apple (`notificationUUID`), then the
existing "re-read from the store" handlers. One-time product RTDN (`oneTimeProductNotification`,
`voidedPurchaseNotification`) added for lifetime refunds/revocations.

## 4. Stages

| Stage | Content | Verification here |
|---|---|---|
| 0 | Commit current work; merge `feat/app-store-backend` into the backend; fix migration order (0004 after 0003). | Full server suite green |
| 1 | Migration 0005 (trials, lifetime, store events, entitlement audit); effective-entitlement function; trial claim + snapshot fields. | New server tests: first trial, 100 accounts/one device, race, reinstall, deletion/recreation, expiry, old-client compatibility |
| 2 | Lifetime inventory: reserve/commit, Play one-time verification, Apple non-consumable, refunds, RTDN/Apple notifications, over-cap path. | Tests at slots 999/1000/1001, 2,000 concurrent cross-platform claims, duplicates, refunds |
| 3 | Device signals: Play Integrity standard request for trial, DeviceCheck server client, Device Recall behind flag. | Fake-Google/Apple HTTP tests only; real calls need credentials |
| 4 | Android client: trial onboarding, countdown, lifetime INAPP product, paywall states. | Unit + Robolectric + screenshot tests; emulator |
| 5 | iOS client: same, plus DeviceCheck token, App Attest, non-consumable. | XCTest on simulator |
| 6 | Docs, store setup, monitoring queries/alerts, rollback. | — |
| 7 | Physical-device and store-sandbox matrix. | **Needs the founder's devices and console access** |

## 5. What cannot be guaranteed

- No uninstall/reset-proof device identity exists on Android without Device Recall; Aurora will not read hardware
  identifiers (Play User Data policy; see `ANTI_ABUSE.md` §10).
- A modified client can enforce whatever it likes on its own phone. The server still refuses it paid entitlements
  and Free reservations beyond the ledgers.
- The lifetime cap can be exceeded by purchases already in flight at the instant the last slot is taken; section 6
  decides what happens to them.
- Apple does not let developers refund App Store purchases.

## 6. Decisions needed from the founder

Recorded here once answered.

| Decision | Answer (founder, 2026-11-10) |
|---|---|
| Branching | WIP committed on `feat/device-quota-anti-abuse`; work continues on `feat/monetization`, with `feat/app-store-backend` merged in. Nothing pushed. |
| Gold | Keep the 2026-11-08 rule: gold on Aurora Plus surfaces via `ui/components/Plus.kt`, plus the logo. Nowhere else. |
| Lifetime over-cap | Honor it: grant lifetime, record as overflow (no numbered slot), alert, deactivate the store product. |
| Android trial gate | ~~Strong Play Integrity verdict alone.~~ Superseded (second decision, same day): **no Android trials until reinstall-resistant device eligibility is verified.** Play Integrity proves a genuine app and device, not that this phone had a trial before. The server refuses Android claims unless `DEVICE_RECALL = "enforce"`, and refuses them when Google can't recall the phone. |
| Account age | No account-age rule. Any verified account that never had a trial or a paid purchase can claim one, subject to the account and device checks. |
| Lifetime definition | "One payment. Aurora Plus access for the lifetime of the Aurora Plus service. No recurring subscription fees. Access ends if Aurora permanently discontinues the service." |
| Trial data retention | Not decided. The 3-year figure is a placeholder; see `PRIVACY_TRIAL_RETENTION_REVIEW.md`. `TRIAL` stays off until it is decided. |
| iOS WIP commit | Keep `3e363e2` as it is (no reset, no rewrite). It was made without authorization; section 13 lists its contents. |

## 7. Status (updated 2026-11-10, second pass)

Nothing is pushed, deployed, migrated remotely or switched on. All flags still ship `off`. **The system is not
production-ready.** Section 12 lists the open critical items. Nothing on a real device or a real store sandbox has been
verified.

| Stage | State | Commits |
|---|---|---|
| 0 Merge | Done. `feat/monetization` = anti-abuse WIP + App Store backend. | aurora-android `28766fd`, `190d3c2` |
| 1 Trial (server) | Done. Second pass: account-age rule removed; Android claims need `DEVICE_RECALL = "enforce"`; an unrecallable phone gets no trial; optional App Attest on iOS claims (`APP_ATTEST`). | `6137892` + this pass |
| 2 Lifetime (server) | Done. Second pass: Play voided purchases handled for subscriptions too (RTDN), plus a daily reconcile against the Voided Purchases API. | `0cb711f` + this pass |
| 3 Device Recall | Behind `DEVICE_RECALL`. Untested against Google (beta access not granted). | `48e6b24` |
| 4 Android client | Trial claim, lifetime purchase, Plus states. Second pass: onboarding trial page, Home countdown, approved lifetime wording, 30 lint errors fixed, Free target-cap bypass through carried sessions fixed. | `4fd0f7a` + this pass |
| 5 iOS client | DeviceCheck trial claim, lifetime, Plus states. Second pass: App Attest on the claim, onboarding trial page, Home countdown, approved lifetime wording, launch sweep of `Transaction.unfinished`. | aurora-ios `cf7af4b`, `6662936` + this pass |
| 6 Physical devices / store sandboxes | **Not started.** Section 11 is the checklist. | — |

Still not built: an admin view for `entitlement_events`; an Apple refund reconcile (only Apple's notifications, with
their retries, cover lifetime refunds); the trial-record erase script (`PRIVACY_TRIAL_RETENTION_REVIEW.md` §4).

## 8. Test record (second pass, executed here, 2026-11-10)

Three categories, kept apart on purpose.

**A. Implemented and covered by mocked tests (stand-ins for Google/Apple; nothing here talks to a real service).**

| Suite | Result |
|---|---|
| Server `npm test` (node:sqlite stand-in for D1; Google, Apple, DeviceCheck, App Attest faked at fetch or with a test CA) | 173/173 pass |
| Android `testDebugUnitTest` (JVM + Robolectric) | 734/734 pass |
| Android `lintDebug` | Pass: 0 errors (was 30) |
| Android screenshots | Recorded and reviewed: `home_trial_countdown_*`, `home_trial_last_day_*`, `onboarding_trial_offer_*`, plus the existing `plus_trial_*` |
| iOS `AuroraTests` (iPhone 18 Pro simulator, StoreKit test session) | 113/113 pass (was 109/110). `BillingScenarios` 35/35 over 5 iterations; it failed 3/3 before the fix |

New mocked coverage in this pass: any account age gets a trial; Android refused under `DEVICE_RECALL` off/shadow/unset,
including in `sync`'s offer; recall unevaluated ⇒ refused; App Attest chain, nonce, app id, counter, environment, key id,
credential id and expiry, each rejected alone; App Attest one-use nonce and a cross-device replay; voided-purchase sweep
across pages, idempotent on re-run, Google down; voided subscription with and without revocation; a carried Strict session
holds Free's five slots; trial countdown day arithmetic on both platforms; onboarding offer only when the server offers it.

**B. Implemented, not verifiable here.** App Attest on a device (the simulator has no App Attest or DeviceCheck);
Device Recall (no beta access); real Play/App Store purchases, refunds, RTDN and App Store notifications; the Voided
Purchases API's real response shape and quotas; D1 under load.

**C. Genuinely verified platform behaviour.** None in this pass. The only real-platform evidence is the Android and
iOS UI rendering in emulator/simulator screenshots.

## 9. What you need to do (credentials, consoles, review)

1. Play Console: subscriptions at the new prices (US, IN); one-time product `aurora_plus_lifetime_us`, US only,
   US$89.99; RTDN topic; link the Cloud project for Play Integrity; set `APP_CERT_SHA256`; apply for Device Recall. The
   service account needs the "View financial data" permission for the Voided Purchases API.
2. App Store Connect: `aurora_plus_lifetime_us` non-consumable, USA storefront only, Family Sharing off; subscriptions
   at the new prices; Server Notifications V2 URL `/v1/apple/notifications`; a DeviceCheck key and your Team ID. For
   App Attest: enable the App Attest capability on the App ID, add `com.apple.developer.devicecheck.appattest-environment`
   to the app's entitlements (`production` for TestFlight/App Store), and keep `DEVICECHECK_ENV` matching it.
3. Secrets: `TRIAL_PEPPER` (never rotate), `DEVICECHECK_KEY_ID`, `DEVICECHECK_PRIVATE_KEY`, App Store Server API key,
   Google service account. Var `APPLE_TEAM_ID`.
4. Approve or change the copy in section 12 and the retention period in `PRIVACY_TRIAL_RETENTION_REVIEW.md`.
5. Decide: do Play promo-code lifetime redemptions take a founding slot? (Today: yes. Only license testers are excluded.)
6. Rollout, each step on staging first: `wrangler d1 migrations apply` (0004 to 0006) → deploy → physical-device matrix
   (section 11) → `TRIAL = "on"` with `APP_ATTEST = "enforce"` (iOS only; Android stays off) → `LIFETIME = "on"` once
   both store products exist → `DEVICE_RECALL = "enforce"` only after the Android rows of section 11 pass.

## 10. Monitoring and rollback

- Alerts (Workers Logs / Logpush): `lifetime_attention` warnings (overflow or duplicate purchases); `request failed`
  errors on `/v1/trial/claim`, `/v1/verify*`, `/v1/rtdn`, `/v1/apple/notifications`.
- Daily queries: `SELECT kind, COUNT(*) FROM entitlement_events WHERE created_at > ? GROUP BY kind` (trial grants vs
  refusals by reason, lifetime slot/overflow/duplicate/revoked); `SELECT COUNT(*) FROM lifetime_slots WHERE slot IS NOT
  NULL`; `SELECT open FROM lifetime_inventory`; `abuse_events` kinds `recall_shadow`, `recall_write_failed`.
- When `lifetime_inventory.open` turns 0: deactivate both store products by hand the same day.
- Rollback: set `TRIAL`/`LIFETIME`/`DEVICE_RECALL` to `off` and deploy, or `wrangler rollback`. Never drop 0005/0006
  tables: granted trials and lifetime purchases live there. Turning `TRIAL` off stops new claims only; granted trials
  keep running to their end.

New alerts from the second pass: `voided_reconcile_failed` and `voided_reconcile_truncated` (daily cron), `app_attest_failed`
in `abuse_events`, and `subscription_voided` in `entitlement_events`.

## 11. Physical-device and store-sandbox checklist

Record the device, OS, build, account and result for each row. A row passes only when it is observed on real hardware
against the real store. Run against staging first.

### Android (Play internal testing track, a license tester and a non-tester account, at least two phones)

| # | Check | Expected |
|---|---|---|
| A1 | Fresh install, sign in, finish onboarding with `TRIAL=on`, `DEVICE_RECALL=off` | No trial page; Plus screen shows no trial offer; `sync` answers `trial.status = unavailable` |
| A2 | With Device Recall approved and switched on in Play Console: read the verdict of a phone that never had an Aurora trial | Record exactly what `deviceRecall.values` contains (`{}` or `bitFirst:false`). If it is `{}`, the server's rule refuses every first trial and must change before `enforce` |
| A3 | `DEVICE_RECALL=enforce`: claim on phone 1 | Granted; Google shows `bitFirst=true` within ~30 s on the next verdict |
| A4 | Uninstall, reinstall, new Google account and new Aurora account on phone 1 | Refused `device_used` |
| A5 | Factory reset phone 1, new accounts, claim | Refused `device_used` |
| A6 | Same Aurora account on phone 2 | Same trial and the same end date; no new claim |
| A7 | Play Integrity: rooted or unlocked phone, sideloaded APK, emulator | No trial (`device_ineligible` or `device_unverifiable`); Free and purchases still work |
| A8 | Free quota: use 90 strict minutes, uninstall, reinstall, same account | 30 minutes left |
| A9 | Free quota: use 120 minutes, uninstall, reinstall, **new account** | **Expected today: a fresh 120 minutes.** Documented limit (section 13.1); record it |
| A10 | Free targets: account A runs a Strict session on 5 apps, switch to account B with 5 apps on | At most 5 apps blocked; B's schedule waits |
| A11 | Subscribe monthly (tester), then cancel in Play | Plus until the period end, then Free; `autoRenewing=false` meanwhile |
| A12 | Refund the subscription from Play Console with "revoke" | Plus ends within minutes (RTDN); if RTDN is disabled, within one day (voided sweep) |
| A13 | Refund without revoke | Plus continues until the period end (Google's state) |
| A14 | Buy lifetime with a **license tester** | Plus granted; `lifetime_slots.status = 'test'`; slot count unchanged |
| A15 | Buy lifetime with a real US card on a non-tester internal account, then refund | Slot numbered; after the refund, Plus ends and the slot stays consumed |
| A16 | Buy lifetime from a non-US Play account | `overflow`, no number (and the product should not be visible there at all) |
| A17 | Restore: reinstall, same account | Plus back after sign-in, with no purchase prompt |
| A18 | Restore on another Aurora account | Conflict message with the masked owner address; no transfer |
| A19 | Pending payment (slow test card) | "Payment pending", no Plus, acknowledged only after server verification |
| A20 | Process death during the trial claim (kill the app after tapping Start) | On relaunch: either no trial and the offer is still there, or the same granted trial. Never two |

### iOS (TestFlight build, sandbox Apple Accounts, at least two iPhones)

| # | Check | Expected |
|---|---|---|
| I1 | App Attest capability and entitlement present; `APP_ATTEST=enforce`; claim trial | Server log shows the attestation verified; trial granted; DeviceCheck `bit0` set |
| I2 | Same iPhone, delete app, reinstall, new Aurora account | Refused `device_used` (DeviceCheck) |
| I3 | Erase All Content and Settings, new account | Record what DeviceCheck answers (Apple doesn't document it) |
| I4 | Second iPhone, same account | Same trial, same end |
| I5 | Home countdown on day 1, the day before the end, and the last day | "14 days left…", "1 day left…", "…ends today at <time>" |
| I6 | Trial end with the phone offline | Free at the end time (signed `paidThrough`) |
| I7 | Subscribe (sandbox), cancel in Settings → Subscriptions | Plus until the period end; then Free after Apple's notification or the daily recheck |
| I8 | Sandbox refund request (Xcode/App Store Connect refund testing) | Plus ends after the REFUND notification |
| I9 | Buy lifetime in sandbox | Shows "Sandbox review access" only; **no founding slot** (`status = 'test'`); production Plus unchanged |
| I10 | Restore purchases after a reinstall, same account | Plus back |
| I11 | Kill the app during purchase verification, relaunch | Launch sweep verifies and finishes the transaction; Plus granted once |
| I12 | Account deletion, then sign up again with the same Apple-bought subscription | Conflict (purchase bound to the deleted account's token); support path needed (section 13.4) |
| I13 | Lifetime offer on a non-US storefront | No lifetime button |

### Both

| # | Check | Expected |
|---|---|---|
| B1 | Same account: Android lifetime purchase, then the iOS app | iOS shows Plus (lifetime) |
| B2 | Two buyers race for the last slot (set `cap` to `committed + 1` on staging) | One numbered slot, one `overflow`; both have Plus; `lifetime_attention` alert fires |
| B3 | Every paywall: price, renewal terms, lifetime definition, Terms and Privacy links visible before purchase | Matches section 12 |

## 12. Trial and lifetime wording for approval (exact text, both platforms)

Shown verbatim from `res/values/strings.xml`. iOS reads the same table (`tools/gen_strings.py`), except where iOS
overrides it.

| Key | Where | Text |
|---|---|---|
| `trial_offer_title` | Plus screen, onboarding trial page | Try Aurora Plus free for 14 days |
| `trial_offer_body` | Same | No payment details, and nothing renews. After 14 days you're back on Aurora Free: everything you set up is kept, and Free's limits apply again. **(changed: added "and Free's limits apply again")** |
| `trial_start` | Button | Start free trial |
| `action_not_now` | Onboarding trial page, second button | Not now |
| `trial_badge` | Home countdown pill (upper-cased) | Free trial **(new)** |
| `trial_days_left` (plural) | Home countdown | %d day left in your Aurora Plus trial / %d days left in your Aurora Plus trial **(new)** |
| `trial_ends_today` | Home countdown, last day | Your Aurora Plus trial ends today at %1$s **(new)** |
| `trial_countdown_detail` | Home countdown, second line | Ends %1$s. Nothing is charged. **(new)** |
| `trial_active_title` / `trial_active_body` | Plus screen during the trial | Free trial · ends %1$s / Nothing is charged when it ends. Aurora Free continues unless you choose a plan. |
| `trial_ended` | Plus screen after | Your free trial ended on %1$s. |
| `trial_refused_used` | Refusal | This account or phone has already had a free trial. |
| `trial_refused_purchased` | Refusal | This account has had Aurora Plus before, so the trial isn't available. |
| `trial_refused_device` (Android) | Refusal | Aurora couldn't confirm this phone with Google Play, so the trial isn't available here. Aurora Free and Plus still work. |
| `trial_refused_device` (iOS override) | Refusal | Aurora couldn't confirm this iPhone with Apple, so the trial isn't available here. Aurora Free and Plus still work. |
| `trial_refused_other` | Refusal | The free trial isn't available right now. |
| ~~`trial_refused_not_new`~~ | — | **Removed** (no account-age rule) |
| `lifetime_price` | Lifetime button | Founding Lifetime · %1$s once |
| `lifetime_terms` | Under the button, before the store sheet | One payment. Aurora Plus access for the lifetime of the Aurora Plus service. No recurring subscription fees. Access ends if Aurora permanently discontinues the service. **(changed to the approved definition)** |
| `lifetime_scope` | Under the terms | Linked to your Aurora account. Available in the US to the first 1,000 buyers. **(new)** |
| `lifetime_sold_out` | Instead of the button | Founding Lifetime is sold out. |
| `lifetime_owned` | Plus card when owned | Founding Lifetime. Nothing renews and nothing to manage. |

Open wording questions:

- **Account deletion.** `account_delete_body` says deletion "doesn't cancel Aurora Plus". It does not say that a
  lifetime purchase stays with the deleted account and can't be moved automatically (section 13.4).
- **"first 1,000 buyers".** This is a scarcity claim. It is true and has no count or timer, but confirm you want it.
- **iOS Plus screen labels.** The paywall reuses Android-named keys (`plus_checking_google_play`,
  `plus_manage_in_google_play`, `plus_subscriptions_renew_until_you_cancel_…`, `plus_google_play`, `plus_unavailable`,
  `plus_google_play_is_waiting_…`). `IOSCopy` overrides all six with App Store wording (checked 2026-11-10).

## 13. Audit findings (second pass)

### 13.1 Free quota after reinstall

- **Same account:** holds. The account ledger keeps the day's minutes (mocked test: 90 used, then reinstall, leaves 30).
- **New account and reinstall: does not hold, on either platform.** The installation id lives in app storage (Android
  SharedPreferences, excluded from cloud backup; iOS App Group defaults), and an uninstall erases it. The new
  installation's ledger is empty, so a new account on a reinstalled phone gets a fresh 120 minutes. Without Device Recall,
  Android has no reinstall-proof signal. Device Recall and DeviceCheck hold 2–3 bits, not a minute count, so they can't
  carry a quota. This is a documented limit, not a bug. Mitigations already present: rate limits per installation, and
  `many_accounts` audit events.
- **Account switching without reinstall:** holds (device ledger, both enforced when `DEVICE_QUOTA=enforce`; production
  config is `shadow` today).

### 13.2 Five-target Free cap across account switching

- **Bug found and fixed (Android).** A Strict or Cooldown *session* (manual start, or a legacy schedule window) carried
  from account A didn't reserve Free's slots, so account B's own five stayed active: **10 targets enforced on Free**
  (`DeviceWideLimitsTest.aCarriedStrictSessionAlsoHoldsFreesSlots` failed with 10). The fix is in
  `Runtime.carriedShield`: every live session's targets now hold slots first. iOS has no session list, so it is not
  affected.
- Configuration runs were already correct. Flexible protection is dropped on switch.
- After a reinstall the old installation's protections are gone, so nothing multiplies at the same moment.

### 13.3 Restoration, cancellation, refunds

- Restore (both stores) re-verifies with the server. Ownership comes from `obfuscatedExternalAccountId` (Play) or
  `appAccountToken` (Apple). Another account gets a conflict with the masked owner address. Transfers are disabled.
- Cancellation: Play `CANCELED` and Apple auto-renew off keep Plus until the period end. Covered by existing tests.
- Refunds: Play now has two paths, RTDN `voidedPurchaseNotification` (subscriptions and lifetime) and the daily Voided
  Purchases sweep. Apple has notifications only, which Apple retries for 3 days. **Gap:** no Apple refund-history
  reconcile, so a lifetime refund whose notification fails all retries stays Plus. Fix with the App Store Server API
  "Get Refund History" in the daily cron.
- Trial: revocation by support sets `revoked_at`; Plus ends immediately.

### 13.4 Account recovery

- Same sign-in (same Firebase UID): everything returns, including trial, purchases and lifetime.
- **Deleted account, then a new account:** the purchase is bound to the old UID through both stores' account tokens,
  so the new account gets a conflict. A lifetime buyer who deletes their account loses Plus and has no self-service way
  back. This needs a support runbook and disclosure in the deletion text (section 12).
- Lost email access: governed by Firebase sign-in recovery. Nothing billing-specific.

### 13.5 Lifetime inventory races

Mocked tests cover slots 999/1000/1001, 2,000 concurrent cross-store commits, 40 racing buyers for 20 slots, duplicate
verification and deletion (deletion never frees a slot). Read-through findings:

- Numbered slots can't exceed the cap (`UNIQUE(slot)`, `CHECK`, and decide-and-write in one statement).
- Holds are advisory. A purchase that arrives without a live hold (a hold expired during a slow sheet, a pending payment
  completing later, a restore elsewhere) can take the last numbered slot ahead of someone who holds one. The holder then
  gets `overflow`: still lifetime, no number. This is honored by policy, and it is alerted.
- A refund followed by a new purchase from the same account takes a second numbered slot (refunded slots are never
  recycled). This is low risk; to prevent it, change the duplicate check to include refunded rows.
- `grantLifetime` binds the purchase before numbering. If numbering throws, Plus is granted with no slot row until the
  next verify of the same purchase. That self-heals on any re-verify but undercounts until then.

### 13.6 Sandbox and test transactions

- Apple: any `environment != Production` (Sandbox, TestFlight) ⇒ `test`, never a slot. `Xcode` is refused outright.
  Covered by a mocked test.
- Play: `purchaseType == 0` (license tester) ⇒ `test`. **Promo-code redemptions (`purchaseType == 1`) take a real
  founding slot today.** This is your decision (section 9.5).
- Play internal-testing purchases by non-testers are real charges and correctly take slots.

### 13.7 Intermittent iOS purchase test: root cause and fix

`testASignedAnswerIsStoredAndTheTransactionFinished` failed 1/3 alone, 3/3 in its class and 5/5 under other setups.
Instrumented runs showed that `Billing.handle` did call `finish()` on the purchased transaction. Yet the same ID stayed in
`Transaction.unfinished` beyond 2.5 s, while calling `finish()` on the copy read back from `Transaction.unfinished`
cleared it within 500 ms. Transaction IDs were unique in those runs, so ID reuse after `clearTransactions()` was ruled out.

Fix: `Billing.start()` now sweeps `Transaction.unfinished` at every launch, which is also Apple's guidance. A dropped
finish, or a verification that got no answer, is finished on the next start, and verification is idempotent. The test
now runs that sweep, as the next launch would, before checking. Whether StoreKit drops the finish on a real device is
unverified (row I11).

### 13.8 Android lint (30 errors, all pre-existing)

- 19 unused strings removed.
- 2 strings that only iOS uses are kept and marked (`start_review_cooldown`, `detail_type`).
- `pass_left_of` marked as not a plural ("left" doesn't inflect).
- `GlassWindow` → `glassWindow` (a composable that returns a value).
- A redundant `SDK_INT >= 31` check removed.
- Two `LocalContext.getString` calls moved to `stringResource`.
- `SessionHero`'s modifier parameter moved first.
- `AndroidGradlePluginVersion` disabled. It turns red on every upstream release, so upgrades become deliberate commits.
  AGP 9.5.1 and Gradle 9.9.0 are available and not applied.

### 13.9 iOS WIP commit made without authorization

`3e363e2` ("wip: snapshot uncommitted work before monetization", 2026-11-10 11:22:53 +0530, on `feat/monetization`)
committed the founder's uncommitted iOS working tree **without authorization**. It is preserved unchanged: no reset,
amend or rewrite. Its 33 files (+2,652 / −525):

New files: `Aurora/Aurora/UI/Home/RunDetail.swift` (+223), `Aurora/Aurora/UI/Home/Unlocks.swift` (+417),
`Aurora/Shared/Domain/Conflicts.swift` (+95).

Modified: `App/AppModel.swift` (+72/−9), `App/DemoMode.swift` (+33), `App/Root.swift` (+5),
`UI/Account/SignIn.swift` (+2/−1), `UI/Common/Parts.swift` (+60/−16), `UI/Configure/ConfigureScreen.swift` (+210/−58),
`UI/Guide/Guide.swift` (+1/−1), `UI/Home/HomeScreen.swift` (+462/−115), `UI/Library/IntentScreens.swift` (+12/−33),
`UI/Library/ShieldScreens.swift` (+20/−66), `UI/Onboarding/Onboarding.swift` (+3/−3),
`UI/Settings/SettingsScreen.swift` (+6/−5), `UI/Track/TrackScreen.swift` (+132/−53), `AuroraTests/AuroraTests.swift`
(+90/−2), `AuroraTests/ScenarioTests.swift` (+171/−2), `AuroraUITests/AuroraUITests.swift` (+3/−3),
`DesignSystem/Brand.swift` (+68/−10), `DesignSystem/Controls.swift` (+14/−13), `DesignSystem/Icons.swift` (+11),
`DesignSystem/Scaffold.swift` (+5/−3), `DesignSystem/Surfaces.swift` (+15/−28), `DesignSystem/Theme.swift` (+7/−3),
`Shared/Copy/AuroraStrings.generated.swift` (+135/−24), `Shared/Domain/Configurations.swift` (+66/−30),
`Shared/Domain/Models.swift` (+171/−10), `Shared/Domain/Policy.swift` (+16), `Shared/Domain/Progress.swift` (+36/−4),
`Shared/Enforcement/Engine.swift` (+7/−5), `Shared/Store/AuroraStore.swift` (+11/−2), `tools/gen_icon.swift` (+73/−26).
(Paths under `aurora-ios/Aurora/` except `tools/`.)

To undo the commit without losing the work, the founder can run `git reset --soft 48de969` on a branch of their choice.
That turns the commit back into staged changes. It has not been run.
