// Founding Lifetime Plus: one inventory of 1,000 slots across both stores (docs/MONETIZATION_PLAN.md, 3.4).
//
// The store checkout and this ledger can't be one transaction. So: a hold before the store sheet opens keeps the
// offer from being shown to more people than there are slots, and the slot itself is numbered only when the purchase
// is verified with the store. A verified purchase that finds no slot is honored anyway (founder, 2026-11-10) and
// recorded as overflow. Slots are never recycled, refund or not.

export const LIFETIME_UNTIL = 253402300799000 // 9999-12-31T23:59:59Z: "paid through" for a purchase that never ends.
export const HOLD_MS = 15 * 60_000

export const lifetimeOn = (env) => env.LIFETIME === 'on'
export const playLifetimeId = (env) => env.LIFETIME_PRODUCT_ID || 'aurora_plus_lifetime_us'
export const appleLifetimeId = (env) => env.APPLE_LIFETIME_PRODUCT_ID || 'aurora_plus_lifetime_us'
// The store's billing country, as each store spells the United States.
export const inUS = (region) => region === 'US' || region === 'USA'

export const lifetimeEntitlement = (productId, environment = null) => ({ productId, tier: 'plus', state: 'lifetime', paidThrough: LIFETIME_UNTIL, autoRenewing: false, known: true, environment })

const counts = `(SELECT COUNT(*) FROM lifetime_slots WHERE slot IS NOT NULL)`

/** 'off', 'owned', 'held' (this account holds a slot now), 'available' or 'sold_out'. Never a count: no scarcity copy. */
export async function offerFor(env, account, now) {
  if (!lifetimeOn(env)) return 'off'
  const owned = await env.DB.prepare("SELECT environment FROM purchases WHERE account_id = ? AND state = 'lifetime' AND tier = 'plus'").bind(account.id).all()
  if (owned.results.some((r) => r.environment !== 'Sandbox')) return 'owned'
  const row = await env.DB.prepare(
    `SELECT i.cap, i.open, ${counts} AS used,
       (SELECT COUNT(*) FROM lifetime_holds WHERE expires_at > ?2 AND account_id <> ?1) AS others,
       (SELECT expires_at FROM lifetime_holds WHERE account_id = ?1 AND expires_at > ?2) AS mine
     FROM lifetime_inventory i WHERE i.id = 1`,
  ).bind(account.id, now).first()
  if (row.mine) return 'held'
  return row.open && row.used + row.others < row.cap ? 'available' : 'sold_out'
}

/**
 * Holds a slot for [HOLD_MS] while the store sheet is open. Refreshing an own hold is fine; holds plus numbered slots
 * never exceed the cap. Expired holds are swept by the same batch.
 */
export async function holdSlot(env, account, now) {
  const [, inserted] = await env.DB.batch([
    env.DB.prepare('DELETE FROM lifetime_holds WHERE account_id = ? OR expires_at <= ?').bind(account.id, now),
    env.DB.prepare(
      `INSERT INTO lifetime_holds (account_id, expires_at, created_at)
       SELECT ?1, ?2, ?3 FROM lifetime_inventory i
       WHERE i.id = 1 AND i.open = 1 AND ${counts} + (SELECT COUNT(*) FROM lifetime_holds WHERE expires_at > ?3) < i.cap`,
    ).bind(account.id, now + HOLD_MS, now),
  ])
  return inserted.meta.changes > 0 ? now + HOLD_MS : null
}

/**
 * Numbers the slot for a verified purchase, once: the same purchase verified again (another phone, a retry, a
 * notification) finds its row. One statement decides and writes, so concurrent commits from both stores can't take
 * the same number or pass the cap; UNIQUE(slot) backs that up. Closes sales when the last slot goes.
 */
export async function commitSlot(env, { key, accountId, store, region, test }, now) {
  await env.DB.batch([
    env.DB.prepare(
      `INSERT OR IGNORE INTO lifetime_slots (purchase_key, account_id, store, slot, status, region, committed_at)
       SELECT ?1, ?2, ?3, CASE WHEN k.kind = 'slot' THEN k.used + 1 END, k.kind, ?4, ?5
       FROM (SELECT ${counts} AS used, CASE
         WHEN ?6 THEN 'test'
         WHEN EXISTS (SELECT 1 FROM lifetime_slots WHERE account_id = ?2 AND status IN ('slot', 'overflow') AND refunded_at IS NULL) THEN 'duplicate'
         WHEN NOT ?7 THEN 'overflow'
         WHEN ${counts} < (SELECT cap FROM lifetime_inventory WHERE id = 1) THEN 'slot'
         ELSE 'overflow' END AS kind) k`,
    ).bind(key, accountId, store, region ?? null, now, test ? 1 : 0, inUS(region) ? 1 : 0),
    env.DB.prepare('DELETE FROM lifetime_holds WHERE account_id = ?').bind(accountId),
    env.DB.prepare(`UPDATE lifetime_inventory SET open = 0 WHERE id = 1 AND open = 1 AND ${counts} >= cap`),
  ])
  return env.DB.prepare('SELECT slot, status FROM lifetime_slots WHERE purchase_key = ?').bind(key).first()
}

/** A refund or revocation: Plus ends, the slot stays taken. */
export const refundStatements = (env, key, state, now) => [
  env.DB.prepare("UPDATE purchases SET tier = 'free', state = ?, paid_through = 0, auto_renewing = 0, checked_at = ? WHERE token_hash = ?").bind(state, now, key),
  env.DB.prepare('UPDATE lifetime_slots SET refunded_at = COALESCE(refunded_at, ?) WHERE purchase_key = ?').bind(now, key),
]

// ---- Google Play one-time product --------------------------------------------------------------------------------

/**
 * What Play says about a one-time purchase (purchases.products.get): purchaseState 0 purchased, 1 canceled, 2 pending;
 * purchaseType 0 is a license tester. Null when Google doesn't know the token.
 */
export function playLifetimeFrom(p) {
  if (!p) return null
  return {
    purchased: p.purchaseState === 0,
    pending: p.purchaseState === 2,
    region: typeof p.regionCode === 'string' ? p.regionCode : null,
    test: p.purchaseType === 0,
    external: p.obfuscatedExternalAccountId ?? null,
  }
}
