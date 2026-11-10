# Trial and lifetime records: retention review (draft, 2026-11-10)

Status: **for the founder's and counsel's review. Nothing here is approved.** The server ships with `TRIAL = "off"`, and
it stays off until this review is settled and the privacy policy is updated. The three-year figure in the code
(`TRIAL_RETENTION_MS` in `server/src/trial.js`) is a placeholder. Changing the period means changing that one constant.

This is an engineering summary. It is not legal advice. The legal basis has to be confirmed by counsel for each market
Aurora sells in. Today that is India and the United States, and the EU/UK if the app is offered there.

## 1. What is kept, and where

| Record | Fields | While the account exists | After the account is purged (30 days after a deletion request) |
|---|---|---|---|
| `trial_claims` | `uid_hash`, `email_hash` (HMAC-SHA-256 with the server secret `TRIAL_PEPPER`, over the Firebase UID and the normalized verified address), `installation_id`, `platform`, `evidence`, `granted_at`, `expires_at`, `revoked_at` | Linked to the account | `account_id` is set to NULL. The two hashes, the installation id and the dates stay until the retention period ends |
| `entitlement_events` | `account_id`, `kind` (for example `trial_granted`, `trial_refused`, `lifetime_slot`, `subscription_voided`), `detail` (a reason, slot number or region), `created_at` | Linked | `account_id` is set to NULL. Today these rows are dropped on the same clock as trial claims |
| `lifetime_slots` | `purchase_key` (hash of the store token), `store`, `slot`, `status`, `region`, `committed_at`, `refunded_at` | Linked | `account_id` is set to NULL. Kept indefinitely, so the 1,000-slot count stays true. It holds no address and no UID |
| `purchases` | Token hash, product, state, dates | Linked | `account_id` is set to NULL. Kept so that a store notification can still be matched to the purchase |
| Apple DeviceCheck (held by Apple) | `bit0` = "an Aurora trial was granted on this device" | Persists | Persists until Aurora resets it. Apple's documentation doesn't say whether it survives an erase |
| Google Device Recall (held by Google; Android trials are off until this is enabled) | `bitFirst` = "an Aurora trial was granted on this phone" | Persists | Google keeps the bits for 3 years after the last read or write (Google's documentation, read 2026-11-10) |
| App Attest | Nothing is stored. Each attestation is checked once against a one-use nonce. A failed check writes an `abuse_events` row, which is kept for 30 days | — | — |

The keyed hashes are still **personal data** (pseudonymised, not anonymous). Aurora holds the key, so hashing a
known address finds its claim.

## 2. Why the records are needed

- **Repeat-trial prevention.** Without a record that outlives the account, someone can delete their account, sign up
  again with the same address and get a new 14-day trial each time. The hashes are the minimum that blocks this, and the
  address itself is never stored in the claim. The installation id blocks a second account on the same installation.
- **Founding Lifetime integrity.** The 1,000-slot promise and refund handling need a durable count. Those rows hold no
  address or UID once the account is purged.
- **Disputes and support.** `entitlement_events` explains why an account was refused a trial or given a slot. While the
  account exists, this record is useful. After deletion its value is low.

## 3. Possible legal bases (for counsel)

- **EU/UK GDPR:** legitimate interests (Art. 6(1)(f)). Recital 47 names fraud prevention as a legitimate interest.
  This needs a written legitimate-interests assessment, a stated retention period and a right to object. Art. 17(3)
  allows retention after an erasure request only where a basis like this applies, so it must be disclosed.
- **India DPDP Act 2023 and Rules:** processing mostly rests on consent. The Act requires erasure once the purpose is
  served, and there is no general "legitimate interests" ground. Counsel must confirm whether keeping a hash after a
  deletion request is allowed. One option is consent at the moment of claiming the trial, with the retention stated
  there ("To give each person one trial, Aurora keeps a coded record of this address for N months after the trial
  ends").
- **US (CCPA/CPRA, if thresholds apply):** disclosure in the notice at collection, and a fraud-prevention exception to
  deletion.

## 4. Retention options

| Option | Abuse prevented | Privacy cost | Notes |
|---|---|---|---|
| A. 3 years after the trial ends (current placeholder) | Repeat trials for 3 years | Highest | Matches Google's Device Recall retention, but nothing requires the two to match |
| B. 24 months | Most repeat attempts | Medium | |
| C. 12 months | Yearly re-trial at most | Lowest | A 14-day trial once a year costs little. This is the easiest period to justify as proportionate |

Engineering recommendation for discussion: **12 months after the trial ends** for `trial_claims`, and **12 months after
creation** for `entitlement_events` of purged accounts. Every option also needs a deletion path:

- On a verified request after account purge (for example, "erase my trial record"), the server hashes the given
  address and deletes the matching claim. **This is not built yet.** It is one admin script, and it would let the person
  have a new trial, which is an accepted trade-off.
- Apple/Google bits: reset on request via `update_two_bits` (DeviceCheck) or `deviceRecall:write` with `false`. This
  needs the device's token, so it can only happen from the app on that device.

## 5. Proposed privacy-policy changes (draft text, not published)

Add to section 2 (Accounts):

> **Free trial.** When you start the Aurora Plus free trial, Aurora keeps a coded record (a keyed hash) of your account
> identifier and verified email address, the installation it was started on, and when it started and ends. On iPhone,
> Aurora also asks Apple's DeviceCheck service to remember that this device has had an Aurora trial. On Android, where
> trials are offered, Aurora asks Google Play's Device Recall service to remember the same. Apple and Google store only
> that one fact, not your address. We use these records only to give each person and each device one trial. If you
> delete your account, the coded record is kept for **[N months]** after your trial ended and then erased. Google keeps
> its record for up to 3 years after it was last used. You can ask us to erase the coded record sooner; you may then
> be offered a trial again.
>
> **Founding Lifetime.** A Founding Lifetime purchase is numbered among the first 1,000. The number, the store, the
> country of purchase and the purchase dates are kept for as long as Aurora Plus is offered, without your address or
> account once your account is deleted.
>
> **Purchase checks.** Aurora asks Apple's App Attest service to confirm that a trial request comes from the genuine
> Aurora app. Aurora checks the answer once and does not keep it.

Change in section 6 (Data purge): "...permanently erased from our databases, **except** the anonymous installation
intervals described above (erased within three days), the coded trial record (erased **[N months]** after the trial
ended), and Founding Lifetime slot records, which hold no address or account."

The policy also needs to say "Android and iPhone", not only Android, before the iOS release.

## 6. Code changes once the period is decided

1. Set `TRIAL_RETENTION_MS` to the approved period.
2. Give `entitlement_events` its own constant. Today it shares the trial constant: see `housekeeping` in `worker.js`.
3. Add the admin erase script from section 4.
4. Publish the policy, then set `TRIAL = "on"`.
