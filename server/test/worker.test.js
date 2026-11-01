import test from 'node:test'
import assert from 'node:assert/strict'
import { entitlementFrom, housekeeping } from '../src/worker.js'
import { IDS, PAST, FUTURE, PROJECT, activeSub, idToken, otherKey, sha256Hex, verifySignature, world } from './fixture.js'

const UTC = 'UTC'
const sync = (w, uid, installId = 'i1', over = {}) => w.call('/v1/account/sync', idToken(uid), { installId, zone: UTC, ...over })
const snapshot = (res) => JSON.parse(res.body.entitlement?.payload ?? res.body.payload)
const buy = (w, uid, token = 'tok', installId = 'i1') => w.call('/v1/verify', idToken(uid), { purchaseToken: token, productId: IDS[0], installId, packageName: 'app.aurora' })
const reserve = (w, uid, minutes, over = {}) => w.call('/v1/quota/reserve', idToken(uid), {
  installId: 'i1', idempotencyKey: crypto.randomUUID(), commitmentId: crypto.randomUUID(), mode: 'always_block', minutes, zone: UTC, ...over,
})
// Free's day ends at a fixed moment; a session near midnight would charge two days and blur what a test checks.
const remaining = async (w, uid) => (await sync(w, uid)).body.quota.remainingMs

// ---- Identity ---------------------------------------------------------------------------------------------

test('protected routes refuse a missing or malformed token', async () => {
  const w = world()
  for (const path of ['/v1/account/sync', '/v1/verify', '/v1/quota/reserve', '/v1/quota/release', '/v1/quota/import', '/v1/purchases/transfer', '/v1/installations/remove', '/v1/account/delete', '/v1/account/delete/cancel']) {
    assert.equal((await w.call(path, null, {})).status, 401, path)
    assert.equal((await w.call(path, 'not.a.jwt', {})).status, 401, path)
  }
})

test('token checks: signature, algorithm, issuer, audience, expiry, issue time, subject', async () => {
  const w = world()
  const now = Math.floor(Date.now() / 1000)
  const bad = [
    idToken('u', {}, { key: otherKey.privateKey }),
    idToken('u', {}, { alg: 'none' }),
    idToken('u', {}, { kid: 'unknown' }),
    idToken('u', { iss: 'https://securetoken.google.com/other' }),
    idToken('u', { aud: 'other' }),
    idToken('u', { exp: now - 1 }),
    idToken('u', { iat: now + 3600 }),
    idToken('u', { auth_time: now + 3600 }),
    idToken('', {}),
  ]
  for (const token of bad) assert.equal((await w.call('/v1/account/sync', token, { installId: 'i1' })).status, 401, token.slice(0, 40))
  assert.equal(w.DB.rows('SELECT * FROM accounts').length, 0)
})

test('an unverified email can\'t make an account', async () => {
  const w = world()
  const res = await w.call('/v1/account/sync', idToken('u', { email_verified: false }), { installId: 'i1' })
  assert.equal(res.status, 403)
  assert.equal(res.body.error, 'email_unverified')
})

test('the Auth Emulator path is refused in production, and only there accepts unsigned tokens', async () => {
  const w = world()
  w.env.FIREBASE_AUTH_EMULATOR = '1'
  const token = idToken('u', {}, { alg: 'none' })
  assert.equal((await w.call('/v1/account/sync', token, { installId: 'i1' })).status, 401)
  w.env.ENVIRONMENT = 'development'
  assert.equal((await w.call('/v1/account/sync', token, { installId: 'i1' })).status, 200)
})

test('a revoked, disabled or vanished user is refused, and an outage fails closed', async () => {
  const w = world()
  const now = Math.floor(Date.now() / 1000)
  w.state.users.set('revoked', { validSince: String(now + 100) })
  w.state.users.set('disabled', { validSince: '0', disabled: true })
  w.state.users.set('gone', 'gone')
  for (const uid of ['revoked', 'disabled', 'gone']) {
    const res = await sync(w, uid)
    assert.equal(res.status, 401, uid)
    assert.equal(res.body.error, 'revoked')
  }
  delete w.env.FIREBASE_SERVICE_ACCOUNT
  assert.equal((await sync(w, 'fine')).status, 503)
})

test('a new account starts Free, and the same identity always reaches the same account', async () => {
  const w = world()
  const first = await sync(w, 'alice')
  assert.equal(first.status, 200)
  assert.equal(snapshot(first).tier, 'free')
  assert.equal(snapshot(first).uid, 'alice')
  assert.ok(verifySignature(first.body.entitlement))
  assert.equal(first.body.quota.remainingMs, 120 * 60_000)
  const again = await sync(w, 'alice', 'i2')
  assert.equal(again.body.account.id, first.body.account.id)
  assert.equal(w.DB.rows('SELECT * FROM accounts').length, 1)
  assert.equal(again.body.devices.length, 2)
})

test('a client-supplied uid means nothing: the token decides', async () => {
  const w = world()
  await buy(w, 'alice')
  const bob = await w.call('/v1/account/sync', idToken('bob'), { installId: 'i1', uid: 'alice', accountId: 'alice', zone: UTC })
  assert.equal(snapshot(bob).uid, 'bob')
  assert.equal(snapshot(bob).tier, 'free')
})

// ---- Purchases ---------------------------------------------------------------------------------------------

test('a verified purchase unlocks Plus for that account, signed for this install', async () => {
  const w = world()
  w.state.subs.set('tok', activeSub())
  const res = await buy(w, 'alice')
  const s = snapshot(res)
  assert.equal(s.tier, 'plus')
  assert.equal(s.purchaseValid, true)
  assert.equal(s.uid, 'alice')
  assert.equal(s.installId, 'i1')
  assert.ok(verifySignature(res.body))
})

test('an invalid token is rejected and binds nothing; an outage says try again', async () => {
  const w = world()
  const res = await buy(w, 'alice', 'forged')
  assert.equal(snapshot(res).tier, 'free')
  assert.equal(snapshot(res).purchaseValid, false)
  assert.equal(w.DB.rows('SELECT * FROM purchases').length, 0)
  w.state.playDown = true
  assert.equal((await buy(w, 'alice')).status, 503)
})

test('states that grant Plus, and those that do not', () => {
  const now = Date.now()
  const of = (state, expiry = FUTURE(), productId = IDS[0]) => entitlementFrom({ subscriptionState: `SUBSCRIPTION_STATE_${state}`, lineItems: [{ productId, expiryTime: expiry }] }, IDS, now)
  assert.equal(of('ACTIVE').tier, 'plus')
  assert.equal(of('IN_GRACE_PERIOD').tier, 'plus')
  assert.equal(of('CANCELED').tier, 'plus', 'keeps access through the paid-through date')
  assert.equal(of('CANCELED').autoRenewing, false)
  for (const state of ['ON_HOLD', 'PAUSED', 'EXPIRED', 'PENDING', 'REVOKED']) assert.equal(of(state).tier, 'free', state)
  assert.equal(of('ACTIVE', PAST()).tier, 'free')
  assert.equal(of('ACTIVE', FUTURE(), 'something_else').tier, 'free')
  assert.equal(entitlementFrom(null, IDS, now).tier, 'free')
})

test('account A cannot use account B\'s purchase', async () => {
  const w = world()
  w.state.subs.set('tok', activeSub())
  assert.equal(snapshot(await buy(w, 'alice')).tier, 'plus')
  const res = await buy(w, 'bob')
  assert.equal(snapshot(res).tier, 'free')
  assert.equal(res.body.conflict, true)
  assert.equal(res.body.conflictHint, 'a•••@example.com')
  assert.equal(snapshot(await sync(w, 'alice')).tier, 'plus', 'nothing moved silently')
  assert.equal(w.DB.rows('SELECT * FROM purchases').length, 1)
})

test('simultaneous claims on one purchase bind it to exactly one account', async () => {
  const w = world()
  w.state.subs.set('tok', activeSub())
  const results = await Promise.all(['alice', 'bob', 'carol'].map((u) => buy(w, u)))
  assert.equal(results.filter((r) => snapshot(r).tier === 'plus').length, 1)
  assert.equal(results.filter((r) => r.body.conflict).length, 2)
})

test('reinstall and a replacement phone restore Plus from the account alone', async () => {
  const w = world()
  w.state.subs.set('tok', activeSub())
  await buy(w, 'alice', 'tok', 'old-phone')
  const fresh = await sync(w, 'alice', 'new-phone')
  assert.equal(snapshot(fresh).tier, 'plus')
  assert.equal(snapshot(fresh).installId, 'new-phone')
})

test('an upgrade inherits ownership through the linked token, and can\'t take another account\'s', async () => {
  const w = world()
  w.state.subs.set('monthly', activeSub())
  await buy(w, 'alice', 'monthly')
  w.state.subs.set('annual', activeSub({ linkedPurchaseToken: 'monthly' }))
  assert.equal((await buy(w, 'bob', 'annual')).body.conflict, true)
  assert.equal(snapshot(await buy(w, 'alice', 'annual')).tier, 'plus')
})

test('a purchase made under another account\'s id isn\'t bound here', async () => {
  const w = world()
  w.state.subs.set('tok', activeSub({ externalAccountIdentifiers: { obfuscatedExternalAccountId: sha256Hex('alice') } }))
  assert.equal((await buy(w, 'bob')).body.conflict, true)
  assert.equal(snapshot(await buy(w, 'alice')).tier, 'plus')
})

test('a purchase conflict resolves only by an explicit transfer with a recent sign-in, and rarely', async () => {
  const w = world()
  w.state.subs.set('tok', activeSub())
  await buy(w, 'alice')
  const old = Math.floor(Date.now() / 1000) - 3600
  const stale = await w.call('/v1/purchases/transfer', idToken('bob', { auth_time: old }), { purchaseToken: 'tok', installId: 'i1' })
  assert.equal(stale.status, 401)
  assert.equal(stale.body.error, 'reauth_required')
  const moved = await w.call('/v1/purchases/transfer', idToken('bob'), { purchaseToken: 'tok', installId: 'i1' })
  assert.equal(snapshot(moved).tier, 'plus')
  assert.equal(snapshot(await sync(w, 'alice')).tier, 'free')
  const back = await w.call('/v1/purchases/transfer', idToken('alice'), { purchaseToken: 'tok', installId: 'i1' })
  assert.equal(back.status, 429)
  assert.equal(back.body.error, 'transfer_limit')
  assert.equal((await w.call('/v1/purchases/transfer', idToken('bob'), { purchaseToken: 'unknown', installId: 'i1' })).status, 404)
})

test('Plus seats: the sixth device is told, and removing an old one frees a seat', async () => {
  const w = world()
  w.state.subs.set('tok', activeSub())
  await buy(w, 'alice', 'tok', 'd1')
  for (const d of ['d2', 'd3', 'd4', 'd5']) assert.equal(snapshot(await sync(w, 'alice', d)).tier, 'plus', d)
  const sixth = await sync(w, 'alice', 'd6')
  assert.equal(snapshot(sixth).tier, 'free')
  assert.equal(snapshot(sixth).state, 'too_many_devices')
  await w.call('/v1/installations/remove', idToken('alice'), { installId: 'd1', currentInstallId: 'd6' })
  assert.equal(snapshot(await sync(w, 'alice', 'd6')).tier, 'plus')
})

test('a removed device is out until it signs in again; another account can\'t remove it', async () => {
  const w = world()
  const old = Math.floor(Date.now() / 1000) - 60
  await w.call('/v1/account/sync', idToken('alice', { auth_time: old }), { installId: 'lost', zone: UTC })
  await sync(w, 'alice', 'kept')
  assert.equal((await w.call('/v1/installations/remove', idToken('mallory'), { installId: 'lost' })).status, 404)
  assert.equal((await w.call('/v1/installations/remove', idToken('alice'), { installId: 'lost', currentInstallId: 'kept' })).status, 200)
  const stale = await w.call('/v1/account/sync', idToken('alice', { auth_time: old }), { installId: 'lost', zone: UTC })
  assert.equal(stale.status, 401)
  assert.equal(stale.body.error, 'installation_removed')
  // A later sign-in brings it back.
  await new Promise((r) => setTimeout(r, 1100))
  const later = await w.call('/v1/account/sync', idToken('alice'), { installId: 'lost', zone: UTC })
  assert.equal(later.status, 200)
})

test('RTDN: expiry and revocation reach the account; old messages and bad tokens change nothing', async () => {
  const w = world()
  w.state.subs.set('tok', activeSub())
  await buy(w, 'alice')
  const note = (at) => ({ message: { data: btoa(JSON.stringify({ packageName: 'app.aurora', eventTimeMillis: String(at), subscriptionNotification: { purchaseToken: 'tok' } })) } })
  const rtdn = (at, token = 'secret') => w.call(`/v1/rtdn?token=${token}`, null, note(at))
  assert.equal((await rtdn(Date.now(), 'wrong')).status, 403)
  const at = Date.now()
  w.state.subs.set('tok', { subscriptionState: 'SUBSCRIPTION_STATE_REVOKED', lineItems: [{ productId: IDS[0], expiryTime: FUTURE() }] })
  assert.equal((await rtdn(at)).status, 204)
  assert.equal(snapshot(await sync(w, 'alice')).tier, 'free')
  w.state.subs.set('tok', activeSub())
  await rtdn(at - 1000)
  assert.equal(snapshot(await sync(w, 'alice')).tier, 'free', 'a delayed message changes nothing')
  await rtdn(at + 1000)
  assert.equal(snapshot(await sync(w, 'alice')).tier, 'plus')
  w.state.playDown = true
  assert.equal((await rtdn(at + 2000)).status, 503, 'Pub/Sub redelivers')
})

test('expiry takes Plus away by the clock alone', async () => {
  const w = world()
  w.state.subs.set('tok', activeSub())
  await buy(w, 'alice')
  w.DB.db.prepare('UPDATE purchases SET paid_through = ?').run(Date.now() - 1000)
  assert.equal(snapshot(await sync(w, 'alice')).tier, 'free')
})

// ---- Quota -------------------------------------------------------------------------------------------------

test('120 minutes allowed, 121 rejected, with the server\'s numbers', async () => {
  const w = world()
  const over = await reserve(w, 'alice', 121)
  assert.equal(over.body.allowed, false)
  assert.equal(over.body.remainingMs, 120 * 60_000)
  const ok = await reserve(w, 'alice', 120)
  assert.equal(ok.body.allowed, true)
  assert.ok(verifySignature(ok.body.authorization))
  const a = JSON.parse(ok.body.authorization.payload)
  assert.equal(a.end - a.start, 120 * 60_000)
  assert.equal(a.uid, 'alice')
  assert.equal(a.type, 'reservation')
})

test('the client can\'t pick the time: a start in the past pays from now, and far ahead is refused', async () => {
  const w = world()
  const before = Date.now()
  const res = await reserve(w, 'alice', 30, { startAt: before - 10 * 60_000 })
  const a = JSON.parse(res.body.authorization.payload)
  assert.ok(a.start >= before)
  assert.equal(a.end, before - 10 * 60_000 + 30 * 60_000)
  assert.equal((await reserve(w, 'alice', 30, { startAt: before + 13 * 3_600_000 })).body.error, 'too_early')
  assert.equal((await reserve(w, 'alice', 5, { startAt: before - 3_600_000 })).body.error, 'in_the_past')
})

test('reinstall, cleared data, logout and login never reset the ledger', async () => {
  const w = world()
  await reserve(w, 'alice', 120)
  // A new installation id is what clearing data or reinstalling looks like to the server.
  assert.equal((await reserve(w, 'alice', 1, { installId: 'reinstalled' })).body.allowed, false)
  assert.equal(await remaining(w, 'alice'), 0)
})

test('two devices share one allowance, even started together', async () => {
  const w = world()
  assert.equal((await reserve(w, 'alice', 90, { installId: 'a' })).body.allowed, true)
  assert.equal((await reserve(w, 'alice', 31, { installId: 'b' })).body.allowed, false)
  assert.equal((await reserve(w, 'alice', 30, { installId: 'b' })).body.allowed, true)
})

test('concurrent requests can\'t oversubscribe the allowance', async () => {
  const w = world()
  const results = await Promise.all(Array.from({ length: 6 }, (_, i) => reserve(w, 'alice', 50, { installId: `d${i}` })))
  assert.equal(results.filter((r) => r.body.allowed).length, 2, JSON.stringify(results.map((r) => r.status)))
  assert.equal(w.DB.rows('SELECT * FROM quota_reservations').length, 2)
})

test('a duplicate request returns the first reservation, and creates nothing', async () => {
  const w = world()
  const key = crypto.randomUUID()
  const first = await reserve(w, 'alice', 60, { idempotencyKey: key })
  const second = await reserve(w, 'alice', 60, { idempotencyKey: key })
  assert.equal(JSON.parse(second.body.authorization.payload).reservationId, JSON.parse(first.body.authorization.payload).reservationId)
  assert.equal(w.DB.rows('SELECT * FROM quota_reservations').length, 1)
  const racing = await Promise.all([reserve(w, 'alice', 30, { idempotencyKey: 'same' }), reserve(w, 'alice', 30, { idempotencyKey: 'same' })])
  assert.ok(racing.every((r) => r.body.allowed))
  assert.equal(w.DB.rows("SELECT * FROM quota_reservations WHERE idempotency_key = 'same'").length, 1)
})

test('each account has its own ledger', async () => {
  const w = world()
  await reserve(w, 'alice', 120)
  assert.equal((await reserve(w, 'bob', 120)).body.allowed, true)
  assert.equal(await remaining(w, 'bob'), 0)
})

test('changing time zone doesn\'t open a new allowance', async () => {
  const w = world()
  await reserve(w, 'alice', 120)
  // Just after the first one ends, so it is new coverage. It would fit if the day had really changed.
  const after = Date.now() + 125 * 60_000
  assert.equal((await reserve(w, 'alice', 5, { startAt: after, zone: 'Pacific/Kiritimati' })).body.allowed, false)
  assert.equal((await reserve(w, 'alice', 5, { startAt: after, zone: 'Pacific/Pago_Pago' })).body.allowed, false)
})

test('Plus has no daily limit and writes no ledger; invalid requests are refused', async () => {
  const w = world()
  w.state.subs.set('tok', activeSub())
  await buy(w, 'alice')
  const res = await reserve(w, 'alice', 600)
  assert.equal(res.body.allowed, true)
  assert.equal(res.body.tier, 'plus')
  assert.equal(w.DB.rows('SELECT * FROM quota_reservations').length, 0)
  for (const over of [{ minutes: 0 }, { minutes: 1441 }, { minutes: 1.5 }, { mode: 'flexible' }, { zone: 'Nope/Nope' }, { installId: '' }, { idempotencyKey: 'x'.repeat(200) }]) {
    assert.equal((await reserve(w, 'bob', 10, over)).status, 400, JSON.stringify(over))
  }
})

test('releasing: only what hasn\'t begun, only your own', async () => {
  const w = world()
  const later = await reserve(w, 'alice', 60, { startAt: Date.now() + 3_600_000 })
  const id = JSON.parse(later.body.authorization.payload).reservationId
  assert.equal((await w.call('/v1/quota/release', idToken('bob'), { reservationId: id })).status, 404)
  assert.equal((await w.call('/v1/quota/release', idToken('alice'), { reservationId: id })).status, 200)
  assert.equal(await remaining(w, 'alice'), 120 * 60_000)
  const started = await reserve(w, 'alice', 60)
  const sid = JSON.parse(started.body.authorization.payload).reservationId
  assert.equal((await w.call('/v1/quota/release', idToken('alice'), { reservationId: sid })).body.error, 'already_started')
  assert.equal(await remaining(w, 'alice'), 60 * 60_000, 'restarting can\'t refund')
})

test('import adds an existing phone\'s time once, and only adds', async () => {
  const w = world()
  const start = Date.now() - 30 * 60_000
  const entries = [{ start, end: start + 45 * 60_000, zone: UTC, mode: 'always_block' }]
  assert.equal((await w.call('/v1/quota/import', idToken('alice'), { installId: 'i1', entries })).body.imported, 1)
  await w.call('/v1/quota/import', idToken('alice'), { installId: 'i1', entries })
  assert.equal(w.DB.rows('SELECT * FROM quota_reservations').length, 1)
  assert.equal(await remaining(w, 'alice'), 75 * 60_000)
  assert.equal((await w.call('/v1/quota/import', idToken('alice'), { installId: 'i1', entries: [{ start: 5, end: 1, zone: UTC }] })).status, 400)
})

test('rate limit answers 429', async () => {
  const w = world()
  let last
  for (let i = 0; i < 31; i++) last = await reserve(w, 'alice', 121)
  assert.equal(last.status, 429)
})

// ---- Deletion ----------------------------------------------------------------------------------------------

test('deletion needs a recent sign-in, can be cancelled, then purges after the grace period', async () => {
  const w = world()
  w.state.subs.set('tok', activeSub())
  await buy(w, 'alice')
  await reserve(w, 'bob', 5)
  const old = Math.floor(Date.now() / 1000) - 3600
  assert.equal((await w.call('/v1/account/delete', idToken('alice', { auth_time: old }), {})).body.error, 'reauth_required')
  assert.ok((await w.call('/v1/account/delete', idToken('alice'), {})).body.deletionAt > Date.now())
  assert.ok((await sync(w, 'alice')).body.account.deletionRequestedAt)
  assert.equal((await reserve(w, 'alice', 5)).body.error, 'account_deleting')

  await w.call('/v1/account/delete/cancel', idToken('alice'), {})
  assert.equal((await sync(w, 'alice')).body.account.deletionRequestedAt, null)

  await w.call('/v1/account/delete', idToken('alice'), {})
  await housekeeping(w.env, w.fetcher)
  assert.equal(w.state.deleted.length, 0, 'not before the grace period')
  await housekeeping(w.env, w.fetcher, Date.now() + 8 * 86_400_000)
  assert.deepEqual(w.state.deleted, ['alice'])
  assert.equal(w.DB.rows("SELECT * FROM accounts WHERE firebase_uid = 'alice'").length, 0)
  assert.equal(w.DB.rows("SELECT * FROM installations WHERE id = 'i1'").length, 1, 'bob\'s remains')
  const [purchase] = w.DB.rows('SELECT * FROM purchases')
  assert.equal(purchase.account_id, null, 'the subscription can bind to a new account')
  assert.equal(snapshot(await buy(w, 'carol')).tier, 'plus')
})

test('project id is required', async () => {
  const w = world()
  delete w.env.FIREBASE_PROJECT_ID
  assert.equal((await sync(w, 'alice')).status, 401)
  assert.equal(PROJECT, 'aurora-test')
})
