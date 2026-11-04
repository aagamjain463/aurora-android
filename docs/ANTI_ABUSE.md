# Anti-abuse: Free's limits belong to the phone too

Aurora Free gives 120 minutes a day of Cooldown and Always Block and 5 active Shield targets. Before this change
both were held per account, so signing in with a second account on the same phone refilled them. This document
covers what now stops that, what cannot be stopped, and how it rolls out. Code: `server/src/device.js`,
`server/src/worker.js` (`reserve`, `quotaFor`, `attest`), `server/migrations/0002_devices.sql`,
`app/.../account/DeviceCredential.kt`, `mergeCarried` and `carriedShield` on Android.

## 1. Two identities, kept apart

| | What it is | What it proves | What resets it |
|---|---|---|---|
| **Account** | Firebase UID, verified email | Who is signed in | Nothing in the app. A new email makes a new account. |
| **Installation id** | Random UUID in `noBackupFilesDir/install_id`, made once per install | Nothing on its own. Anyone can send any string. | Clearing data, reinstalling, factory reset, a new phone. Never backed up. |
| **Device key** | ECDSA P-256 key in the Android Keystore (TEE), not exportable, alias `aurora_device_<installId>` | That a request comes from the installation that first registered this id | The same events as the installation id. Keystore keys aren't backed up or migrated. |
| **Play Integrity verdict** | Google's opinion of the app build and the device, fetched now and then | That this is Aurora from Play on a device that passes integrity, *at that moment* | Expires after 7 days on our side. |

None of these is a physical-device identity. Aurora never reads IMEI, serial numbers, the Android ID, advertising
ids or other fingerprints, and never claims to recognise a phone after a reset or reinstall. The only Google
signal that can do that, **Device Recall**, is not integrated (section 5).

The installation id is independent of accounts: `ProfileVault` swaps account data in and out of DataStore, but the
id file and the key are outside it, so every account on the phone uses the same installation.

### Device key binding (trust on first use)

Every request carries `X-Aurora-Device-Key` (SPKI, base64) and `X-Aurora-Device-Signature`, a DER ECDSA signature
over:

```
aurora-device-v1\n<path>\n<sha256 hex of the Firebase ID token>\n<sha256 hex of the exact body>
```

- The first valid signature the server sees for an id binds that key to it (`devices.public_key`), in one upsert,
  so two first requests racing with different keys bind exactly one.
- From then on, a request naming that id without a valid signature from that key is refused with
  `403 device_proof_required` and an `abuse_events` row. Nobody can spend or be charged to another phone's ledger.
- A proof is bound to its body and its sign-in, so a captured one can't authorize a different request, and it
  expires with the ID token (one hour). Replaying the same request is harmless: every quota write is idempotent.
- Ids never proved (older app versions, a device with no working Keystore) still work, unproved. That is no
  loophole: a person who wants a fresh device ledger can always reinstall, which costs the same.

**Rotation and recovery.** A key is never rotated in place. If the server refuses an id (the key was lost, or
someone else bound the id first), the app deletes its id and key, and the next start is a new installation
(`resetInstallation`). The account's ledger is untouched, so nothing is refilled for the account; the phone gets a
new device ledger, exactly as a reinstall would. A new installation takes a Plus seat; the old one can be removed in
Settings › Devices.

## 2. Two ledgers, one decision

`/v1/quota/reserve` grants Free time only if it fits **both**:

- **Account ledger**: every reservation of the account, on every installation (unchanged). Overlaps on one
  installation count once; overlaps between installations count separately.
- **Device ledger**: every reservation on this installation, by every account, plus `device_usage` (deleted
  accounts, below). All entries are on one installation, so overlaps count once whichever account made them.

Consequences, each covered by a test in `server/test/anti-abuse.test.js`:

- A uses 110 minutes and switches to B on the same phone: B has 10. Switching back to A gives A nothing.
- One account on several phones shares its 120 minutes. A second account on a fresh phone has its own.
- A commitment that only covers time the phone is already committed for adds nothing to the phone's day.
- Midnight, 23/25-hour days, scheduled starts up to 12 hours ahead and time-zone changes follow the existing rules
  (`quota.js`) on both ledgers: the zone of the newest entry applies until its local day ends. The server's clock
  decides; a start in the past is charged from the server's now.
- `sync` and `reserve` report the smaller of the two allowances, with `limitedBy: 'account' | 'device'`.

**Plus.** Plus reservations write no ledger and draw on neither. Plus applies only while a verified purchase is
paid through *and* the installation holds one of the account's five seats (a sixth phone is Free, now also for
reservations, not only for its snapshot). Free time spent on a phone before or after a Plus account used it still
counts: Plus never erases a Free account's consumption, and Plus time never consumes the phone's Free allowance.
When Plus expires, the next reservation is judged as Free.

### Atomicity

Two revisions guard every write: `accounts.quota_revision` and `devices.quota_revision`. A reservation reads both,
then both ledgers, decides, and commits in one `DB.batch`:

1. `INSERT … SELECT … FROM accounts a, devices d WHERE a.quota_revision = ? AND d.quota_revision = ?`
2. bump both revisions, only if that row now exists.

D1 runs a batch as one SQL transaction on a single primary that serialises writes, so the conditional insert is
atomic across Worker instances and isolates (no in-memory lock is assumed anywhere). If anything wrote to either
ledger after the read, the insert is a no-op and the request retries (6 attempts, then `503 try_again`). The
idempotency key (`UNIQUE (account_id, idempotency_key)`, the app sends its commitment id) makes retries and
duplicate requests return the first reservation, but only to the installation that made it (another installation
reusing the key gets `409 idempotency_conflict`). Releasing a reservation that hasn't begun frees its key: the app's
key for a recurring occurrence is fixed (`rec:<schedule>:<start>`), so a schedule paused and turned back on asks
with the same key and is charged again instead of replaying the released authorization for free. Migration
`0003_released_keys.sql` frees the keys of reservations released before this. Tests: six accounts racing on one
phone, six phones racing on one account, duplicate keys racing, release then re-reserve, key reuse across phones.

### Signed, time-limited authorizations; offline

A granted reservation returns the existing signed authorization (RSA, `SIGNING_KEY`): `uid`, `installId`,
`commitmentId`, `reservationId`, `start`, `end`, `packageName`. The app accepts it only for that account, that
installation and that commitment (`AccountRepository.authorize`; test
`anAuthorizationForAnotherPhoneOrCommitmentStartsNothing`). It can't be stretched: its end is fixed and signed.

Offline policy, unchanged in substance and now stated:

- A Free commitment needs the server's signed yes before it starts. A schedule is authorized up to 6 hours
  ahead, so it starts on time offline. There is no offline pool of minutes.
- With no server, a new Free commitment is refused with `NeedsConnection`; nothing is weakened. Anything already
  running keeps running offline, through sign-out and account switches (carried commitments).
- The local ledger is device-wide (below), so the app's own pre-check and the minutes it shows can't be refilled
  by switching either.

## 3. Shield targets stay on the phone

Free's 5 active targets are counted on the device. No package name, site or configuration is uploaded.

- A commitment still running when an account signs out is carried into the next account (`mergeCarried`),
  anonymised (`carried = true`, a generic name). The phone's ledger of committed intervals is carried with it:
  only start, end and zone cross over.
- `Runtime.carriedShield` gives the carried targets. They hold Free's slots first in `retainedShield`,
  `entitledActivations`, `runFor`, `activeTargets` and `policyState`, so B can't add targets while A's five are
  carried, and B's own enabled targets pause until the carried commitment ends. The Shield never enforces more
  than five targets on Free across accounts. Plus stays entitlement-based: no limit.
- `ProfileVault` isolation is unchanged: B never reads A's rules, names, notes, history or reasons. B's runtime
  receives the carried run (its targets, which it must enforce) and A's committed time intervals.

Limit: this counting runs in the app. A modified client can count however it likes. Play Integrity can notice a
modified build (section 4); nothing can stop one from enforcing whatever it wants on its own phone.

## 4. Play Integrity (risk-based)

- Used for one thing: an occasional verdict on the installation, not for every Shield. `sync` returns
  `device.attestNonce` when a proved device is due (never checked, older than 7 days, or last verdict `failed`).
  The app asks Play for a classic Integrity token with that nonce and posts it to `/v1/device/attest`.
- The server decodes it with Google (`decodeIntegrityToken`, the existing service account with the
  `playintegrity` scope) and accepts it only for our package, our nonce, and within 10 minutes. Each nonce is
  spent in one conditional `UPDATE`, so a token or nonce can't be presented twice. `APP_CERT_SHA256` pins Play's
  app-signing certificate.
- Verdicts: `ok` (Play-recognised Aurora, device integrity), `basic` (anything between, e.g. an unlocked
  bootloader), `failed` (an unrecognised build, or a device failing even basic integrity), `unknown` (never
  checked: no Play services, no cloud project in the build, Google unreachable).
- Response: with `INTEGRITY=enforce`, only `failed` holds back new **Free** reservations (`403 integrity_failed`).
  `basic` never refuses anything, Plus is never refused, and running commitments are never touched. `unknown` is
  refused in one case only: another installation of the same account was judged `failed` in the last 7 days. So a
  failed build can't start over with a new install id (or stop signing) to shed its verdict. This installation's
  own `ok` or `basic` verdict clears it at once; otherwise it lapses after 7 days.
  Recovery: install Aurora from Play; a failed device is offered a new check at every sync and a genuine verdict
  clears it at once.
- Development builds set no `aurora.playCloudProjectNumber`, so they never ask and are never judged.

## 5. Device Recall: not integrated

Device Recall (three bits Google keeps per device across reinstalls and resets) is the only supported way to notice
a phone that was reset or reinstalled to farm new allowances. Aurora has no evidence of approved access, so it is
not called, not stubbed and not behind a flag. If access is granted, the plan is: on `attest`, read the bits; write
one bit ("Free allowance used today") with a short write date; treat a set bit on a fresh installation as one more
signal for `abuse_events`, never as an identifier and never stored beyond the device row's verdict.

## 6. Lifecycle: what is enforced and what is only detectable

| Event | Account ledger | Device ledger | Targets |
|---|---|---|---|
| Logout, login, switch between accounts | Kept | Kept (same id) | Carried commitments hold slots |
| Many accounts on one phone | Each its own | **Shared**; a 4th+ account on one id in 30 days is logged (`many_accounts`), never refused | Same |
| Account deleted, new account, same phone | New account is fresh | **Kept**: during the 30-day grace the old reservations stay; at purge the last 3 days move to `device_usage`, with no account | Carried as above |
| Clear data, reinstall, factory reset | Kept if the same account signs in | **New** (new id and key). Not detectable without Device Recall | Local state is gone |
| Backup and restore, new phone | Kept | New (id and key are never backed up) | Restored rules count as usual |
| Lost key, id taken by someone else | Kept | New installation (section 1) | — |
| Second-hand phone | The buyer's own | The phone keeps nothing across the seller's reset; without a reset, the buyer shares today's device ledger with the seller's last day | — |
| Modified or rooted client | Server-side, enforced | Server-side, enforced, but a client can invent new ids | Not enforceable |

**Reliably enforced:** the account ledger; the device ledger for as long as the app's data lives; no reuse of one
installation's id by another; purchase tokens bound to one account. **Only detectable:** account farms on fresh
installations (`many_accounts`, Play's `recentDeviceActivity` if it is ever needed), modified builds (Integrity).
**Not enforceable:** a person who resets or reinstalls *and* makes a new account each day gets a new allowance;
a modified client can protect as much as it likes on its own phone.

## 7. Security and privacy

- **Rate limits** (D1 buckets): per IP 300/min, per account per route (reserve 30/min), and now per installation
  (reserve 30/min across all its accounts, attest 5/hour).
- **Validation**: install ids must match `[A-Za-z0-9_-]{1,64}`; bodies over 64 KB are refused (`413 too_large`);
  non-object JSON is `400 bad_request`. Errors are `{ "error": "<code>" }`, plus quota numbers where useful.
- **Never logged or stored**: Firebase tokens, purchase tokens (hashed for lookup, as before), Integrity tokens,
  device private keys, targets, emails in events. `console.error` logs only route and error name.
- **Retention**:
  - `devices` (random id, public key, verdict): deleted after 180 days unseen.
  - `device_usage` (installation id, start, end, zone; no account): deleted 3 days after the interval ends, the
    furthest the quota ever looks back.
  - `abuse_events` (kind, installation id, account id): deleted after 30 days, and with the account.
  - `quota_reservations`: 35 days, as before; cascades with the account.
  - These run on their own clocks. The 30-day account deletion grace period only decides when the account (and with
    it its reservations, installations and `abuse_events`) is purged; it never extends any of the limits above.
- **Justification**: the device ledger exists only to stop the deletion-and-recreation loophole on one phone, holds
  no personal data beyond a random id and time intervals, and expires before it could describe anyone's habits.
- **False positives and appeal**: nothing here bans an account. A device refused for its key recovers by itself as
  a new installation. A `failed` Integrity verdict clears with a genuine one. A person who believes Free is wrongly
  limited writes to support (the address in Settings); support can read the relevant `abuse_events`, reset a device
  row (`UPDATE devices SET integrity = 'unknown' WHERE id = ?`) or delete a `device_usage` row, and replies within
  the retention window.

## 8. Threat model: what is left

| Attack | Status |
|---|---|
| Switch accounts on one phone to refill minutes | Closed (device ledger) |
| Switch accounts to add 5 more targets | Closed in the official app (carried targets hold slots) |
| Delete the account and make a new one, same install | Closed (grace period, `device_usage`) |
| Two requests, or two accounts, racing for the last minutes | Closed (two revisions, one transaction) |
| Replay a reservation, a proof, or an authorization | Closed (idempotency, proof bound to token and body, authorization bound to uid, install and commitment) |
| Spend or poison someone else's installation ledger | Closed once that id is bound to a key; before then, needs the victim's random id |
| Change the phone's clock or time zone | Closed on the server; the app's own clock policy is in `CommittedQuota.kt` |
| Reinstall or clear data, then a new account | **Open.** Each costs a reinstall and a new verified email. Only Device Recall could notice it. |
| Modified client: count targets wrongly, invent ids, skip proofs | **Open** for local enforcement; server limits still hold per id and per account. See "How a modified app skips Play Integrity" below. |
| Pause and resume a schedule to get its reservation back for free | Closed (a release frees the key; the next ask is charged) |
| Reuse another installation's idempotency key to borrow its authorization | Closed (`409 idempotency_conflict`) |

### How a modified app skips Play Integrity

Play Integrity here is a risk signal, not a gate, because Free must keep working on phones without Play services
and on development builds. A modified client can therefore:

1. **Never attest.** It ignores `device.attestNonce`; its installation stays `unknown`, which is not refused.
2. **Never sign.** Unsigned (unproved) installations are never offered a nonce, so they are never judged.
3. **Sign with its own software key.** The server checks possession of the bound key, not that the key lives in
   the Keystore (no key attestation), so a client can mint ids and keys freely. Each new id is a fresh device
   ledger; the account ledger still holds.
4. **Shed a failed verdict by starting over as a new id.** *Closed for the same account*: in `enforce`, an
   unjudged installation of an account that failed in the last 7 days is held back until it earns its own verdict.

Server-side options not taken, and why: requiring a verdict (or a proof) for every Free reservation would block
de-Googled phones, broken Keystores and old app versions, and still not stop items 1 to 3 with a new account.
Android key attestation (verifying the Keystore certificate chain, which names the signing app) would close item 3
for proved installations, but needs X.509 parsing in a dependency-free Worker and a policy for phones without it.
Revisit if `abuse_events` shows farms of fresh installations.
| Stolen Firebase token without the device key | Limited to unproved installations, or to a new id (its own fresh ledger, the victim's account ledger) |
| Emulator farms | Integrity marks them `failed` or `basic`; `enforce` holds `failed` back from Free |

## 9. Rollout and rollback

Flags are Worker vars (`server/wrangler.toml`). The schema change is additive; the previous Worker ignores it.

1. **Schema and observability.** `wrangler d1 migrations apply aurora --remote` (0002 backfills a device row for
   every known installation, unproved; 0003 frees the keys of already-released reservations). Deploy with `DEVICE_QUOTA = "shadow"`, `INTEGRITY = "off"`. Watch
   `SELECT kind, COUNT(*) FROM abuse_events GROUP BY kind` for a week: `device_quota_shadow` is what enforcement
   would refuse.
2. **Dual quota.** `DEVICE_QUOTA = "enforce"`. Existing reservations already name their installation, so the
   device ledger is complete from the first request. Watch `device_quota_refused` and support mail.
3. **Android device credentials and switching protection.** Ship the app build. Its first sync binds each key.
   Watch `device_proof_failed`: legitimate ones are Keystore failures, which recover as new installations.
4. **Play Integrity.** Link the Cloud project in Play Console, set `aurora.playCloudProjectNumber` for release
   builds, grant the service account Play Integrity access, set `APP_CERT_SHA256` (base64url, see `docs/ANTI_ABUSE_STAGING_TESTS.md` T5), then `INTEGRITY = "observe"`.
   After a few weeks of verdicts, `enforce`.
5. **Device Recall**, only once access is approved (section 5).
6. **Hardening.** Review rate limits against real traffic; alert on `abuse_events` spikes; keep the retention
   jobs running (daily cron).

**Rollback**, any stage: set the flag back (`DEVICE_QUOTA = "shadow"` or `"off"`, `INTEGRITY = "off"`) and
`wrangler deploy`, or `wrangler rollback` to the previous Worker. No migration needs reverting; the new tables can
stay. App builds with device keys work against the old Worker (it ignores the headers). Reservations made under
enforcement stay valid either way.

Not production-ready until stages 1 to 3 have run on staging with real Keystore keys and real D1, and the full
server and Android suites pass there; the automated tests here use a SQLite stand-in for D1 and Robolectric
without a Keystore. The steps are in `docs/ANTI_ABUSE_STAGING_TESTS.md`.
