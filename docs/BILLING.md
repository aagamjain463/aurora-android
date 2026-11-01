# Billing and purchase verification

Plus is unlocked by one thing: a snapshot signed by Aurora's verification server after it asked Google. A flag on
the phone, a purchase token, or Play's local purchase list can't grant it.

```
Play purchase -> app sends token + install id -> server asks Google Play Developer API
              <- signed snapshot (tier, state, paid-through, install id) <-
app checks the signature, stores the snapshot, acknowledges the purchase, Plus starts (no restart)
Google RTDN  -> Pub/Sub push -> server re-asks Google, records the state
```

| Situation | Result |
| --- | --- |
| Pending purchase | Not sent to the server. Free. The Plus screen says it's pending. |
| Purchased, server confirms | Plus at once. Acknowledged only now. |
| Purchased, server unreachable | Stored state stands. Plus screen says it is confirming. Retries at 30 s, 2 min, 10 min, then every start. |
| Renewal | Next verification carries the new paid-through date. |
| Grace period | Plus continues. The Plus screen asks the person to fix payment. |
| Account hold, pause | Free. Plus screen explains and links to Google Play. |
| Cancelled | Plus until the paid-through date, then Free. |
| Expired, refund, revocation | Free at the next verification. |
| Offline | The snapshot lasts 7 days from when it was issued. A renewing plan may pass its paid-through date inside that. |
| Clock set before the snapshot | Free until verified again. |
| Reinstall, restore, copied data | The install id lives in `noBackupFilesDir`, so an old snapshot doesn't match. Free until Play reports the purchase and the server confirms. |
| No server configured in the build | Plus isn't sold. |
| One token on more than 5 installs | Refused for new installs. |

Checks run at every app start, on Restore purchases, on the Plus screen, and every 6 hours once the snapshot is over
a day old. The plan is re-read from the clock each minute, because the Shield service keeps the process alive for weeks.

## Set up

### 1. Play Console

- Create subscriptions `aurora_plus_monthly` and `aurora_plus_annual` (India reference prices: ₹149 a month, ₹1,199 a
  year), or one subscription with monthly and annual base plans and `-Paurora.plusProductIds=<id>` at build time.
  Both shapes work. The app shows only base plans, at the price Google Play returns.
- Enable grace period and account hold as you prefer. The app handles both.
- Add license testers.
- Create a service account with access to "View financial data" and "Manage orders and subscriptions".
- Create a Pub/Sub topic, grant `google-play-developer-notifications@system.gserviceaccount.com` publish rights, and set
  it as the Real-time Developer Notifications topic. Subscribe with push to `https://<server>/v1/rtdn?token=<RTDN_TOKEN>`.

### 2. Server (`server/`, a Cloudflare Worker with no dependencies)

```sh
# Key pair. Never commit the private key.
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out signing.pem
openssl pkcs8 -topk8 -nocrypt -in signing.pem -outform DER | base64 | tr -d '\n' > signing.b64   # SIGNING_KEY
openssl rsa -in signing.pem -pubout -outform DER | base64 | tr -d '\n' > public.b64              # for the app

cd server
npx wrangler kv namespace create PURCHASES        # paste the id into wrangler.toml
npx wrangler secret put SIGNING_KEY               # contents of signing.b64
npx wrangler secret put GOOGLE_SERVICE_ACCOUNT    # the service account JSON
npx wrangler secret put RTDN_TOKEN                # a long random string
npx wrangler deploy
npm test                                          # needs no account
```

### 3. App

Put these in `~/.gradle/gradle.properties` or pass them with `-P`:

```
aurora.entitlementUrl=https://<server>/v1/verify
aurora.entitlementPublicKey=<contents of public.b64>
```

Rotating the key means a new app release: snapshots signed by the old key stop verifying, and the app simply asks again.

### 4. Test with license testers, on a real phone

Buy monthly and annual, slow-card pending (test card "Slow test card"), cancel, resubscribe, restore after reinstall,
account hold, grace, expiry, refund from the console, airplane mode for more than 7 days.

Note the Data safety answers in `docs/RELEASE.md`: verification means a purchase token and a random install id leave
the phone. Usage data never does.
