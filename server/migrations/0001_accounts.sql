-- Aurora accounts (docs/ACCOUNT_SYSTEM.md). No app usage, targets, Goals or browsing history live here.

CREATE TABLE accounts (
  id TEXT PRIMARY KEY,
  firebase_uid TEXT NOT NULL UNIQUE,
  email TEXT,
  display_name TEXT,
  photo_url TEXT,
  created_at INTEGER NOT NULL,
  last_auth_at INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'deletion_requested')),
  deletion_requested_at INTEGER,
  -- Bumped by every ledger write: a quota decision only commits if nothing changed since it read the ledger.
  quota_revision INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE installations (
  id TEXT NOT NULL,               -- Random, made by the app. Never a hardware or advertising id.
  account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  label TEXT,                     -- The phone's model, so a person can tell their devices apart.
  registered_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  revoked_at INTEGER,
  PRIMARY KEY (account_id, id)
);

-- One Google Play purchase belongs to at most one account. A hash, so this table can't be replayed against Google.
CREATE TABLE purchases (
  token_hash TEXT PRIMARY KEY,
  account_id TEXT REFERENCES accounts(id) ON DELETE SET NULL,
  product_id TEXT,
  tier TEXT NOT NULL DEFAULT 'free',
  state TEXT NOT NULL,
  paid_through INTEGER NOT NULL DEFAULT 0,
  auto_renewing INTEGER NOT NULL DEFAULT 0,
  checked_at INTEGER NOT NULL,
  event_at INTEGER NOT NULL DEFAULT 0,
  bound_at INTEGER NOT NULL,
  transferred_at INTEGER
);
CREATE INDEX purchases_account ON purchases (account_id);

-- The authoritative Free allowance. Whole reserved intervals; never refunded once started.
CREATE TABLE quota_reservations (
  id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  installation_id TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  commitment_id TEXT NOT NULL,
  mode TEXT NOT NULL CHECK (mode IN ('cooldown', 'always_block')),
  start_at INTEGER NOT NULL,
  end_at INTEGER NOT NULL,
  zone TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'reserved' CHECK (status IN ('reserved', 'released', 'migrated')),
  created_at INTEGER NOT NULL,
  UNIQUE (account_id, idempotency_key)
);
CREATE INDEX quota_account_end ON quota_reservations (account_id, end_at);

CREATE TABLE rate_limits (
  bucket TEXT PRIMARY KEY,
  count INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
