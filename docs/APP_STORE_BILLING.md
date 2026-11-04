# App Store billing and the shared Plus entitlement

**Status: staged, not deployed.** Mocks prove the logic. Only real purchases prove Apple agrees (see "Still unproved").

Aurora has one account service. Android and iOS share Firebase identity, the `purchases` table, the signed snapshot, the Plus seats and the Free quota. Neither platform has its own billing backend.

```
StoreKit purchase -> app sends its signed transaction + install id + bundle id + Firebase token
   /v1/verify/apple: verify JWS to Apple's root -> GET App Store Server API -> verify that answer too
                     pick this original transaction -> ownership check -> store
   <- the same signed snapshot Android gets

App Store Server Notification V2 -> /v1/apple/notifications: verify signature -> ask Apple -> update state, never owner
Daily cron -> renewing App Store rows past their date -> ask Apple
```

## Trust rules

1. A JWS payload counts only after its `x5c` chain verifies to the Apple Root CA - G3 pinned in `src/apple.js`, with Apple's marker extensions on the intermediate (`1.2.840.113635.100.6.2.1`) and the leaf (`1.2.840.113635.100.6.11.1`), and an ES256 signature from that leaf. These are the checks in Apple's own `app-store-server-library-node`, without its OCSP lookup.
2. A verified transaction from the phone is only a pointer and proof of possession. Tier, dates and token come from the Server API's answer, itself verified.
3. No configuration or request can change the root. `appleTrust` is a module value only tests replace.
4. Notification contents never set a state. A valid notification means "ask Apple again".
5. The route decides the platform. A `platform` field means nothing.

OCSP is left out because a revoked signing certificate gains an attacker nothing here: a forged JWS can't change what the Server API answers. A compromised key could still forge a *notification*, which only triggers a re-read, and could present a forged transaction as proof of possession for a move (see "Known limits").

## Ownership

The server recomputes `appAccountToken` from the verified Firebase `sub` (the first 16 bytes of SHA-256 of the UID, shaped as a UUID) and accepts none from clients. It is Apple's counterpart of Google's `obfuscatedAccountId`. Knowing someone's UID only lets you buy them a subscription, so the token needs no secret, stored state or round trip.

| Purchase row | Apple-signed token | `/v1/verify/apple` |
| --- | --- | --- |
| Bound to this account | any | Updates. Restore works. |
| Bound to another account | any | `conflict`, with a masked hint. Nothing moves. |
| Unbound | this account's | Binds, atomically. |
| Unbound | another account's | `conflict`. Stores nothing. |
| Unbound | none, or `FAMILY_SHARED` | `conflict` + `claimRequired`. Stores nothing. |

**Legacy policy.** A transaction with no token is never bound by asking. `/v1/purchases/transfer/apple` binds it, and is also how a subscription moves between accounts. It requires an explicit tap, a sign-in within 10 minutes, a transaction Apple signed (a bare id is refused), Apple's current answer, 5 attempts an hour, and one move per 30 days, enforced inside the write so racing moves can't both pass. After a move the stored owner outranks the token.

Notifications and the cron can't bind or move anything. A purchase whose first verify never reached us stays unbound until the app's next `Transaction.currentEntitlements` pass.

## Entitlement

| Apple's status | Plus | `state` | Paid through |
| --- | --- | --- | --- |
| 1, renewing | yes | `active` | `expiresDate` |
| 1, auto-renew off | yes, until the date | `canceled` | `expiresDate` |
| 4 billing grace | yes | `in_grace_period` | later of `gracePeriodExpiresDate`, `expiresDate` |
| 3 billing retry | no | `on_hold` | `expiresDate` |
| 2 expired | no | `expired` | `expiresDate` |
| 5 revoked, or any `revocationDate` | no | `revoked` | `expiresDate` |
| `isUpgraded`, unknown status | no | as reported | `expiresDate` |

Plus also requires the date in the future, so an active status past its date grants nothing. An unknown or mismatched bundle, environment, product, type or id is "not this purchase". A malformed or unverifiable answer is an outage: the route answers 503 and changes nothing.

`accountSnapshot` already aggregates correctly: it takes the latest row with `tier = 'plus'` and a future date, whichever store it came from, so an expired or refunded row on one store never hides a live one on the other. The only snapshot change is an additive `store` field. `packageName` stays `app.aurora` in every snapshot because both apps check it as the service's identity. Android decodes with `ignoreUnknownKeys`.

## App Store policy

Guideline 3.1.3(b), read 2026-11-04: multiplatform apps "may allow users to access content, subscriptions, or features they have acquired in your app on other platforms ... provided those items are also available as in-app purchases within the app". Keep Plus on sale through StoreKit on iOS. The iOS patch shows a Google Play subscriber plain text, with no link to Play, because 3.1.1 and 3.1.3 restrict calls to action toward other purchase methods outside the US storefront.

Google Play's Payments policy page was read the same day. It restricts leading users to other payment methods, and doesn't address using a subscription bought on another platform. It points to an FAQ that was not reviewed. **Check that FAQ before relying on iOS-bought Plus on Android.** The Android app has no link to the App Store and needs none.

## Credentials and setup, once membership is active

1. **App Store Connect:** create the subscription group and products. Their ids must equal `APPLE_PRODUCT_IDS` in `wrangler.toml` and `PlusProductIds` in `Billing.swift` (currently `aurora_plus_monthly`, `aurora_plus_annual`).
2. **Family Sharing:** decide per product. On means family members land in the explicit-claim path, because Apple's transaction for them carries no purchaser token.
3. **API key:** Users and Access > Integrations > In-App Purchase. Note the Issuer ID and Key ID, and download the `.p8` (one download only).
4. **Notifications:** App Information > App Store Server Notifications. Set Version 2 and the Production and Sandbox URLs to `https://<server>/v1/apple/notifications`.

```sh
cd server
npm test                                              # Must be green, on the merged branch.
npx wrangler d1 migrations list aurora --remote       # Read-only. See which are pending.
npx wrangler d1 migrations apply aurora --remote      # Migrate BEFORE deploying the Worker.
npx wrangler secret put APPLE_ISSUER_ID
npx wrangler secret put APPLE_KEY_ID
npx wrangler secret put APPLE_PRIVATE_KEY             # The .p8 contents. PEM or bare base64.
npx wrangler deploy
```

`APPLE_BUNDLE_ID`, `APPLE_PRODUCT_IDS` and `APPLE_ALLOW_SANDBOX` are plain vars in `wrangler.toml`. Until all the Apple settings exist, the three App Store routes answer 503 and Android is unaffected.

Then apply `ios-integration/aurora-ios-billing.patch` from the iOS repo root: `git apply --3way` once that repo's `Billing.swift` and `Account.swift` are committed.

**Sandbox.** `APPLE_ALLOW_SANDBOX = "1"` is needed while Apple reviews a build, because App Review purchases with sandbox accounts in the production app. The cost: TestFlight testers can get real Plus from a sandbox purchase while it stays active. Turning it off later takes effect at the next re-check of each sandbox row, or at its date.

**Rollback.** Deploy the previous Worker; `purchases` keeps working with the extra columns. The migration's header has the schema rollback. It deletes App Store rows, so run it only when none need keeping.

## What the earlier patch got wrong

`aurora-ios/server-patches/apple-verify.js` is superseded by `server/src/apple.js`. It should not be pasted in.

| Problem | Finding | Now |
| --- | --- | --- |
| Markdown-formatted URLs | Not present. A raw byte scan of the patch and the server found none, and the Google OAuth scope is a plain string. The real issue: the patch used Apple's older `*.itunes.apple.com` hosts | `api.storekit.apple.com`, `api.storekit-sandbox.apple.com` |
| Platform identity | Compared the bundle against Android's `PACKAGE_NAME`, so the iOS app's real bundle could never pass, and iOS sent `app.aurora` to get through | `APPLE_BUNDLE_ID`, checked in the request, the phone's JWS, Apple's answer and each transaction inside it |
| Product ids | Shared Google's `PRODUCT_IDS` | Separate `APPLE_PRODUCT_IDS`, one internal Plus |
| Verification | `jwsPayload` only decoded. Nothing checked a signature, and the phone's unverified claim chose what to look up | Full chain and signature, phone and API alike |
| Subscription choice | `.find((t) => t.originalTransactionId)`: the first line of any subscription | Exact id match, then consistency checks |
| Ownership | First account to send a transaction bound it. `appAccountToken` was read and never compared | Table above |
| Revocation and states | Mostly right, but a canceled subscription read as `active`, and no type or consistency checks | Table above |
| Sandbox | Always queried, with no way to refuse it | `APPLE_ALLOW_SANDBOX`, and environments must agree |
| Notifications | Absent | `/v1/apple/notifications` plus the daily re-check |

## Android-specific assumptions in the backend

Harmless, kept: `packageName` in snapshots and reservations is `app.aurora` on both platforms, as a service identity. `entitlementFrom`, `playSubscription`, RTDN, `/v1/verify` and `/v1/purchases/transfer` are Google-only by route and remain so. Quota, seats, installations and deletion were already platform-neutral.

Changed: the `purchases` table now records its store. `housekeeping` keeps the unowned row after account deletion, but an App Store row then rebinds only through its token or an explicit move, where a Google one with no external id binds to the next account.

## Still unproved

| What | Why mocks can't show it | How to check |
| --- | --- | --- |
| Apple accepts our JWT | Requires a real key | "Request a Test Notification" and any sandbox verify |
| Real `signedPayload` and `signedTransactionInfo` verify end to end | No Apple-signed production JWS is public. The real certificate chain is tested, and Apple's own mock JWS files are | First sandbox purchase, then the test notification |
| The new `api.storekit.apple.com` hosts answer | Taken from Apple's library, changed 2026-05-05 ("Update API URLs"). The docs pages wouldn't render | First sandbox purchase. The old `*.itunes.apple.com` hosts are the fallback to try |
| `appAccountToken` survives renewal, upgrade and restore | StoreKit test sessions only cover the local purchase | Sandbox: buy, renew, upgrade, restore on a second device |
| Family Sharing payload shape | Assumed from the docs | Sandbox, if enabled |
| Refund and revocation timing against the API | Apple-side | Sandbox refund or a TestFlight revoke; watch `purchases.state` |
| Apple's `signedDate` and API read-after-notification consistency | Apple-side | Repeat refund and renewal in sandbox, then compare state |
| Sign in with Apple gives `email_verified` and `email` in the Firebase ID token | `authenticate` refuses without both, and iOS would then sign the person out | Sign in with Apple, including Hide My Email, then call sync |
| Android UI for an App Store subscriber | Not changed here | See "Known limits" |

## Merging with `feat/device-quota-anti-abuse`

That branch was being written in the Android tree at the same time, and also edits `worker.js` and `fixture.js`. Hence `0003_app_store.sql`, after its `0002_devices.sql`. A rehearsal on a snapshot of its uncommitted work passed every test from both (126). It took these resolutions:

- `worker.js`: keep both header comments. In `appleNotification`, replace the `request.text()` and size check with `const raw = await rawBody(request)`, because that branch's fetch handler reads and size-checks the body first.
- `fixture.js`: keep this branch's `migrationNames` and `without` lines, and add `apple: null` to that branch's `state` object.

An iOS installation has no device key, so it stays unproved under that branch. It is allowed, and counts toward the device quota. Its Play Integrity checks don't apply to iOS.

## Known limits

- **A stolen transaction JWS plus a fresh sign-in can move a subscription.** This matches Android's purchase-token model. Hardening available: check `deviceVerification` against `AppStore.deviceVerificationID`, once its exact byte format can be tested on a real device.
- **Billing-retry recovery depends on a notification or the app.** The cron only re-checks rows still marked renewing.
- **20 re-checks a day**, to stay inside the free plan's subrequest limit. The oldest go first, and the rest wait for the next run.
- **A write race on Google's route remains.** `/v1/purchases/transfer` checks its cooldown before writing, so two simultaneous moves could both pass. The App Store route doesn't share this. Left alone so as not to change Android behaviour.
- **Android still says "Manage in Google Play" to an App Store subscriber.** The new `store` field lets it say otherwise. It needs Android copy and a release. Access is unaffected.
- **iOS has no in-place re-authentication.** A move needing a fresh sign-in shows the existing "sign in again" message.
- **`RTDN_TOKEN` is compared with `!==` and sent in the URL.** Existing behaviour, low risk, unchanged.
