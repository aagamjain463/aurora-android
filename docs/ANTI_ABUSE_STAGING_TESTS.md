# Anti-abuse: real-device and staging tests

What the automated suites can't prove (`docs/ANTI_ABUSE.md`, section 9): real Firebase tokens, a real Android
Keystore, real D1 under concurrency, and real Play Integrity verdicts. Run these before any production rollout
stage and record the results in PR #2. Nothing here touches production.

## 0. Ground rules

- Use a **separate** Worker (`aurora-entitlements-staging`) and a **separate** D1 database (`aurora-staging`).
  Every `wrangler` command below has `--env staging`. Never run `migrations apply aurora --remote`, `wrangler
  deploy` without `--env staging`, or any `d1 execute aurora`.
- Use test Google accounts or test email addresses, never a real user's. Don't paste ID tokens, service account
  JSON or signing keys into tickets, PRs or chat.
- Use a phone whose Aurora data can be lost. Accounts A, B and C below are fresh test accounts on Free.

## 1. Staging setup (once)

```sh
cd server
npx wrangler d1 create aurora-staging            # note the database_id it prints
```

Add this to `server/wrangler.toml` (vars aren't inherited by environments, so all of them are repeated):

```toml
[env.staging]
name = "aurora-entitlements-staging"

[env.staging.vars]
PACKAGE_NAME = "app.aurora"
PRODUCT_IDS = "aurora_plus_monthly,aurora_plus_annual"
FIREBASE_PROJECT_ID = "aurora-24aa9"
ENVIRONMENT = "staging"
DEVICE_QUOTA = "enforce"
INTEGRITY = "observe"
APP_CERT_SHA256 = ""

[[env.staging.d1_databases]]
binding = "DB"
database_name = "aurora-staging"
database_id = "<database_id from d1 create>"
migrations_dir = "migrations"
```

A staging signing key, so staging can never sign anything production trusts:

```sh
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out ~/aurora-staging-signing.pem
openssl pkcs8 -topk8 -nocrypt -in ~/aurora-staging-signing.pem -outform DER | base64 | tr -d '\n'   # SIGNING_KEY
openssl pkey -in ~/aurora-staging-signing.pem -pubout -outform DER | base64 | tr -d '\n'            # app's aurora.entitlementPublicKey
```

```sh
npx wrangler d1 migrations apply aurora-staging --env staging --remote    # 0001, 0002, 0003
npx wrangler secret put SIGNING_KEY --env staging                         # the PKCS#8 line above
npx wrangler secret put FIREBASE_SERVICE_ACCOUNT --env staging            # same Firebase project as the app
npx wrangler secret put GOOGLE_SERVICE_ACCOUNT --env staging              # Play Developer API + Play Integrity
npx wrangler secret put RTDN_TOKEN --env staging                          # any random string
npx wrangler deploy --env staging
```

Staging app build, pointing at staging (debug-signed, so it is *not* a Play build: see T5):

```sh
./gradlew :app:installDebug \
  -Paurora.serverUrl=https://aurora-entitlements-staging.<subdomain>.workers.dev \
  -Paurora.entitlementPublicKey=<public key line above>
```

Leave `aurora.playCloudProjectNumber` unset for T1 to T4, so this build never asks for a verdict.

Shortcut for the checks below:

```sh
q() { npx wrangler d1 execute aurora-staging --env staging --remote --command="$1"; }
```

## T1. Firebase authentication

1. Sign in on the phone with Google (account A). Then sign out and sign in with an email link (account B).
2. `q "SELECT id, email IS NOT NULL AS has_email, status FROM accounts ORDER BY created_at DESC LIMIT 5"`:
   two rows, `active`.
3. `q "SELECT id, account_id, label FROM installations ORDER BY registered_at DESC LIMIT 5"`: both accounts on the
   **same** installation id.
4. In the Firebase console, disable account B. Reopen the app: it signs B out (server answers `401 revoked`).
   Re-enable B afterwards.

Pass: all four as described, no `500` in `npx wrangler tail --env staging`.

## T2. Android Keystore device key

1. After T1: `q "SELECT id, public_key IS NOT NULL AS bound, key_bound_at FROM devices ORDER BY last_seen_at DESC LIMIT 3"`.
   The phone's id has `bound = 1`.
2. Force-stop and reopen the app; reboot the phone; reopen. Same id, still bound, and
   `q "SELECT COUNT(*) FROM abuse_events WHERE kind = 'device_proof_failed'"` is 0.
3. Change the screen lock (PIN to pattern and back). Reopen. Still no `device_proof_failed` (the key has no
   user-authentication requirement, so this must not invalidate it).
4. Clear Aurora's storage in Android settings. Reopen and sign in: a **new** id appears, bound to a new key; the old
   row is unchanged. (Expected limitation: a cleared phone is a new installation.)

Pass: 1 to 4 as described. Also note the phone model and Android version: some vendors' Keystores misbehave.

## T3. Account switching (DEVICE_QUOTA = enforce)

1. Account A, Free: start a 110-minute Always Block commitment.
2. Sign out. The commitment keeps running (it is carried). Sign in as B.
   - The Free card shows **10 minutes** left.
   - Starting 11 or more minutes is refused; 10 minutes is allowed.
3. Switch back to A: 0 left. Sign in as C: 0 left.
4. As A, Settings › Account › Delete account. The scheduled date shown is **30 days** from today. Sign in as B: still
   0 left.
5. Pause-and-resume (fixed in this review): as a fresh account on a fresh phone or after the local day resets,
   create a schedule starting in 2 hours for 60 minutes. Then:
   `q "SELECT idempotency_key, status, start_at, end_at FROM quota_reservations ORDER BY created_at DESC LIMIT 5"`
   shows one live reservation. Pause the schedule: it becomes `released` and its key ends in `:released:<id>`.
   Turn it back on: a **new** live reservation, and the Free card's minutes go down again.
6. `q "SELECT kind, COUNT(*) FROM abuse_events GROUP BY kind"`: `device_quota_refused` for each refusal in 2 and 3,
   `many_accounts` only from a fourth account onwards.

Pass: every number above. Fail if any switch, deletion or pause/resume gives minutes back, or if the running
commitment from step 1 stops early.

## T4. D1 transactions under concurrency

Needs two or more fresh test accounts' Firebase ID tokens (one hour each). One way, with the web API key from
`app/google-services.json` and an email-link sign-in: `POST
https://identitytoolkit.googleapis.com/v1/accounts:sendOobCode?key=<API_KEY>` with
`{"requestType":"EMAIL_SIGNIN","email":"<test address>","continueUrl":"<an authorized domain>"}`, open the email,
take `oobCode` from the link, then `POST …/v1/accounts:signInWithEmailLink?key=<API_KEY>` with
`{"email":"<test address>","oobCode":"<code>"}`; its `idToken` is the token.

```sh
cd server
BASE=https://aurora-entitlements-staging.<subdomain>.workers.dev TOKENS=<tokB>,<tokC> node scripts/race.mjs accounts
BASE=https://aurora-entitlements-staging.<subdomain>.workers.dev TOKENS=<tokD> node scripts/race.mjs phones
```

Each prints `allowed 2 of 6 (expected 2)` and exits 0. Then check D1 agrees:
`q "SELECT installation_id, COUNT(*) FROM quota_reservations WHERE status <> 'released' GROUP BY installation_id"`
(2 on the race's installation for `accounts`; 2 in total across its six installations for `phones`). Run each mode
three times with fresh accounts. Any `allowed 3` is a failure; a few `503 try_again` are acceptable (retries ran
out), as long as never more than 2 are allowed.

## T5. Play Integrity

Setup (Play Console and Google Cloud, staging only needs the same app):

1. Play Console › Test and release › App integrity › Play Integrity API: link a Google Cloud project. Note its
   **project number**.
2. In that Cloud project, enable the Play Integrity API and give the service account in `GOOGLE_SERVICE_ACCOUNT`
   permission to decode tokens.
3. `APP_CERT_SHA256`: the **app signing** key certificate SHA-256 from Play Console › App integrity, converted
   from the colon hex Play Console shows to the base64url form verdicts use:
   `echo '<AA:BB:…>' | tr -d ':' | xxd -r -p | base64 | tr '+/' '-_' | tr -d '='`. Set it in `[env.staging.vars]`
   and redeploy. If a genuine Play install keeps coming back `basic` instead of `ok`, this value is wrong.
4. Upload a release build with `-Paurora.playCloudProjectNumber=<number>` and the staging `serverUrl` and public key
   to an **internal testing** track, and install it from Play on the test phone.

Checks, with `INTEGRITY = "observe"`:

1. Open the app (a sync). `q "SELECT id, integrity, integrity_at FROM devices ORDER BY last_seen_at DESC LIMIT 3"`:
   `ok` on a genuine phone, `basic` on an unlocked-bootloader phone.
2. Sideload the debug build built with the same `playCloudProjectNumber` (uninstall the Play one first): its verdict
   is `failed` (`UNRECOGNIZED_VERSION`), and an `integrity_failed` event appears. Free still works (observe).
3. An emulator without Play services, or the build without `playCloudProjectNumber`: stays `unknown`, nothing is
   refused.

Then set `INTEGRITY = "enforce"` on staging only and redeploy:

4. The sideloaded build (step 2): a new Free commitment is refused (`integrity_failed`); Plus, if the account has it,
   still works; anything already running keeps running.
5. Clear its data and sign in with the same account: still refused (fixed in this review; a new id can't shed the
   verdict). With a **different** account it is allowed (known limitation, `docs/ANTI_ABUSE.md` section 8).
6. Reinstall from Play and open: the next sync asks again, the verdict is `ok`, and Free works at once.

Pass: every verdict and refusal as described; no genuine Play install ever refused.

## T6. Deletion grace period

1. Request deletion in the app: the date shown is 30 days out. `q "SELECT status, deletion_requested_at FROM accounts WHERE …"`.
2. Sign in again and choose Keep my account: `status = 'active'`, `deletion_requested_at` NULL.

## Recording results

For each of T1 to T6: date, phone model and Android version, build (debug, phone, or Play internal track),
pass/fail, and any `abuse_events` counts. Put the table in PR #2. A test that wasn't run is "not run", never "pass".

## Cleanup

`npx wrangler delete --env staging` removes the staging Worker; `npx wrangler d1 delete aurora-staging` the
database. Remove the `[env.staging]` block if it shouldn't stay. Delete the test Firebase users.
