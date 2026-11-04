# Manual Account Deletion Runbook

This runbook instructs Aurora operators on how to handle user deletion requests submitted via email or the web portal (`docs/account-deletion.html`).

---

## 1. Verification of Ownership

Never delete or modify user data without verifying ownership of the associated account:
1. Confirm the request email matches an existing account in Firebase Authentication:
   ```sh
   # Firebase Admin SDK or Console:
   # Look up user by email in project: <FIREBASE_PROJECT_ID>
   ```
2. Send a confirmation message containing a unique verification token or link back to the requester's email address.
3. Only proceed once the user replies from the registered email confirming deletion.

---

## 2. Execute Cloudflare D1 Deletion

Use the server's own deletion path, so the purge does what the app's does: installations and reservations go with
the account, and only the anonymous last days of committed time stay with each installation (`docs/ANTI_ABUSE.md`).
Deleting rows by hand would hand that phone a fresh Free allowance, and leave orphaned data behind.

```sh
cd server

# Find the account (D1 database "aurora", schema in migrations/):
npx wrangler d1 execute aurora --remote --command="SELECT id, firebase_uid, status FROM accounts WHERE email = 'user@example.com';"

# Standard: start the 30-day grace period, exactly as Settings does.
npx wrangler d1 execute aurora --remote --command="
  UPDATE accounts SET status = 'deletion_requested', deletion_requested_at = COALESCE(deletion_requested_at, unixepoch('now') * 1000)
  WHERE id = '<account id>';"

# Immediate (an explicit erasure request): backdate the request; the next daily run (03:17 UTC) deletes the
# Firebase user and the account.
npx wrangler d1 execute aurora --remote --command="
  UPDATE accounts SET status = 'deletion_requested', deletion_requested_at = 0 WHERE id = '<account id>';"
```

---

## 3. Delete Firebase Authentication User

Delete the user identity record in Firebase Authentication so credentials and tokens can no longer authenticate:
```sh
# Using Firebase CLI / Admin SDK:
firebase auth:delete <FIREBASE_UID>
```

---

## 4. Notify User & Close Request

Send a final confirmation notice to the requester stating that their cloud account, registered devices, quota history, and authentication records have been permanently erased. Inform them that any active recurring subscriptions must be cancelled through Google Play Subscriptions on their Google account.
