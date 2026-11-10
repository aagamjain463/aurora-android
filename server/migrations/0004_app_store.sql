-- App Store purchases share the purchases table with Google Play's (docs/APP_STORE_BILLING.md). Additive: every existing
-- row is a Google Play purchase, and a Worker that doesn't know these columns keeps working.
-- An App Store row's token_hash is sha256("apple:" + environment + ":" + originalTransactionId).
--
-- Rollback: deploy the previous Worker, then, only once no App Store row needs keeping:
--   DELETE FROM purchases WHERE store = 'app_store';
--   ALTER TABLE purchases DROP COLUMN store; ALTER TABLE purchases DROP COLUMN store_ref; ALTER TABLE purchases DROP COLUMN environment;

ALTER TABLE purchases ADD COLUMN store TEXT NOT NULL DEFAULT 'google_play' CHECK (store IN ('google_play', 'app_store'));
-- The original transaction id, to ask Apple again without the app. Useless without our App Store Connect key.
ALTER TABLE purchases ADD COLUMN store_ref TEXT;
ALTER TABLE purchases ADD COLUMN environment TEXT;
