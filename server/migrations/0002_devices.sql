-- Device-level Free allowance and anti-abuse signals (docs/ANTI_ABUSE.md). Additive only: the previous worker
-- version ignores every table and index here, so rolling the code back needs no schema change.

-- One row per installation, across every account that has used it. The id is the app's random install id; it is
-- not a hardware id and says nothing about a person. A key, once bound, has to sign every request for this id.
CREATE TABLE devices (
  id TEXT PRIMARY KEY,
  public_key TEXT,                -- SPKI, base64. ECDSA P-256 from the Android Keystore. NULL: never proved.
  key_bound_at INTEGER,
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  -- Bumped by every write to this installation's ledger, like accounts.quota_revision.
  quota_revision INTEGER NOT NULL DEFAULT 0,
  integrity TEXT NOT NULL DEFAULT 'unknown' CHECK (integrity IN ('unknown', 'ok', 'basic', 'failed')),
  integrity_at INTEGER,
  attest_nonce TEXT,              -- One-use Play Integrity nonce, and when it stops being accepted.
  attest_nonce_expires_at INTEGER
);

-- Every installation already known becomes a device, unproved. Its reservations already name it.
INSERT OR IGNORE INTO devices (id, created_at, last_seen_at)
  SELECT id, MIN(registered_at), MAX(last_seen_at) FROM installations GROUP BY id;

CREATE INDEX quota_installation_end ON quota_reservations (installation_id, end_at);

-- A deleted account's last days on an installation, with nothing that says whose: deleting an account can't
-- hand the same phone a new allowance. Kept no longer than the quota can look back (housekeeping).
CREATE TABLE device_usage (
  installation_id TEXT NOT NULL,
  start_at INTEGER NOT NULL,
  end_at INTEGER NOT NULL,
  zone TEXT NOT NULL
);
CREATE INDEX device_usage_installation ON device_usage (installation_id, end_at);

-- Suspected abuse, minimal and short-lived: what happened, to which installation and account, never tokens,
-- targets or keys. Deleted after 30 days, and with the account.
CREATE TABLE abuse_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  kind TEXT NOT NULL,
  installation_id TEXT,
  account_id TEXT REFERENCES accounts(id) ON DELETE CASCADE,
  detail TEXT,
  created_at INTEGER NOT NULL
);
CREATE INDEX abuse_events_created ON abuse_events (created_at);
