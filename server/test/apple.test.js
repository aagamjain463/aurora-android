// /v1/verify/apple, moves, notifications and the shared entitlement, against a faked Apple (test/apple-fixture.js).
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { appAccountToken, appleEntitlement, appleStatus } from '../src/apple.js'
import { housekeeping } from '../src/worker.js'
import { FakeD1, IDS, activeSub, idToken, verifySignature, world, PAST, FUTURE } from './fixture.js'
import {
  APPLE_IDS, BUNDLE, DAY, appleWorld, group, jwsOf, lastTransaction, makePki, moveApple, notification, notify, renewal, snapshotOf, subscribed, transaction, trustedPki, verifyApple,
} from './apple-fixture.js'

const sync = (w, uid, installId = 'ios-1') => w.call('/v1/account/sync', idToken(uid), { installId, zone: 'UTC' })
const tierOf = async (w, uid, installId) => snapshotOf(await sync(w, uid, installId)).tier
const buyGoogle = (w, uid, token = 'gtok', installId = 'android-1') => w.call('/v1/verify', idToken(uid), { purchaseToken: token, productId: IDS[0], installId, packageName: 'app.aurora' })
const reserve = (w, uid, minutes, installId = 'ios-1') => w.call('/v1/quota/reserve', idToken(uid), { installId, idempotencyKey: crypto.randomUUID(), commitmentId: crypto.randomUUID(), mode: 'always_block', minutes, zone: 'UTC' })
const rows = (w) => w.DB.rows('SELECT * FROM purchases')
const config = { bundleId: BUNDLE, productIds: APPLE_IDS }
const answerFor = (body, environment = 'Production') => ({ environment, body: { environment, bundleId: BUNDLE, ...body } })

// ---- A purchase ------------------------------------------------------------------------------------------------

test('a valid purchase: Plus for the account its signed token names, signed for this install', async () => {
  const w = appleWorld()
  const res = await verifyApple(w, 'alice', await subscribed(w, 'alice'))
  assert.equal(res.status, 200)
  const s = snapshotOf(res)
  assert.equal(s.tier, 'plus')
  assert.equal(s.state, 'active')
  assert.equal(s.purchaseValid, true)
  assert.equal(s.autoRenewing, true)
  assert.equal(s.uid, 'alice')
  assert.equal(s.installId, 'ios-1')
  assert.equal(s.store, 'app_store')
  assert.equal(s.productId, APPLE_IDS[0])
  assert.equal(s.packageName, 'app.aurora', 'the signed service identity both apps check, unchanged')
  assert.ok(verifySignature(res.body))
  const [row] = rows(w)
  assert.equal(row.store, 'app_store')
  assert.equal(row.store_ref, '2000')
  assert.equal(row.environment, 'Production')
  assert.equal(w.apple.calls.length, 1)
  assert.match(w.apple.calls[0], /^https:\/\/api\.storekit\.apple\.com\/inApps\/v1\/subscriptions\/2000$/)
})

test('an invalid signed transaction is refused, stores nothing, and never reaches Apple', async () => {
  const w = appleWorld()
  const genuine = await subscribed(w, 'alice')
  const [head, body, signature] = genuine.split('.')
  const unsigned = `${Buffer.from('{"alg":"none"}').toString('base64url')}.${body}.`
  const rogue = jwsOf(transaction(), makePki())
  const changed = `${head}.${Buffer.from(JSON.stringify(transaction({ originalTransactionId: '9' }))).toString('base64url')}.${signature}`
  for (const jws of [unsigned, rogue, changed, 'not-a-jws', '2000']) {
    const res = await verifyApple(w, 'alice', jws)
    assert.equal(res.status, 400, jws.slice(0, 20))
    assert.equal(res.body.error, 'invalid_transaction')
  }
  assert.equal(rows(w).length, 0)
  assert.equal(w.apple.calls.length, 0)
})

test('the platform identifier: each route checks its own, and a request can\'t declare itself out of it', async () => {
  const w = appleWorld()
  const jws = await subscribed(w, 'alice')
  for (const bundleId of ['app.aurora', 'com.other.App']) assert.equal((await verifyApple(w, 'alice', jws, { bundleId })).body.error, 'wrong_package', bundleId)
  assert.equal((await verifyApple(w, 'alice', jws, { bundleId: 'app.aurora', platform: 'android', packageName: BUNDLE })).body.error, 'wrong_package', 'claiming Android changes nothing')
  assert.equal((await verifyApple(w, 'alice', jws, { bundleId: undefined, packageName: BUNDLE })).status, 400, 'the old field is not an alternative')
  w.state.subs.set('gtok', activeSub())
  const android = await w.call('/v1/verify', idToken('alice'), { purchaseToken: 'gtok', productId: IDS[0], installId: 'a', packageName: BUNDLE, platform: 'ios' })
  assert.equal(android.body.error, 'wrong_package', 'Google\'s route never accepts the bundle id')
  assert.equal(rows(w).length, 0)
})

test('incorrect bundle id: in the phone\'s transaction, and in Apple\'s answer', async () => {
  const w = appleWorld()
  const foreign = await subscribed(w, 'alice', { tx: { bundleId: 'com.other.App' } })
  assert.equal((await verifyApple(w, 'alice', foreign)).body.error, 'wrong_package')
  const jws = jwsOf(transaction({ appAccountToken: await appAccountToken('alice') }), trustedPki)
  const res = await verifyApple(w, 'alice', jws) // Apple's stored answer still says com.other.App.
  assert.equal(snapshotOf(res).purchaseValid, false)
  assert.equal(snapshotOf(res).tier, 'free')
  w.apple.bundleId = 'com.other.App'
  assert.equal(snapshotOf(await verifyApple(w, 'alice', jws)).purchaseValid, false, 'the answer as a whole names another app')
  assert.equal(rows(w).length, 0)
})

test('incorrect product id grants nothing, and Google\'s ids aren\'t App Store ids', async () => {
  for (const productId of ['something_else', IDS[0]]) {
    const w = appleWorld()
    const res = await verifyApple(w, 'alice', await subscribed(w, 'alice', { tx: { productId } }))
    assert.equal(snapshotOf(res).tier, 'free', productId)
    assert.equal(snapshotOf(res).purchaseValid, false, productId)
    assert.equal(rows(w).length, 0, productId)
  }
})

test('a client-supplied productId counts for nothing', async () => {
  const w = appleWorld()
  const res = await verifyApple(w, 'alice', await subscribed(w, 'alice', { tx: { productId: 'something_else' } }), { productId: APPLE_IDS[0], tier: 'plus', expiresDate: Date.now() + DAY })
  assert.equal(snapshotOf(res).tier, 'free')
})

test('states that grant Plus, and those that don\'t, from Apple\'s signed fields alone', async () => {
  const now = Date.now()
  const of = (status, tx = {}, info = {}) => appleEntitlement(answerFor({ data: group(lastTransaction(status, transaction(tx), renewal(info))) }), '2000', config, now)
  const active = await of(1)
  assert.deepEqual([active.tier, active.state, active.autoRenewing], ['plus', 'active', true])
  const canceled = await of(1, {}, { autoRenewStatus: 0 })
  assert.deepEqual([canceled.tier, canceled.state, canceled.autoRenewing], ['plus', 'canceled', false], 'cancelled, and paid through its end')
  const grace = await of(4, { expiresDate: now - DAY }, { gracePeriodExpiresDate: now + DAY })
  assert.deepEqual([grace.tier, grace.state, grace.paidThrough], ['plus', 'in_grace_period', now + DAY])
  assert.equal((await of(4, { expiresDate: now - 2 * DAY }, { gracePeriodExpiresDate: now - DAY })).tier, 'free', 'grace over')
  assert.equal((await of(4, { expiresDate: now - DAY })).tier, 'free', 'grace with no date')
  const retry = await of(3, { expiresDate: now - DAY }, { isInBillingRetryPeriod: true })
  assert.deepEqual([retry.tier, retry.state], ['free', 'on_hold'])
  assert.deepEqual(await of(2, { expiresDate: now - DAY }).then((e) => [e.tier, e.state]), ['free', 'expired'])
  const revoked = await of(5, { revocationDate: now, revocationReason: 0 })
  assert.deepEqual([revoked.tier, revoked.state], ['free', 'revoked'])
  const refunded = await of(1, { revocationDate: now })
  assert.deepEqual([refunded.tier, refunded.state], ['free', 'revoked'], 'a revocation date overrules an active status')
  assert.equal((await of(1, { expiresDate: now - 1000 })).tier, 'free', 'an active status past its date')
  assert.equal((await of(1, { isUpgraded: true })).tier, 'free', 'this one was replaced')
  assert.equal((await of(99)).tier, 'free', 'a status we don\'t know')
  assert.equal((await of('1')).tier, 'free', 'a status of the wrong type')
  assert.equal((await of(1, { type: 'Non-Consumable' })).known, false)
  assert.equal((await of(1, { expiresDate: undefined })).known, false)
  assert.equal((await of(1, { bundleId: 'x' })).known, false)
})

test('the requested subscription is chosen by id, not by position, across groups and histories', async () => {
  const now = Date.now()
  const pick = (body, id = '2000') => appleEntitlement(answerFor(body), id, config, now)
  const other = transaction({ originalTransactionId: '7777', productId: APPLE_IDS[1], expiresDate: now + 300 * DAY })
  const otherItem = lastTransaction(1, other, renewal({ originalTransactionId: '7777' }))
  const mineExpired = lastTransaction(2, transaction({ expiresDate: now - DAY }))
  const mine = await pick({ data: group(otherItem, mineExpired) })
  assert.equal(mine.tier, 'free', 'someone else\'s active line, listed first, doesn\'t lend its state')
  assert.equal(mine.state, 'expired')
  assert.equal((await pick({ data: [{ lastTransactions: [otherItem] }, { lastTransactions: [lastTransaction(1)] }] })).tier, 'plus', 'second group')
  assert.equal((await pick({ data: group(otherItem) })).known, false, 'not in the answer at all')
  const twice = await pick({ data: group(lastTransaction(2, transaction({ expiresDate: now + 5 * DAY })), lastTransaction(1, transaction({ productId: APPLE_IDS[1], expiresDate: now + 9 * DAY }))) })
  assert.deepEqual([twice.tier, twice.productId], ['plus', APPLE_IDS[1]], 'a duplicate entry: Plus and the later date win')
  // A wrapper claims the id, but the signed transaction inside is another one.
  const lying = { ...otherItem, originalTransactionId: '2000' }
  assert.equal((await pick({ data: group(lying) })).known, false)
  // Renewal info for a different subscription.
  assert.equal((await pick({ data: group(lastTransaction(1, transaction(), renewal({ originalTransactionId: '7777' }))) })).known, false)
  assert.equal((await pick({ data: group(lastTransaction(1, transaction(), null)) })).state, 'canceled', 'no renewal info: never promises a renewal')
  // Malformed answers and unverifiable contents are an outage, never a verdict.
  for (const body of [{}, { data: 'x' }, { data: null }]) await assert.rejects(pick(body))
  assert.equal((await pick({ data: [null, 5, {}, { lastTransactions: 'x' }, { lastTransactions: [null] }] })).known, false)
  const forged = lastTransaction(1, transaction(), renewal(), makePki())
  await assert.rejects(pick({ data: group(forged) }), 'a forged line in Apple\'s answer')
  await assert.rejects(pick({ data: group({ originalTransactionId: '2000', status: 1, signedTransactionInfo: 'x' }) }))
})

test('duplicate verification is idempotent, including at once', async () => {
  const w = appleWorld()
  const jws = await subscribed(w, 'alice')
  const first = await verifyApple(w, 'alice', jws)
  const second = await verifyApple(w, 'alice', jws)
  assert.equal(snapshotOf(second).tier, 'plus')
  assert.equal(snapshotOf(second).paidThrough, snapshotOf(first).paidThrough)
  await Promise.all(Array.from({ length: 5 }, () => verifyApple(w, 'alice', jws)))
  assert.equal(rows(w).length, 1)
})

test('expiry, renewal and a product change all come from Apple on the next verification', async () => {
  const w = appleWorld()
  const jws = await subscribed(w, 'alice')
  await verifyApple(w, 'alice', jws)
  const later = Date.now() + 60 * DAY
  await subscribed(w, 'alice', { tx: { expiresDate: later, productId: APPLE_IDS[1], transactionId: '1002' }, info: { productId: APPLE_IDS[1] } })
  const renewed = snapshotOf(await verifyApple(w, 'alice', jws))
  assert.deepEqual([renewed.tier, renewed.paidThrough, renewed.productId], ['plus', later, APPLE_IDS[1]])
  assert.equal(rows(w).length, 1, 'same original transaction, same purchase')
  await subscribed(w, 'alice', { status: 2, tx: { expiresDate: Date.now() - 1000 } })
  assert.equal(snapshotOf(await verifyApple(w, 'alice', jws)).tier, 'free')
  w.DB.db.prepare('UPDATE purchases SET paid_through = ?').run(Date.now() - 1000)
  assert.equal(await tierOf(w, 'alice'), 'free', 'the clock alone also ends it')
})

test('an Apple outage says try again, changes nothing, and keeps what the account has', async () => {
  const w = appleWorld()
  const jws = await subscribed(w, 'alice')
  await verifyApple(w, 'alice', jws)
  for (const status of [500, 429, 401]) {
    w.apple.down = true
    w.apple.status = status
    const res = await verifyApple(w, 'alice', jws)
    assert.equal(res.status, 503, String(status))
    assert.equal(res.body.error, 'try_again')
    assert.equal(rows(w)[0].tier, 'plus')
  }
  w.apple.down = false
  w.apple.body = { data: 'nonsense' }
  assert.equal((await verifyApple(w, 'alice', jws)).status, 503, 'an answer of the wrong shape')
  w.apple.body = null
  const broken = async () => { throw new TypeError('network') }
  w.state.apple = broken
  assert.equal((await verifyApple(w, 'alice', jws)).status, 503, 'no answer at all')
  assert.equal(await tierOf(w, 'alice'), 'plus')
})

test('with Apple not configured the routes fail closed and Android is unaffected', async () => {
  const w = world()
  w.env.APPLE_BUNDLE_ID = BUNDLE // Partly configured counts as not configured.
  w.state.subs.set('gtok', activeSub())
  assert.equal(snapshotOf(await buyGoogle(w, 'alice')).tier, 'plus')
  assert.equal((await w.call('/v1/verify/apple', idToken('alice'), { signedTransaction: 'x', installId: 'i', bundleId: BUNDLE })).status, 503)
  assert.equal((await w.call('/v1/purchases/transfer/apple', idToken('alice'), { signedTransaction: 'x', installId: 'i' })).status, 403)
  assert.equal((await w.call('/v1/apple/notifications', null, { signedPayload: 'x' })).status, 503)
  await housekeeping(w.env, w.fetcher)
})

test('the App Store routes need a verified identity, and the .p8 works as a PEM or bare base64', async () => {
  const w = appleWorld()
  for (const path of ['/v1/verify/apple', '/v1/purchases/transfer/apple']) assert.equal((await w.call(path, null, {})).status, 401, path)
  assert.equal((await w.call('/v1/verify/apple', idToken('u', { email_verified: false }), {})).status, 403)
  for (const over of [{ signedTransaction: 'x'.repeat(16385) }, { installId: '' }, { bundleId: '' }]) {
    assert.equal((await verifyApple(w, 'alice', 'jws', over)).body.error, 'bad_request', JSON.stringify(over).slice(0, 30))
  }
  w.env.APPLE_PRIVATE_KEY = w.env.APPLE_PRIVATE_KEY.replace(/-----[A-Z ]+-----|\s/g, '')
  assert.equal(snapshotOf(await verifyApple(w, 'alice', await subscribed(w, 'alice'))).tier, 'plus')
})

// ---- Ownership -------------------------------------------------------------------------------------------------

test('incorrect account token: a transaction Apple signed for someone else binds nothing', async () => {
  const w = appleWorld()
  const res = await verifyApple(w, 'bob', await subscribed(w, 'alice'))
  assert.equal(snapshotOf(res).tier, 'free')
  assert.equal(res.body.conflict, true)
  assert.equal(res.body.claimRequired, undefined)
  assert.equal(rows(w).length, 0, 'not even unbound')
})

test('a client can\'t supply the token, in the body or by resigning its own transaction', async () => {
  const w = appleWorld()
  const aliceToken = await appAccountToken('alice')
  const jws = await subscribed(w, 'alice')
  assert.equal((await verifyApple(w, 'bob', jws, { appAccountToken: await appAccountToken('bob'), uid: 'alice' })).body.conflict, true)
  // A forged phone copy naming Bob points at Alice's real subscription: Apple's answer is what's read.
  const claim = jwsOf(transaction({ appAccountToken: await appAccountToken('bob') }), trustedPki)
  const res = await verifyApple(w, 'bob', claim)
  assert.equal(res.body.conflict, true)
  assert.equal(snapshotOf(res).tier, 'free')
  assert.equal(aliceToken, await appAccountToken('alice'))
})

test('a transaction claimed by another account stays with it, and the hint is masked', async () => {
  const w = appleWorld()
  const jws = await subscribed(w, 'alice')
  await verifyApple(w, 'alice', jws)
  const res = await verifyApple(w, 'bob', jws)
  assert.equal(res.body.conflict, true)
  assert.equal(res.body.conflictHint, 'a•••@example.com')
  assert.equal(snapshotOf(res).tier, 'free')
  assert.equal(await tierOf(w, 'alice'), 'plus', 'nothing moved')
  assert.equal(rows(w).length, 1)
})

test('account switching on one phone moves nothing, either way', async () => {
  const w = appleWorld()
  const jws = await subscribed(w, 'alice')
  await verifyApple(w, 'alice', jws)
  assert.equal((await verifyApple(w, 'bob', jws)).body.conflict, true)
  assert.equal(await tierOf(w, 'bob'), 'free')
  assert.equal(snapshotOf(await verifyApple(w, 'alice', jws)).tier, 'plus', 'back on the first account: Restore still works')
})

test('Apple purchases and Google tokens can\'t reach each other\'s rows', async () => {
  const w = appleWorld()
  const jws = await subscribed(w, 'alice')
  await verifyApple(w, 'alice', jws)
  // The string that hashes to the App Store row, offered as a Play token.
  assert.equal(snapshotOf(await w.call('/v1/verify', idToken('bob'), { purchaseToken: 'apple:2000', productId: IDS[0], installId: 'a', packageName: 'app.aurora' })).purchaseValid, false)
  assert.equal((await w.call('/v1/purchases/transfer', idToken('bob'), { purchaseToken: 'apple:2000', installId: 'a' })).status, 403)
  const note = { message: { data: btoa(JSON.stringify({ packageName: 'app.aurora', eventTimeMillis: String(Date.now()), subscriptionNotification: { purchaseToken: 'apple:2000' } })) } }
  assert.equal((await w.call('/v1/rtdn?token=secret', null, note)).status, 204)
  assert.equal(rows(w)[0].tier, 'plus', 'RTDN never writes an App Store row')
  assert.equal(await tierOf(w, 'alice'), 'plus')
})

test('bought on either store: Plus on both platforms, for the one account', async () => {
  const w = appleWorld()
  await verifyApple(w, 'alice', await subscribed(w, 'alice'))
  assert.equal(await tierOf(w, 'alice', 'android-phone'), 'plus', 'App Store purchase, Android phone')
  const android = snapshotOf(await sync(w, 'alice', 'android-phone'))
  assert.deepEqual([android.installId, android.store, android.packageName], ['android-phone', 'app_store', 'app.aurora'])

  w.state.subs.set('gtok', activeSub())
  await buyGoogle(w, 'bob')
  const ios = snapshotOf(await sync(w, 'bob', 'ios-phone'))
  assert.deepEqual([ios.tier, ios.installId, ios.store], ['plus', 'ios-phone', 'google_play'], 'Google Play purchase, iPhone')
})

test('expired Apple + active Google: Plus, and it shows the Google subscription', async () => {
  const w = appleWorld()
  await verifyApple(w, 'alice', await subscribed(w, 'alice', { status: 2, tx: { expiresDate: Date.now() - DAY } }))
  assert.equal(await tierOf(w, 'alice'), 'free')
  w.state.subs.set('gtok', activeSub())
  await buyGoogle(w, 'alice')
  const s = snapshotOf(await sync(w, 'alice'))
  assert.deepEqual([s.tier, s.state, s.store, s.productId], ['plus', 'active', 'google_play', IDS[0]])
})

test('active Apple + expired Google: Plus, and it shows the Apple subscription', async () => {
  const w = appleWorld()
  w.state.subs.set('gtok', { subscriptionState: 'SUBSCRIPTION_STATE_EXPIRED', lineItems: [{ productId: IDS[0], expiryTime: PAST() }] })
  await buyGoogle(w, 'alice')
  assert.equal(await tierOf(w, 'alice'), 'free')
  await verifyApple(w, 'alice', await subscribed(w, 'alice'))
  const s = snapshotOf(await sync(w, 'alice'))
  assert.deepEqual([s.tier, s.store, s.productId], ['plus', 'app_store', APPLE_IDS[0]])
})

test('a refunded Apple row with a far date can\'t mask, or pose as, a live Google one', async () => {
  const w = appleWorld()
  const jws = await subscribed(w, 'alice', { status: 5, tx: { revocationDate: Date.now(), expiresDate: Date.now() + 300 * DAY } })
  await verifyApple(w, 'alice', jws)
  const refunded = snapshotOf(await sync(w, 'alice'))
  assert.deepEqual([refunded.tier, refunded.state], ['free', 'revoked'])
  w.state.subs.set('gtok', activeSub())
  await buyGoogle(w, 'alice')
  const s = snapshotOf(await sync(w, 'alice'))
  assert.deepEqual([s.tier, s.store], ['plus', 'google_play'], 'the row with the later date isn\'t the one that counts')
})

test('both stores active: Plus until the later one, then the other carries on', async () => {
  const w = appleWorld()
  w.state.subs.set('gtok', activeSub())
  await buyGoogle(w, 'alice')
  await verifyApple(w, 'alice', await subscribed(w, 'alice', { tx: { expiresDate: Date.now() + 90 * DAY } }))
  assert.equal(snapshotOf(await sync(w, 'alice')).store, 'app_store')
  w.DB.db.prepare("UPDATE purchases SET paid_through = ? WHERE store = 'app_store'").run(Date.now() - 1000)
  assert.deepEqual(['plus', 'google_play'], await sync(w, 'alice').then(snapshotOf).then((s) => [s.tier, s.store]))
})

test('seats span both platforms: five installations, Android and iOS together', async () => {
  const w = appleWorld()
  await verifyApple(w, 'alice', await subscribed(w, 'alice'), { installId: 'ios-1' })
  for (const d of ['android-1', 'ios-2', 'android-2', 'ios-3']) assert.equal(await tierOf(w, 'alice', d), 'plus', d)
  const sixth = snapshotOf(await sync(w, 'alice', 'android-3'))
  assert.deepEqual([sixth.tier, sixth.state], ['free', 'too_many_devices'])
})

test('the quota follows the account: Apple Plus is unlimited anywhere, and a refund returns to the same ledger', async () => {
  const w = appleWorld()
  assert.equal((await reserve(w, 'alice', 120, 'android-1')).body.allowed, true)
  assert.equal((await reserve(w, 'alice', 1, 'ios-1')).body.allowed, false, 'Free\'s day is shared across platforms')
  const jws = await subscribed(w, 'alice')
  await verifyApple(w, 'alice', jws)
  const plus = await reserve(w, 'alice', 600, 'android-1')
  assert.deepEqual([plus.body.allowed, plus.body.tier], [true, 'plus'], 'App Store Plus, Android phone')
  assert.equal((await sync(w, 'alice')).body.quota, null)
  assert.equal(w.DB.rows('SELECT * FROM quota_reservations').length, 1, 'Plus writes no ledger')
  await subscribed(w, 'alice', { status: 5, tx: { revocationDate: Date.now() } })
  await notify(w, notification(transaction(), { notificationType: 'REFUND' }))
  const free = await reserve(w, 'alice', 1, 'ios-1')
  assert.deepEqual([free.body.allowed, free.body.tier], [false, 'free'], 'a refund can\'t reopen the used allowance')
  assert.equal((await sync(w, 'alice')).body.quota.remainingMs, 0)
})

// ---- Notifications ---------------------------------------------------------------------------------------------

const bought = async () => {
  const w = appleWorld()
  await verifyApple(w, 'alice', await subscribed(w, 'alice'))
  w.apple.calls.length = 0
  return w
}

test('a forged notification is turned away, and changes nothing', async () => {
  const w = await bought()
  await subscribed(w, 'alice', { status: 5, tx: { revocationDate: Date.now() } })
  const rogue = makePki()
  assert.equal((await notify(w, notification(transaction()), rogue)).status, 403, 'a chain to another root')
  // Apple's signature on the outside, an unsigned transaction inside.
  const unsigned = `${Buffer.from('{"alg":"none"}').toString('base64url')}.${Buffer.from(JSON.stringify(transaction())).toString('base64url')}.`
  assert.equal((await notify(w, notification(transaction(), { data: { bundleId: BUNDLE, environment: 'Production', signedTransactionInfo: unsigned } }))).status, 403)
  for (const body of [{}, { signedPayload: 'x.y.z' }, { signedPayload: 5 }]) assert.equal((await w.call('/v1/apple/notifications', null, body)).status, 403)
  const plain = await w.call('/v1/apple/notifications', null, { notificationType: 'REFUND', data: { originalTransactionId: '2000' } })
  assert.equal(plain.status, 403, 'an unsigned body claiming a refund')
  assert.equal((await w.call('/v1/apple/notifications', null, { signedPayload: 'x'.repeat(70_000) })).status, 413)
  assert.equal(w.apple.calls.length, 0, 'none of them cost a call to Apple')
  assert.equal(rows(w)[0].tier, 'plus')
})

test('renewal, expiry and refund all arrive by asking Apple, not by believing the message', async () => {
  const w = await bought()
  const later = Date.now() + 60 * DAY
  await subscribed(w, 'alice', { tx: { expiresDate: later } })
  assert.equal((await notify(w, notification(transaction()))).status, 200)
  assert.equal(rows(w)[0].paid_through, later)
  assert.equal(snapshotOf(await sync(w, 'alice', 'android-1')).paidThrough, later, 'an Android phone sees an App Store renewal')

  await new Promise((r) => setTimeout(r, 2))
  await subscribed(w, 'alice', { status: 2, tx: { expiresDate: Date.now() - 1000 } })
  await notify(w, notification(transaction(), { notificationType: 'EXPIRED', subtype: 'VOLUNTARY' }))
  assert.equal(await tierOf(w, 'alice'), 'free')

  await new Promise((r) => setTimeout(r, 2))
  await subscribed(w, 'alice')
  await notify(w, notification(transaction(), { notificationType: 'SUBSCRIBED' }))
  assert.equal(await tierOf(w, 'alice'), 'plus')

  await new Promise((r) => setTimeout(r, 2))
  await subscribed(w, 'alice', { status: 5, tx: { revocationDate: Date.now() } })
  await notify(w, notification(transaction(), { notificationType: 'REFUND' }))
  assert.equal(rows(w)[0].state, 'revoked')
  assert.equal(await tierOf(w, 'alice'), 'free')
})

test('Apple\'s word beats the message: what it claims never sets a state', async () => {
  const w = await bought()
  const message = transaction({ revocationDate: Date.now() })
  await notify(w, notification(message, { notificationType: 'REFUND', data: { bundleId: BUNDLE, environment: 'Production', status: 5, signedTransactionInfo: jwsOf(message, trustedPki) } }))
  assert.equal(rows(w)[0].tier, 'plus', 'Apple still says active')
  await new Promise((r) => setTimeout(r, 2))
  await subscribed(w, 'alice', { status: 2, tx: { expiresDate: Date.now() - 1000 } })
  await notify(w, notification(transaction({ expiresDate: Date.now() + 90 * DAY }), { notificationType: 'DID_RENEW' }))
  assert.equal(rows(w)[0].tier, 'free', 'a "renewed" message can\'t grant what Apple doesn\'t report')
  await new Promise((r) => setTimeout(r, 2))
  w.apple.Production.clear()
  await notify(w, notification())
  assert.deepEqual([rows(w)[0].tier, rows(w)[0].state, rows(w)[0].paid_through], ['free', 'unknown', 0], 'Apple no longer knows it')
})

test('duplicates and out-of-order delivery change nothing and ask Apple only once', async () => {
  const w = await bought()
  const at = Date.now()
  const newest = notification(transaction(), { signedDate: at })
  await subscribed(w, 'alice', { status: 5, tx: { revocationDate: at } })
  assert.equal((await notify(w, newest)).status, 200)
  assert.equal(w.apple.calls.length, 1)
  assert.equal(rows(w)[0].event_at, at)

  await subscribed(w, 'alice') // If anything re-read this, the row would flip back to Plus.
  assert.equal((await notify(w, newest)).status, 200, 'redelivered')
  assert.equal((await notify(w, notification(transaction(), { signedDate: at - 5000, notificationType: 'DID_RENEW' }))).status, 200, 'older, and late')
  assert.equal(w.apple.calls.length, 1)
  assert.equal(rows(w)[0].tier, 'free')
  assert.equal(rows(w)[0].event_at, at, 'the marker never goes backwards')
})

test('Apple down during a notification: refused so Apple resends, and the resend works', async () => {
  const w = await bought()
  await subscribed(w, 'alice', { status: 5, tx: { revocationDate: Date.now() } })
  const note = notification(transaction(), { notificationType: 'REFUND' })
  w.apple.down = true
  assert.equal((await notify(w, note)).status, 503)
  assert.equal(rows(w)[0].tier, 'plus')
  assert.equal(rows(w)[0].event_at, 0, 'not marked as seen')
  w.apple.down = false
  assert.equal((await notify(w, note)).status, 200)
  assert.equal(rows(w)[0].tier, 'free')
})

test('a slow verification can\'t overwrite a newer notification\'s answer', async () => {
  const w = await bought()
  const jws = jwsOf(transaction({ appAccountToken: await appAccountToken('alice') }), trustedPki)
  let release
  w.apple.hold = new Promise((r) => { release = r })
  const slow = verifyApple(w, 'alice', jws) // Reads "active", then stalls on the way back.
  while (w.apple.calls.length === 0) await new Promise((r) => setTimeout(r, 1))
  await new Promise((r) => setTimeout(r, 3))
  await subscribed(w, 'alice', { status: 5, tx: { revocationDate: Date.now() } })
  await notify(w, notification(transaction(), { notificationType: 'REFUND' }))
  assert.equal(rows(w)[0].tier, 'free')
  release()
  const res = await slow
  assert.equal(res.body.conflict, undefined)
  assert.equal(snapshotOf(res).tier, 'free')
  assert.equal(rows(w)[0].tier, 'free', 'the refund stands')
})

test('a notification never binds or moves a purchase', async () => {
  const w = appleWorld()
  await subscribed(w, 'alice')
  assert.equal((await notify(w, notification())).status, 200, 'not bound yet: acknowledged')
  assert.equal(rows(w).length, 0, 'the app\'s verify binds it')
  assert.equal(w.apple.calls.length, 0)
  const jws = jwsOf(transaction({ appAccountToken: await appAccountToken('alice') }), trustedPki)
  await verifyApple(w, 'alice', jws)
  const owner = rows(w)[0].account_id
  await new Promise((r) => setTimeout(r, 2))
  const stolen = transaction({ appAccountToken: await appAccountToken('bob') })
  await subscribed(w, 'bob', { tx: { appAccountToken: await appAccountToken('bob') } })
  await notify(w, notification(stolen))
  assert.equal(rows(w)[0].account_id, owner, 'whatever token it names')
})

test('notifications that aren\'t ours are acknowledged and ignored', async () => {
  const w = await bought()
  const test_ = await notify(w, { notificationType: 'TEST', notificationUUID: 'u', signedDate: Date.now(), data: { bundleId: BUNDLE, environment: 'Production' } })
  assert.equal(test_.status, 200)
  assert.equal((await notify(w, { notificationType: 'RENEWAL_EXTENSION', subtype: 'SUMMARY', signedDate: Date.now(), summary: {} })).status, 200)
  const other = transaction({ bundleId: 'com.other.App' })
  await notify(w, notification(other, { data: { bundleId: 'com.other.App', environment: 'Production', signedTransactionInfo: jwsOf(other, trustedPki) } }))
  await notify(w, notification(other))
  // A sandbox notification for a production row, sandbox on and off.
  const sandbox = transaction({ environment: 'Sandbox' })
  const sandboxNote = () => notification(sandbox, { data: { bundleId: BUNDLE, environment: 'Sandbox', signedTransactionInfo: jwsOf(sandbox, trustedPki) } })
  assert.equal((await notify(w, sandboxNote())).status, 200)
  w.env.APPLE_ALLOW_SANDBOX = '1'
  assert.equal((await notify(w, sandboxNote())).status, 200)
  // The inside and outside disagreeing about the environment.
  const crossed = notification(transaction(), { data: { bundleId: BUNDLE, environment: 'Sandbox', signedTransactionInfo: jwsOf(transaction(), trustedPki) } })
  assert.equal((await notify(w, crossed)).status, 200)
  assert.equal(w.apple.calls.length, 0)
  assert.equal(rows(w)[0].event_at, 0)
})

// ---- The daily re-check ------------------------------------------------------------------------------------------

test('a renewal nobody told us about is found the next day, with Apple\'s answer, and only for renewing rows', async () => {
  const w = await bought()
  const stale = Date.now() - DAY
  w.DB.db.prepare('UPDATE purchases SET paid_through = ?, checked_at = ?').run(stale, stale)
  assert.equal(await tierOf(w, 'alice', 'android-1'), 'free', 'the clock took it away')
  const later = Date.now() + 30 * DAY
  await subscribed(w, 'alice', { tx: { expiresDate: later } })
  w.apple.down = true
  await housekeeping(w.env, w.fetcher)
  assert.equal(rows(w)[0].paid_through, stale, 'an outage leaves it for tomorrow, and the run still ends')
  w.apple.down = false
  w.apple.calls.length = 0
  await housekeeping(w.env, w.fetcher)
  assert.equal(rows(w)[0].paid_through, later)
  assert.equal(await tierOf(w, 'alice', 'android-1'), 'plus')
  assert.equal(w.apple.calls.length, 1)
  await housekeeping(w.env, w.fetcher)
  assert.equal(w.apple.calls.length, 1, 'nothing to ask now')
})

test('the re-check leaves Google rows, and cancelled or recently checked App Store rows, alone', async () => {
  const w = await bought()
  w.state.subs.set('gtok', activeSub())
  await buyGoogle(w, 'bob')
  const stale = Date.now() - DAY
  w.DB.db.prepare("UPDATE purchases SET paid_through = ?, checked_at = ? WHERE store = 'google_play'").run(stale, stale)
  w.DB.db.prepare("UPDATE purchases SET paid_through = ? WHERE store = 'app_store'").run(stale)
  await housekeeping(w.env, w.fetcher)
  assert.equal(w.apple.calls.length, 0)
  w.DB.db.prepare("UPDATE purchases SET auto_renewing = 0, checked_at = ? WHERE store = 'app_store'").run(stale)
  await housekeeping(w.env, w.fetcher)
  assert.equal(w.apple.calls.length, 0)
})

// ---- Migration and Android compatibility -------------------------------------------------------------------------

const APP_STORE = '0004_app_store.sql'
const migration = (name) => readFileSync(new URL(`../migrations/${name}`, import.meta.url), 'utf8')

test('the migration keeps every Android purchase, as a Google Play one, working through the old and new Worker', async () => {
  const w = world({ without: [APP_STORE] })
  w.state.subs.set('gtok', activeSub())
  assert.equal(snapshotOf(await buyGoogle(w, 'alice')).tier, 'plus', 'this Worker on the old schema: migrating late breaks nothing')
  assert.equal(snapshotOf(await sync(w, 'alice', 'android-1')).store, null)
  await housekeeping(w.env, w.fetcher)
  const before = w.DB.rows('SELECT * FROM purchases')[0]

  w.DB.db.exec(migration(APP_STORE))
  const after = w.DB.rows('SELECT * FROM purchases')[0]
  for (const [column, value] of Object.entries(before)) assert.equal(after[column], value, column)
  assert.deepEqual([after.store, after.store_ref, after.environment], ['google_play', null, null])
  assert.equal(snapshotOf(await sync(w, 'alice', 'android-1')).store, 'google_play')
  assert.equal(snapshotOf(await buyGoogle(w, 'alice')).tier, 'plus')
  assert.throws(() => w.DB.db.prepare("UPDATE purchases SET store = 'nope'").run(), /CHECK/)
})

test('the rollback in the migration\'s header runs, and returns the old table', () => {
  const db = new FakeD1([APP_STORE])
  const shape = () => db.rows("SELECT sql FROM sqlite_master WHERE name = 'purchases'")[0].sql
  const before = db.rows('PRAGMA table_info(purchases)').map((c) => c.name)
  db.db.exec(migration(APP_STORE))
  assert.match(shape(), /store_ref/)
  const rollback = migration(APP_STORE).split('\n').filter((l) => /^-- {3}/.test(l)).map((l) => l.replace(/^-- {3}/, '')).join('\n')
  assert.match(rollback, /DROP COLUMN store_ref/)
  db.db.exec(rollback)
  assert.deepEqual(db.rows('PRAGMA table_info(purchases)').map((c) => c.name), before)
})

test('Google Play is untouched by App Store settings: its product ids and package stay its own', async () => {
  const w = appleWorld()
  w.state.subs.set('gtok', activeSub({ lineItems: [{ productId: APPLE_IDS[0], expiryTime: FUTURE() }] }))
  assert.equal(snapshotOf(await buyGoogle(w, 'alice')).tier, 'free', 'an App Store id means nothing to Google\'s route')
  w.state.subs.set('gtok', activeSub())
  assert.equal(snapshotOf(await buyGoogle(w, 'alice')).tier, 'plus')
  assert.equal(snapshotOf(await sync(w, 'alice')).store, 'google_play')
})

test('sandbox grants only scoped iOS review access, never production Plus or quota', async () => {
  const w = appleWorld()
  w.env.APPLE_ALLOW_SANDBOX = '1'
  const res = await verifyApple(w, 'alice', await subscribed(w, 'alice', { environment: 'Sandbox' }))
  const s = snapshotOf(res)
  assert.equal(res.status, 200)
  assert.equal(s.tier, 'free')
  assert.equal(s.purchaseValid, true)
  assert.equal(s.iosSandbox.scope, 'ios_sandbox')
  assert.ok(s.iosSandbox.expiresAt <= Date.now() + 15 * 60_000)
  assert.ok(verifySignature(res.body))
  assert.equal(rows(w)[0].environment, 'Sandbox')
  assert.deepEqual(w.apple.calls.map((u) => new URL(u).host), ['api.storekit-sandbox.apple.com'])
  assert.equal(await tierOf(w, 'alice', 'android-1'), 'free')
  assert.equal((await reserve(w, 'alice', 180, 'android-1')).body.allowed, false)
  w.env.APPLE_ALLOW_SANDBOX = '0'
  assert.equal(snapshotOf(await sync(w, 'alice')).iosSandbox, null)
  w.env.APPLE_ALLOW_SANDBOX = '1'
  await verifyApple(w, 'alice', await subscribed(w, 'alice'))
  assert.equal(rows(w).length, 2, 'environment namespaces isolate identical numeric transaction IDs')
  assert.equal(await tierOf(w, 'alice', 'android-1'), 'plus')
})

test('sandbox disabled and signed environment mismatch never fall back', async () => {
  const w = appleWorld()
  const jws = await subscribed(w, 'alice', { environment: 'Sandbox' })
  assert.equal((await verifyApple(w, 'alice', jws)).body.error, 'wrong_environment')
  assert.equal(w.apple.calls.length, 0)
  w.env.APPLE_ALLOW_SANDBOX = '1'
  await subscribed(w, 'alice')
  w.apple.Sandbox.clear()
  assert.equal(snapshotOf(await verifyApple(w, 'alice', jws)).purchaseValid, false)
  assert.equal(rows(w).length, 0)
  assert.deepEqual(w.apple.calls.map((u) => new URL(u).host), ['api.storekit-sandbox.apple.com'])
})

test('stolen JWS and fresh sign-in cannot move ownership; legacy claims and family sharing are disabled', async () => {
  const w = appleWorld()
  const stolen = await subscribed(w, 'alice')
  await verifyApple(w, 'alice', stolen)
  const owner = rows(w)[0].account_id
  const moved = await moveApple(w, 'mallory', stolen)
  assert.equal(moved.status, 403)
  assert.equal(moved.body.error, 'transfer_disabled')
  assert.equal(rows(w)[0].account_id, owner)
  assert.equal(await tierOf(w, 'mallory'), 'free')
  assert.equal((await verifyApple(w, 'mallory', stolen)).body.conflict, true)
  const legacy = await subscribed(w, 'alice', { id: 'legacy', tx: { appAccountToken: undefined } })
  assert.equal((await verifyApple(w, 'mallory', legacy)).body.claimRequired, true)
  assert.equal((await moveApple(w, 'mallory', legacy)).body.error, 'transfer_disabled')
  const shared = await subscribed(w, 'alice', { tx: { inAppOwnershipType: 'FAMILY_SHARED' } })
  assert.equal((await verifyApple(w, 'alice', shared)).body.error, 'family_sharing_disabled')
  assert.equal(rows(w).length, 1)
  assert.equal(rows(w)[0].account_id, owner)
})

test('family sharing cannot revive an existing production entitlement through a notification', async () => {
  const w = appleWorld()
  await verifyApple(w, 'alice', await subscribed(w, 'alice'))
  await subscribed(w, 'alice', { tx: { inAppOwnershipType: 'FAMILY_SHARED' } })
  await notify(w, notification(transaction()))
  assert.equal(await tierOf(w, 'alice'), 'free')
})
