# Billing and purchase verification

Plus is unlocked by one thing: a snapshot signed by Aurora's verification server after verifying the purchase with Google Play. A flag on the phone, a purchase token alone, or Play's local purchase list cannot grant it. Entitlements belong to the verified Aurora account, not just a single device.

```
Play purchase -> app sends token + install id + auth token -> server asks Google Play Developer API
              <- signed snapshot (tier, state, paid-through, install id, uid) <-
app checks the signature, stores the snapshot, acknowledges the purchase, Plus starts (no restart)
Google RTDN   -> Pub/Sub push -> server re-queries Google, updates Cloudflare D1 state
```

| Situation | Result |
| --- | --- |
| Pending purchase | Not sent to the server. Free. The Plus screen says it's pending. |
| Purchased, server confirms | Plus at once. Acknowledged only now. Bound to the Aurora account. |
| Purchased, server unreachable | Stored state stands. Plus screen says it is confirming. Retries with backoff, then every start. |
| Renewal | Next verification carries the new paid-through date. |
| Grace period | Plus continues. The Plus screen asks the person to fix payment. |
| Account hold, pause | Free. Plus screen explains and links to Google Play. |
| Cancelled | Plus until the paid-through date, then Free. |
| Expired, refund, revocation | Free at the next verification. |
| Offline | The snapshot lasts 7 days from when it was issued. A renewing plan may pass its paid-through date inside that. |
| Clock set before the snapshot | Free until verified again. |
| Reinstall, replacement phone | Sign into the same Aurora account; the server signs a snapshot for the new install. Restored immediately. |
| Multiple devices | Up to 5 concurrent active installations per Plus account. The 6th device is prompted to remove an older device. |
| Purchase on another account | If another Aurora account attempts to verify an already-claimed purchase, the server returns `conflict`. Resolves via explicit transfer with reauthentication. |
| No server configured in the build | Plus isn't sold. |

Checks run at every app start, on Restore purchases, on the Plus screen, and every 6 hours once the snapshot is over
a day old. The plan is re-read from the clock each minute, because the Shield service keeps the process alive for weeks.

## Set up

### 1. Play Console

- Create subscriptions `aurora_plus_monthly` and `aurora_plus_annual` (India reference prices: ₹149 a month, ₹1,199 a
  year), or one subscription with monthly and annual base plans and `-Paurora.plusProductIds=<id>` at build time.
  Both shapes work. The app shows only base plans, at the price Google Play returns.
- Enable grace period and account hold as you prefer. The app handles both.
- Add license testers.
- Create a Google Cloud service account with access to "View financial data" and "Manage orders and subscriptions".
- Create a Pub/Sub topic, grant `google-play-developer-notifications@system.gserviceaccount.com` publish rights, and set
  it as the Real-time Developer Notifications topic. Subscribe with push to `https://<server>/v1/rtdn?token=<RTDN_TOKEN>`.

### 2. Server (`server/`, Cloudflare Worker + Cloudflare D1)

```sh
# Generate Ed25519 or RSA key pair. Never commit the private key.
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out signing.pem
openssl pkcs8 -topk8 -nocrypt -in signing.pem -outform DER | base64 | tr -d '\n' > signing.b64   # SIGNING_KEY
openssl rsa -in signing.pem -pubout -outform DER | base64 | tr -d '\n' > public.b64              # for the app

cd server
# Provision D1 database
npx wrangler d1 create aurora-production
# Apply migrations (server/migrations/0001_accounts.sql)
npx wrangler d1 execute aurora-production --file=migrations/0001_accounts.sql

# Set secrets
npx wrangler secret put SIGNING_KEY               # contents of signing.b64
npx wrangler secret put GOOGLE_SERVICE_ACCOUNT    # the service account JSON
npx wrangler secret put FIREBASE_PROJECT_ID       # the Firebase project ID for JWT validation
npx wrangler secret put RTDN_TOKEN                # a long random secret token
npx wrangler deploy
npm test                                          # runs full worker test suite
```

### 3. App

Put these in `~/.gradle/gradle.properties` or pass them with `-P`:

```
aurora.accountServerUrl=https://<server>
aurora.entitlementPublicKey=<contents of public.b64>
```

Rotating the key means a new app release: snapshots signed by the old key stop verifying, and the app simply asks again.

### 4. Test with license testers, on a real phone

Buy monthly and annual, slow-card pending (test card "Slow test card"), cancel, resubscribe, restore after reinstall,
account hold, grace, expiry, refund from the console, airplane mode for more than 7 days.

See `docs/ACCOUNT_SYSTEM.md` for the complete specification of routes, payload schemas, and multi-device quota synchronization.
