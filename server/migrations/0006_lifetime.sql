-- Founding Lifetime Plus: 1,000 numbered slots across Google Play and the App Store together (docs/MONETIZATION_PLAN.md,
-- section 3.4). Additive. The entitlement itself is an ordinary purchases row (state 'lifetime', paid through 9999);
-- these tables only count and number the slots.

CREATE TABLE lifetime_inventory (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  cap INTEGER NOT NULL CHECK (cap > 0),
  open INTEGER NOT NULL DEFAULT 1 CHECK (open IN (0, 1))   -- 0: no new holds. Set by the server at the cap, or by hand.
);
INSERT INTO lifetime_inventory (id, cap, open) VALUES (1, 1000, 1);

-- A slot held for one account while its store sheet is open. Counted against the cap until it expires.
CREATE TABLE lifetime_holds (
  account_id TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
  expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX lifetime_holds_expires ON lifetime_holds (expires_at);

-- One row per verified lifetime purchase. slot is 1..cap for a founding slot, NULL otherwise:
--   'slot'      numbered, counts toward the cap; stays consumed after a refund (slots are never recycled)
--   'overflow'  verified after the cap was reached, or outside the US: honored, not numbered (founder, 2026-11-10)
--   'duplicate' a second lifetime purchase by an account that already has one: honored, flagged for a refund
--   'test'      a Play license-tester purchase: Plus for testing, never a slot
-- Rows outlive their account (account_id SET NULL), so a deleted account never frees a slot.
CREATE TABLE lifetime_slots (
  purchase_key TEXT PRIMARY KEY,        -- purchases.token_hash
  account_id TEXT REFERENCES accounts(id) ON DELETE SET NULL,
  store TEXT NOT NULL CHECK (store IN ('google_play', 'app_store')),
  slot INTEGER UNIQUE CHECK (slot BETWEEN 1 AND 1000),
  status TEXT NOT NULL CHECK (status IN ('slot', 'overflow', 'duplicate', 'test')),
  region TEXT,                          -- The store's billing country (Play regionCode, App Store storefront).
  committed_at INTEGER NOT NULL,
  refunded_at INTEGER,
  CHECK ((status = 'slot') = (slot IS NOT NULL))
);
CREATE INDEX lifetime_slots_account ON lifetime_slots (account_id);
