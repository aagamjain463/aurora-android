// Founding Lifetime Plus: 1,000 slots across both stores, honored past the cap, never recycled (MONETIZATION_PLAN.md 3.4).
import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { housekeeping } from '../src/worker.js'
import { appAccountToken } from '../src/apple.js'
import { LIFETIME_UNTIL, commitSlot } from '../src/lifetime.js'
import { idToken, verifySignature } from './fixture.js'
import { BUNDLE, appleWorld, jwsOf, notification, notify, transaction, trustedPki } from './apple-fixture.js'

const PLAY_ID = 'aurora_plus_lifetime_us'
const APPLE_ID = 'aurora_plus_lifetime_us'
const ext = (uid) => createHash('sha256').update(uid).digest('hex')

function lifetimeWorld() {
  const w = appleWorld()
  w.env.LIFETIME = 'on'
  w.appleTx = new Map()
  w.state.appleTx = async (url) => {
    const tx = w.appleTx.get(decodeURIComponent(url.split('/transactions/')[1]))
    return tx ? new Response(JSON.stringify({ signedTransactionInfo: jwsOf(tx, trustedPki) })) : new Response('{}', { status: 404 })
  }
  return w
}

const playPurchase = (uid, over = {}) => ({ purchaseState: 0, purchaseType: undefined, regionCode: 'US', obfuscatedExternalAccountId: ext(uid), acknowledgementState: 0, ...over })
const buyPlay = (w, uid, token, over = {}) => {
  w.state.products.set(token, playPurchase(uid, over))
  return w.call('/v1/verify', idToken(uid), { purchaseToken: token, productId: PLAY_ID, installId: `a-${uid}`, packageName: 'app.aurora' })
}
async function buyApple(w, uid, id, over = {}) {
  const tx = transaction({ transactionId: id, originalTransactionId: id, productId: APPLE_ID, type: 'Non-Consumable', storefront: 'USA', appAccountToken: await appAccountToken(uid), expiresDate: undefined, ...over })
  w.appleTx.set(id, tx)
  return w.call('/v1/verify/apple', idToken(uid), { signedTransaction: jwsOf(tx, trustedPki), installId: `i-${uid}`, bundleId: BUNDLE, productId: APPLE_ID })
}
const snap = (res) => {
  const signed = res.body.entitlement ?? res.body
  assert.ok(verifySignature(signed), 'signed')
  return JSON.parse(signed.payload)
}
const hold = (w, uid) => w.call('/v1/lifetime/hold', idToken(uid), { installId: `a-${uid}` })
const offer = async (w, uid) => (await w.call('/v1/account/sync', idToken(uid), { installId: `a-${uid}`, zone: 'UTC' })).body.lifetime.offer
const slots = (w) => w.DB.rows('SELECT * FROM lifetime_slots ORDER BY committed_at')
const fill = (w, n) => {
  const insert = w.DB.db.prepare("INSERT INTO lifetime_slots (purchase_key, store, slot, status, region, committed_at) VALUES (?, 'google_play', ?, 'slot', 'US', 0)")
  for (let i = 1; i <= n; i++) insert.run(`seed-${i}`, i)
}

test('Google Play lifetime: verified, numbered, Plus forever, and the same purchase twice is one slot', async () => {
  const w = lifetimeWorld()
  assert.equal(await offer(w, 'alice'), 'available')
  assert.equal((await hold(w, 'alice')).body.held, true)
  const res = await buyPlay(w, 'alice', 'ptok')
  assert.deepEqual(res.body.lifetime, { slot: 1, status: 'slot' })
  const s = snap(res)
  assert.equal(s.tier, 'plus')
  assert.equal(s.source, 'lifetime')
  assert.equal(s.paidThrough, LIFETIME_UNTIL)
  assert.equal(s.autoRenewing, false)
  assert.deepEqual((await buyPlay(w, 'alice', 'ptok')).body.lifetime, { slot: 1, status: 'slot' })
  assert.equal(slots(w).length, 1)
  assert.equal(w.DB.rows('SELECT * FROM lifetime_holds').length, 0, 'the hold is released by the commit')
  assert.equal(await offer(w, 'alice'), 'owned')
  assert.deepEqual((await hold(w, 'alice')).body, { held: false, offer: 'owned' })
})

test('slots 999, 1000 and 1001: two numbered, the third honored as overflow, and sales close', async () => {
  const w = lifetimeWorld()
  fill(w, 998)
  assert.deepEqual((await buyPlay(w, 'u999', 't999')).body.lifetime, { slot: 999, status: 'slot' })
  assert.deepEqual((await buyApple(w, 'u1000', '1000')).body.lifetime, { slot: 1000, status: 'slot' })
  assert.equal(w.DB.rows('SELECT open FROM lifetime_inventory')[0].open, 0, 'closed at the cap')
  const late = await buyPlay(w, 'u1001', 't1001')
  assert.deepEqual(late.body.lifetime, { slot: null, status: 'overflow' })
  assert.equal(snap(late).tier, 'plus', 'a verified purchase over the cap is honored')
  assert.equal(await offer(w, 'newcomer'), 'sold_out')
  assert.deepEqual((await hold(w, 'newcomer')).body, { held: false, offer: 'sold_out' })
  assert.equal(w.DB.rows("SELECT COUNT(*) AS n FROM lifetime_slots WHERE slot IS NOT NULL")[0].n, 1000)
  assert.equal(w.DB.rows("SELECT COUNT(*) AS n FROM entitlement_events WHERE kind = 'lifetime_overflow'")[0].n, 1)
})

test('2,000 concurrent commits from both stores number exactly 1,000 slots, 1..1000, no repeats', async () => {
  const w = lifetimeWorld()
  const now = Date.now()
  const results = await Promise.all(Array.from({ length: 2000 }, (_, i) =>
    commitSlot(w.env, { key: `k${i}`, accountId: null, store: i % 2 ? 'app_store' : 'google_play', region: i % 2 ? 'USA' : 'US', test: false }, now)))
  const numbered = results.filter((r) => r.status === 'slot').map((r) => r.slot).sort((a, b) => a - b)
  assert.equal(numbered.length, 1000)
  assert.deepEqual(numbered, Array.from({ length: 1000 }, (_, i) => i + 1))
  assert.equal(results.filter((r) => r.status === 'overflow').length, 1000)
})

test('end to end at the edge: 40 buyers on both stores race for the last 20 slots', async () => {
  const w = lifetimeWorld()
  fill(w, 980)
  const results = await Promise.all(Array.from({ length: 40 }, (_, i) => (i % 2 ? buyApple(w, `r${i}`, String(5000 + i)) : buyPlay(w, `r${i}`, `rt${i}`))))
  assert.ok(results.every((r) => snap(r).tier === 'plus'), 'every verified buyer has Plus')
  const got = results.map((r) => r.body.lifetime)
  assert.equal(got.filter((l) => l.status === 'slot').length, 20)
  assert.equal(new Set(got.filter((l) => l.slot).map((l) => l.slot)).size, 20)
  assert.ok(got.filter((l) => l.slot).every((l) => l.slot > 980 && l.slot <= 1000))
})

test('holds: never more holds plus slots than the cap; an expired hold frees its place; refreshing your own is fine', async () => {
  const w = lifetimeWorld()
  fill(w, 998)
  assert.equal((await hold(w, 'a')).body.held, true)
  assert.equal((await hold(w, 'a')).body.held, true, 'refresh')
  assert.equal((await hold(w, 'b')).body.held, true)
  assert.equal((await hold(w, 'c')).body.offer, 'sold_out')
  assert.equal(await offer(w, 'c'), 'sold_out')
  assert.equal(await offer(w, 'a'), 'held')
  const holds = await Promise.all(['d', 'e', 'f', 'g'].map((u) => hold(w, u)))
  assert.ok(holds.every((h) => !h.body.held))
  w.DB.db.prepare("UPDATE lifetime_holds SET expires_at = ? WHERE account_id = (SELECT id FROM accounts WHERE firebase_uid = 'b')").run(Date.now() - 1)
  assert.equal((await hold(w, 'c')).body.held, true, 'b let theirs lapse')
  w.env.LIFETIME = 'off'
  assert.deepEqual((await hold(w, 'z')).body, { held: false, offer: 'off' })
})

test('pending, canceled and unknown purchases grant nothing; Google down grants nothing', async () => {
  const w = lifetimeWorld()
  const pending = await buyPlay(w, 'p', 'pend', { purchaseState: 2 })
  assert.equal(snap(pending).tier, 'free')
  assert.equal(snap(pending).pending, true)
  assert.equal(snap(await buyPlay(w, 'c', 'canc', { purchaseState: 1 })).tier, 'free')
  const unknown = await w.call('/v1/verify', idToken('x'), { purchaseToken: 'nobody', productId: PLAY_ID, installId: 'a-x', packageName: 'app.aurora' })
  assert.equal(snap(unknown).purchaseValid, false)
  w.state.playDown = true
  assert.equal((await buyPlay(w, 'd', 'down')).status, 503)
  assert.equal(slots(w).length, 0)
})

test('ownership: a token bought under another account, or under none, can\'t be claimed; one purchase, one owner', async () => {
  const w = lifetimeWorld()
  w.state.products.set('stolen', playPurchase('victim'))
  const thief = await w.call('/v1/verify', idToken('thief'), { purchaseToken: 'stolen', productId: PLAY_ID, installId: 'a-thief', packageName: 'app.aurora' })
  assert.equal(thief.body.conflict, true)
  assert.equal(snap(thief).tier, 'free')
  const none = await buyPlay(w, 'n', 'noext', { obfuscatedExternalAccountId: undefined })
  assert.equal(none.body.claimRequired, true)
  assert.equal(snap(await buyPlay(w, 'victim', 'stolen')).tier, 'plus')
  assert.equal(slots(w).length, 1)
  // Apple: the transaction must carry this account's appAccountToken.
  const wrong = await buyApple(w, 'eve', '777', { appAccountToken: await appAccountToken('someone-else') })
  assert.equal(wrong.body.conflict, true)
  assert.equal(snap(wrong).tier, 'free')
})

test('buying twice (Play then App Store) keeps one slot and flags the second for a refund', async () => {
  const w = lifetimeWorld()
  await buyPlay(w, 'twice', 'first')
  const second = await buyApple(w, 'twice', '888')
  assert.deepEqual(second.body.lifetime, { slot: null, status: 'duplicate' })
  assert.equal(snap(second).tier, 'plus')
  assert.equal(w.DB.rows("SELECT COUNT(*) AS n FROM lifetime_slots WHERE slot IS NOT NULL")[0].n, 1)
})

test('outside the US or a license tester: honored, never a founding slot', async () => {
  const w = lifetimeWorld()
  assert.deepEqual((await buyPlay(w, 'in', 'india', { regionCode: 'IN' })).body.lifetime, { slot: null, status: 'overflow' })
  assert.deepEqual((await buyApple(w, 'gb', '999', { storefront: 'GBR' })).body.lifetime, { slot: null, status: 'overflow' })
  assert.deepEqual((await buyPlay(w, 'tester', 'lt', { purchaseType: 0 })).body.lifetime, { slot: null, status: 'test' })
  assert.equal(w.DB.rows("SELECT COUNT(*) AS n FROM lifetime_slots WHERE slot IS NOT NULL")[0].n, 0)
})

test('a Play refund (voided purchase) ends Plus; the slot stays consumed and the next buyer gets the next number', async () => {
  const w = lifetimeWorld()
  await buyPlay(w, 'r1', 'refundme')
  const rtdn = (note) => w.call('/v1/rtdn?token=secret', null, { message: { data: Buffer.from(JSON.stringify({ packageName: 'app.aurora', eventTimeMillis: String(Date.now()), ...note })).toString('base64') } })
  assert.equal((await rtdn({ voidedPurchaseNotification: { purchaseToken: 'refundme', orderId: 'GPA.1', productType: 2, refundType: 1 } })).status, 204)
  const after = await w.call('/v1/account/sync', idToken('r1'), { installId: 'a-r1', zone: 'UTC' })
  assert.equal(snap(after).tier, 'free')
  assert.equal(snap(after).state, 'refunded')
  assert.ok(slots(w)[0].refunded_at)
  assert.deepEqual((await buyPlay(w, 'r2', 'next')).body.lifetime, { slot: 2, status: 'slot' })
  // A canceled one-time notification is checked with Google first.
  w.state.products.set('next', playPurchase('r2', { purchaseState: 1 }))
  await rtdn({ oneTimeProductNotification: { version: '1.0', notificationType: 2, purchaseToken: 'next', sku: PLAY_ID } })
  assert.equal(snap(await w.call('/v1/account/sync', idToken('r2'), { installId: 'a-r2', zone: 'UTC' })).tier, 'free')
  // Forged notifications need the push secret.
  assert.equal((await w.call('/v1/rtdn?token=guess', null, {})).status, 403)
})

test('an App Store refund, confirmed by Apple\'s own record, ends Plus; the notification alone decides nothing', async () => {
  const w = lifetimeWorld()
  await buyApple(w, 'ar', '4242')
  const tx = w.appleTx.get('4242')
  // A notification claiming a refund that Apple's record doesn't show changes nothing.
  await notify(w, notification({ ...tx, revocationDate: Date.now() }, { notificationType: 'REFUND' }))
  assert.equal(snap(await w.call('/v1/account/sync', idToken('ar'), { installId: 'i-ar', zone: 'UTC' })).tier, 'plus')
  w.appleTx.set('4242', { ...tx, revocationDate: Date.now(), revocationReason: 0, signedDate: Date.now() + 1000 })
  await notify(w, notification({ ...tx, signedDate: Date.now() + 2000 }, { notificationType: 'REFUND', signedDate: Date.now() + 2000 }))
  assert.equal(snap(await w.call('/v1/account/sync', idToken('ar'), { installId: 'i-ar', zone: 'UTC' })).tier, 'free')
  assert.ok(slots(w)[0].refunded_at)
})

test('a revoked or wrong-type Apple transaction grants nothing; a sandbox purchase never takes a slot', async () => {
  const w = lifetimeWorld()
  assert.equal(snap(await buyApple(w, 'rv', '1', { revocationDate: Date.now() })).tier, 'free')
  assert.equal(snap(await buyApple(w, 'ty', '2', { type: 'Consumable' })).tier, 'free')
  w.env.APPLE_ALLOW_SANDBOX = '1'
  const sandbox = await buyApple(w, 'sb', '3', { environment: 'Sandbox' })
  assert.deepEqual(sandbox.body.lifetime, { slot: null, status: 'test' })
  assert.equal(snap(sandbox).tier, 'free', 'sandbox never grants production Plus')
})

test('deleting an account never frees its slot', async () => {
  const w = lifetimeWorld()
  await buyPlay(w, 'gone', 'g')
  w.DB.db.prepare("UPDATE accounts SET status = 'deletion_requested', deletion_requested_at = ?").run(Date.now() - 31 * 86_400_000)
  await housekeeping(w.env, w.fetcher)
  assert.equal(slots(w)[0].account_id, null)
  assert.deepEqual((await buyPlay(w, 'other', 'o')).body.lifetime, { slot: 2, status: 'slot' })
})
