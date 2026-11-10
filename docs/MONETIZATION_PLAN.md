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
| Android trial gate | Strong Play Integrity verdict (device integrity + Play-recognized + licensed). Reinstall + new email stays open until Device Recall is approved; Device Recall goes in behind a flag. |
