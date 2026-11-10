-- The 14-day Aurora Plus trial (docs/MONETIZATION_PLAN.md, section 3.2). Additive: the previous Worker ignores it.
-- Apply before deploying the Worker that reads it.
--
-- One trial per account, per verified address and per installation, each held by a UNIQUE constraint, so two racing
-- claims can't both win. A claim outlives its account (account_id is set NULL on purge) with only keyed hashes left,
-- so deleting an account and signing up again with the same address finds the claim. Housekeeping drops claims
-- whose account is gone three years after they ended.
CREATE TABLE trial_claims (
  id TEXT PRIMARY KEY,
  account_id TEXT UNIQUE REFERENCES accounts(id) ON DELETE SET NULL,
  uid_hash TEXT NOT NULL UNIQUE,         -- HMAC(TRIAL_PEPPER, 'uid:' + Firebase UID)
  email_hash TEXT NOT NULL UNIQUE,       -- HMAC(TRIAL_PEPPER, 'email:' + normalized verified address)
  installation_id TEXT NOT NULL UNIQUE,  -- The installation it was granted on. One trial per installation, any account.
  platform TEXT NOT NULL CHECK (platform IN ('android', 'ios')),
  evidence TEXT NOT NULL,                -- What vouched for the device: 'play_integrity', 'devicecheck'.
  granted_at INTEGER NOT NULL,           -- The server's clock when the grant committed. The trial starts here.
  expires_at INTEGER NOT NULL CHECK (expires_at > granted_at),
  revoked_at INTEGER                     -- Support or fraud review. Ends the trial; never deletes the claim.
);

-- Play's licensing verdict from the last attestation. NULL: never said.
ALTER TABLE devices ADD COLUMN licensed INTEGER;

-- What changed an account's entitlement and why: trial grants and refusals, lifetime slots, over-cap honors. No
-- tokens, no addresses. Kept with the trial claims' retention.
CREATE TABLE entitlement_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  account_id TEXT REFERENCES accounts(id) ON DELETE SET NULL,
  kind TEXT NOT NULL,
  detail TEXT,
  created_at INTEGER NOT NULL
);
CREATE INDEX entitlement_events_created ON entitlement_events (created_at);
