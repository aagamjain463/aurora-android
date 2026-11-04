# App Store billing and shared Plus

Local implementation only. Nothing has been pushed, deployed, merged into the original app trees, or migrated in production. Product limits and pricing remain unchanged. See [the 4.10 audit](APP_STORE_REVIEW_AUDIT.md) before submission and [the integration handoff](../android-integration/README.md) before integrating.

## Production account entitlement

Production StoreKit and Google Play purchases grant one Aurora Plus entitlement to the same verified Firebase account. iOS purchases stay native StoreKit; Android purchases stay Google Play Billing. Neither app redirects users to another platform to purchase.

`POST /v1/verify/apple` requires a verified identity, installation, iOS `bundleId` and signed transaction. The server verifies Apple's certificate chain/signature, bundle, environment, product, subscription type and original transaction ID, independently requests current status from Apple, and verifies the signed response. Client-supplied product, tier, dates and account tokens cannot grant Plus.

First binding requires `PURCHASED` ownership and Apple's signed `appAccountToken` matching the UUID derived from the authenticated Firebase UID. An existing binding cannot change accounts through verify, restore, notifications or scheduled refresh. Paid active subscriptions and billing grace grant Plus. Expired, refunded, revoked, upgraded-away and billing-retry-only subscriptions do not. Cancellation keeps access through the paid period. Device seats still apply.

Family Sharing is OFF. Leave it disabled for both initial products in App Store Connect. The local StoreKit products already have `familyShareable: false`. The backend refuses `FAMILY_SHARED` verification and never grants Plus from family evidence during refresh. Enabling Family Sharing in App Store Connect is irreversible; do not enable it as an experiment.

## Sandbox and review isolation

`APPLE_ALLOW_SANDBOX = "1"` supports development, TestFlight and App Review evidence without granting production account Plus. Each row records `Production` or `Sandbox`; the key is SHA-256 of `apple:<environment>:<originalTransactionId>`. Identical numeric IDs cannot collide across environments. Verified evidence selects one API host; 404 never changes environment. Notifications and scheduled refresh use the stored/verified environment.

Production snapshot selection and server Plus/quota checks exclude sandbox rows. A sandbox-only account receives signed `tier: free`, including on Android, and cannot bypass production server quota. Production subscription-management metadata also excludes sandbox rows.

The signed snapshot may additionally contain `iosSandbox: {scope: "ios_sandbox", productId, expiresAt}`. This is explicitly temporary iOS review access, bound to the signed account and installation and limited by device seats. Expiry is at most 15 minutes after issuance and never beyond the verified subscription expiry. The iOS patch recognizes only this scope and the two configured Plus products, rejects incorrect bindings/overlong grants, and gives review access no offline renewal grace. The UI labels it as sandbox review access. Android ignores the additional field and continues to read the production tier.

This permits iOS-local Plus review without cross-platform production Plus. Server quota endpoints deliberately retain production Free rules for sandbox-only accounts. If a future review flow requires server quota authorization, test that flow with an isolated Worker/database/signing key and test accounts; do not add an unrestricted production quota bypass. Disclose this limitation in submission preparation.

Xcode StoreKit transactions have environment `Xcode`, not `Sandbox`. The real backend refuses them. Local iOS tests stub server responses; they do not demonstrate Apple server acceptance.

## Ownership and recovery

`POST /v1/purchases/transfer/apple` authenticates and returns `403 transfer_disabled`. The iOS patch has no move/claim control. A stolen JWS is replayable. Fresh sign-in proves control of the destination Aurora account, not the source purchase. Signature validation, Apple's current status, a cooldown and a device key do not establish source ownership.

Safe recovery: sign in to the original verified Aurora account and restore there. A future migration process must prove control of both accounts, require explicit consent, record an audit trail, revoke old access and reassign atomically. Support must not accept a JWS, transaction ID or receipt alone as proof. If source recovery fails, retain the binding and explain cancellation/re-purchase after the current period; do not promise an automatic move.

The same replay weakness existed in the Google Play transfer endpoint. `/v1/purchases/transfer` is also disabled with `403 transfer_disabled`; the Android patch removes its move action. Existing purchase verification, original-account restore and native Google Play buying remain supported. Neither platform can use token possession plus destination sign-in to reassign a bound purchase.

## Apple endpoints: authoritative documentation checked 2026-11-04

- Production: `GET https://api.storekit.apple.com/inApps/v1/subscriptions/{anyTransactionId}`
- Sandbox: `GET https://api.storekit-sandbox.apple.com/inApps/v1/subscriptions/{anyTransactionId}`

Confirmed against Apple's [Get All Subscription Statuses](https://developer.apple.com/documentation/appstoreserverapi/get-all-subscription-statuses), [API changelog](https://developer.apple.com/documentation/appstoreserverapi/app-store-server-api-changelog), and [environment documentation](https://developer.apple.com/documentation/appstoreserverapi/environment). The changelog records the recommended domain change in API version 1.22 (2026-09-28) and says the previous domains remain supported. The new hosts are correct; an old host is not a security fix. JWT authentication uses ES256, issuer/key ID, `aud: appstoreconnect-v1` and bundle ID `bid`.

Future Notifications V2 setup uses `/v1/apple/notifications`, following [Apple's URL setup instructions](https://developer.apple.com/help/app-store-connect/configure-in-app-purchase-settings/enter-server-urls-for-app-store-server-notifications/). Verified messages trigger an independent Apple status read, never ownership changes. Outages request redelivery. Scheduled rechecks currently cover at most 20 lapsed renewing App Store rows per run.

Documentation and fake HTTP tests are complete. No authenticated real sandbox request has been made. Later, with separately authorized credentials/membership, check JWT acceptance, sandbox purchases, renewal, grace, cancellation, refund/revocation, notification delivery/retry, account switching, fresh installs and environment isolation. Never log `.p8` contents or full JWS data.

## Superseding the old patch

The synchronized iOS integration patch removes `server-patches/apple-verify.js`, its runnable entry point and obsolete tests, and replaces its README with a retirement notice. That verifier must not remain routed, imported or deployed. Supported code is `server/src/apple.js` plus this Worker's authenticated routes and migration. The old verifier only decoded purchase claims and was not a secure substitute.

The iOS patch uses `bundleId`, requires sign-in before buying, rechecks entitlements after sign-in, removes transfers, recognizes scoped review access, corrects managing-store wording, and isolates StoreKit tests. It is based on a snapshot of current uncommitted iOS source; review fresh changes before applying it.

## Integration and remaining limits

Preservation commit: `41c9d648d2fe7f84da6eb3abeaf3037e43d10c18`, branch `feat/app-store-backend`. Hardening follows in a separate commit. Migration order on the Android anti-abuse branch is `0001_accounts.sql`, `0002_devices.sql`, `0003_released_keys.sql`, then `0004_app_store.sql`. Only local SQLite migrations ran. Retain the additive schema on Worker rollback; deleting purchases is not a normal rollback.

Deploy-time secrets are still prerequisites, not actions performed here: Apple issuer/key ID/private key, entitlement signing key, and existing Firebase/Google credentials. Do not deploy or migrate production from this handoff.

The certificate verifier pins Apple's G3 root and validates the chain/signatures and Apple marker OIDs. It omits OCSP and requires an independent TLS Server API read for entitlement. Real Apple payload acceptance remains unproven. Billing-retry recovery depends on notification/app verification; the daily job only covers renewing rows. The seven-day production offline policy is unchanged; sandbox review has no such grace.
