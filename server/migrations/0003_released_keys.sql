-- A released reservation gives its idempotency key back (worker.js, release), so asking again for the same
-- occurrence is charged again instead of replaying the released authorization. Frees the keys of reservations
-- released before that. Data only; the previous worker reads these rows the same way.
UPDATE quota_reservations SET idempotency_key = idempotency_key || ':released:' || id
  WHERE status = 'released' AND idempotency_key NOT LIKE '%:released:%';
