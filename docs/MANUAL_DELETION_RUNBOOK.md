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

Once verified, schedule or immediately execute the deletion in Cloudflare D1:

```sh
cd server

# Find the account record:
npx wrangler d1 execute aurora-production --command="SELECT id, email, tier FROM accounts WHERE email = 'user@example.com';"

# Obtain the user ID (e.g., 'usr_12345').

# Schedule standard 30-day grace period deletion:
npx wrangler d1 execute aurora-production --command="
  INSERT INTO deletion_requests (uid, requested_at, delete_at)
  VALUES ('usr_12345', unixepoch('now') * 1000, (unixepoch('now') + 30 * 86400) * 1000)
  ON CONFLICT(uid) DO UPDATE SET requested_at = excluded.requested_at, delete_at = excluded.delete_at;
"

# OR execute immediate purge (if requested explicitly under GDPR / CCPA right to erasure):
npx wrangler d1 execute aurora-production --command="
  DELETE FROM quota_reservations WHERE uid = 'usr_12345';
  DELETE FROM quota_ledger WHERE uid = 'usr_12345';
  DELETE FROM purchase_transfers WHERE old_uid = 'usr_12345' OR new_uid = 'usr_12345';
  DELETE FROM purchases WHERE uid = 'usr_12345';
  DELETE FROM installations WHERE uid = 'usr_12345';
  DELETE FROM deletion_requests WHERE uid = 'usr_12345';
  DELETE FROM accounts WHERE id = 'usr_12345';
"
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
