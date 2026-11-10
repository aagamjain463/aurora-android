// The 14-day trial: one per account, address and device; granted by the server; ends by itself (MONETIZATION_PLAN.md 3.2).
import test from 'node:test'
import assert from 'node:assert/strict'
import { generateKeyPairSync } from 'node:crypto'
import { housekeeping } from '../src/worker.js'
import { normalizeEmail } from '../src/trial.js'
import { activeSub, deviceKey, idToken, IDS, signedCall, verifySignature, world } from './fixture.js'

const DAY = 86_400_000
const TRIAL = 14 * DAY
const dcKey = generateKeyPairSync('ec', { namedCurve: 'prime256v1' }).privateKey.export({ type: 'pkcs8', format: 'pem' })

function trialWorld() {
  const w = world()
  Object.assign(w.env, { TRIAL: 'on', TRIAL_PEPPER: 'pepper', APPLE_TEAM_ID: 'TEAM', DEVICECHECK_KEY_ID: 'KID', DEVICECHECK_PRIVATE_KEY: dcKey })
  // DeviceCheck: one physical device per token prefix ("dev1:anything"), as Apple would resolve it.
  const bits = new Map()
  w.state.dcCalls = []
  w.state.deviceCheck = async (url, init) => {
    const req = JSON.parse(init.body)
    w.state.dcCalls.push({ url, req, auth: init.headers.authorization })
    if (w.state.dcDown) return new Response('', { status: 500 })
    if (req.device_token.startsWith('bad')) return new Response('Bad Device Token', { status: 400 })
    const dev = req.device_token.split(':')[0]
    if (url.endsWith('/update_two_bits')) {
      if (w.state.dcUpdateDown) return new Response('', { status: 503 })
      bits.set(dev, { bit0: req.bit0 ?? false, bit1: req.bit1 ?? false, last_update_time: '2026-11' })
      return new Response('')
    }
    const b = bits.get(dev)
    return b ? new Response(JSON.stringify(b)) : new Response('Bit State Not Found')
  }
  return w
}

const verdict = (nonce, over = {}) => ({
  requestDetails: { requestPackageName: 'app.aurora', nonce, timestampMillis: String(Date.now()) },
  appIntegrity: { appRecognitionVerdict: 'PLAY_RECOGNIZED', packageName: 'app.aurora', certificateSha256Digest: ['cert'] },
  deviceIntegrity: { deviceRecognitionVerdict: ['MEETS_DEVICE_INTEGRITY'] },
  accountDetails: { appLicensingVerdict: 'LICENSED' },
  ...over,
})

const claim = (w, key, uid, installId, over = {}, token = {}) =>
  signedCall(w, key, '/v1/trial/claim', idToken(uid, token), { installId, platform: 'android', ...over })
const sync = (w, key, uid, installId, token = {}) => signedCall(w, key, '/v1/account/sync', idToken(uid, token), { installId, zone: 'UTC' })
const snapshot = (res) => {
  assert.ok(verifySignature(res.body.entitlement), 'signed by the server')
  return JSON.parse(res.body.entitlement.payload)
}

/** The Android path: ask, attest with the nonce the server hands back, ask again. */
async function androidClaim(w, key, uid, installId, shape = verdict, token = {}) {
  const first = await claim(w, key, uid, installId, {}, token)
  if (first.status !== 428) return first
  assert.equal(first.body.reason, 'attestation_required')
  w.state.verdict = () => shape(first.body.attestNonce)
  const attested = await signedCall(w, key, '/v1/device/attest', idToken(uid, token), { installId, token: 'integrity-token' })
  assert.equal(attested.status, 200, JSON.stringify(attested.body))
  return claim(w, key, uid, installId, {}, token)
}

const claimsIn = (w) => w.DB.rows('SELECT * FROM trial_claims')

test('first legitimate Android trial: 14 days of Plus, no store, ends by itself, told to old clients as paid-through', async () => {
  const w = trialWorld()
  const key = deviceKey()
  assert.equal((await sync(w, key, 'alice', 'p1')).body.trial.status, 'available')
  const before = Date.now()
  const res = await androidClaim(w, key, 'alice', 'p1')
  assert.equal(res.status, 200, JSON.stringify(res.body))
  const s = snapshot(res)
  assert.equal(s.tier, 'plus')
  assert.equal(s.state, 'trial')
  assert.equal(s.source, 'trial')
  assert.equal(s.autoRenewing, false, 'a client that predates trials ends Plus at paidThrough')
  assert.ok(s.paidThrough >= before + TRIAL && s.paidThrough <= Date.now() + TRIAL)
  assert.equal(s.trialEndsAt, s.paidThrough)
  assert.equal(res.body.trial.status, 'active')
  assert.equal(res.body.trial.endsAt - res.body.trial.startedAt, TRIAL)
  assert.equal(w.state.subs.size, 0, 'no store was asked for anything')
  const [row] = claimsIn(w)
  assert.equal(row.evidence, 'play_integrity')
  assert.ok(!JSON.stringify(row).includes('alice'), 'no address or uid in the clear')
  // Plus means Plus everywhere the server decides: no Free ledger for a trial reservation.
  const reserved = await signedCall(w, key, '/v1/quota/reserve', idToken('alice'), { installId: 'p1', idempotencyKey: 'k', commitmentId: 'c', mode: 'always_block', minutes: 600, zone: 'UTC' })
  assert.equal(reserved.body.tier, 'plus')
})

test('asking again, reinstalling or moving phones never moves the end', async () => {
  const w = trialWorld()
  const key = deviceKey()
  const first = await androidClaim(w, key, 'alice', 'p1')
  const end = first.body.trial.endsAt
  assert.equal((await claim(w, key, 'alice', 'p1')).body.trial.endsAt, end)
  // Reinstall: a new installation id and key, same account.
  const key2 = deviceKey()
  const resynced = await sync(w, key2, 'alice', 'p2')
  assert.equal(resynced.body.trial.endsAt, end)
  assert.equal(snapshot(resynced).paidThrough, end)
  assert.equal((await claim(w, key2, 'alice', 'p2')).body.trial.endsAt, end, 'no attestation needed to read your own trial')
  assert.equal(claimsIn(w).length, 1)
})

test('a second account on the same phone gets no trial; neither do 100 more', async () => {
  const w = trialWorld()
  const key = deviceKey()
  assert.equal((await androidClaim(w, key, 'alice', 'p1')).status, 200)
  const bob = await claim(w, key, 'bob', 'p1')
  assert.equal(bob.status, 403)
  assert.equal(bob.body.reason, 'device_used')
  assert.equal(snapshot(await sync(w, key, 'bob', 'p1')).tier, 'free')
  // A farm on one phone hits the per-device claim limit (10 an hour) too; every answer is a refusal either way.
  for (let i = 0; i < 100; i++) {
    const r = await claim(w, key, `farm${i}`, 'p1')
    assert.ok(r.body.reason === 'device_used' || r.status === 429, `account ${i}: ${JSON.stringify(r.body)}`)
  }
  assert.equal(claimsIn(w).length, 1)
  // Switching back to the first account keeps its trial.
  assert.equal(snapshot(await sync(w, key, 'alice', 'p1')).tier, 'plus')
})

test('racing claims: twenty accounts on one phone at once grant exactly one trial', async () => {
  const w = trialWorld()
  const key = deviceKey()
  const first = await claim(w, key, 'a0', 'p1')
  w.state.verdict = () => verdict(first.body.attestNonce)
  await signedCall(w, key, '/v1/device/attest', idToken('a0'), { installId: 'p1', token: 't' })
  const results = await Promise.all(Array.from({ length: 20 }, (_, i) => claim(w, key, `a${i}`, 'p1')))
  assert.equal(results.filter((r) => r.status === 200).length, 1, JSON.stringify(results.map((r) => r.body.reason ?? 'ok')))
  assert.ok(results.filter((r) => r.status !== 200).every((r) => r.body.reason === 'device_used' || r.status === 429))
  assert.equal(claimsIn(w).length, 1)
})

test('racing claims: one account asking twice at once gets one trial and the same end both times', async () => {
  const w = trialWorld()
  const key = deviceKey()
  const first = await claim(w, key, 'alice', 'p1')
  w.state.verdict = () => verdict(first.body.attestNonce)
  await signedCall(w, key, '/v1/device/attest', idToken('alice'), { installId: 'p1', token: 't' })
  const [a, b] = await Promise.all([claim(w, key, 'alice', 'p1'), claim(w, key, 'alice', 'p1')])
  assert.equal(a.status, 200)
  assert.equal(b.status, 200)
  assert.equal(a.body.trial.endsAt, b.body.trial.endsAt)
  assert.equal(claimsIn(w).length, 1)
})

test('deleting the account and signing up again with the same address, however spelled, finds the old trial', async () => {
  const w = trialWorld()
  const key = deviceKey()
  assert.equal((await androidClaim(w, key, 'alice', 'p1', verdict, { email: 'alice.smith@gmail.com' })).status, 200)
  w.DB.db.prepare("UPDATE accounts SET status = 'deletion_requested', deletion_requested_at = ?").run(Date.now() - 31 * DAY)
  await housekeeping(w.env, w.fetcher)
  assert.equal(w.DB.rows('SELECT * FROM accounts').length, 0, 'account purged')
  assert.equal(claimsIn(w)[0].account_id, null, 'claim kept, unlinked')
  for (const email of ['Alice.Smith@gmail.com', 'alicesmith+again@googlemail.com', 'a.l.i.c.e.s.m.i.t.h@gmail.com']) {
    const newPhone = deviceKey()
    const r = await claim(w, newPhone, `alice-${email}`, `fresh-${email.length}`, {}, { email })
    assert.equal(r.status, 403, email)
    assert.equal(r.body.reason, 'account_used', email)
  }
  assert.equal(normalizeEmail(' Someone+tag@Example.com '), 'someone@example.com')
  assert.equal(normalizeEmail('a.b@example.com'), 'a.b@example.com', 'dots only matter at Gmail')
})

test('expiry: Plus ends at the server\'s end, offline clients already know it, and no second trial follows', async () => {
  const w = trialWorld()
  const key = deviceKey()
  await androidClaim(w, key, 'alice', 'p1')
  w.DB.db.prepare('UPDATE trial_claims SET granted_at = ?, expires_at = ?').run(Date.now() - TRIAL - DAY, Date.now() - DAY)
  const s = await sync(w, key, 'alice', 'p1')
  assert.equal(snapshot(s).tier, 'free')
  assert.equal(snapshot(s).state, 'trial_ended')
  assert.equal(s.body.trial.status, 'ended')
  assert.ok(s.body.quota, 'Free allowance applies again')
  const again = await claim(w, key, 'alice', 'p1')
  assert.equal(again.status, 200)
  assert.equal(again.body.trial.status, 'ended')
  assert.equal(snapshot(again).tier, 'free')
  assert.equal(claimsIn(w).length, 1)
})

test('a revoked trial is Free at once and stays claimed', async () => {
  const w = trialWorld()
  const key = deviceKey()
  await androidClaim(w, key, 'alice', 'p1')
  w.DB.db.prepare('UPDATE trial_claims SET revoked_at = ?').run(Date.now() - 1000)
  const s = await sync(w, key, 'alice', 'p1')
  assert.equal(snapshot(s).tier, 'free')
  assert.equal(s.body.trial.status, 'ended')
})

test('Android evidence: weak, unlicensed or modified builds get no trial; an unkeyed install can\'t be judged', async () => {
  for (const [name, shape] of [
    ['basic integrity', (n) => verdict(n, { deviceIntegrity: { deviceRecognitionVerdict: ['MEETS_BASIC_INTEGRITY'] } })],
    ['unlicensed', (n) => verdict(n, { accountDetails: { appLicensingVerdict: 'UNLICENSED' } })],
    ['modified build', (n) => verdict(n, { appIntegrity: { appRecognitionVerdict: 'UNRECOGNIZED_VERSION' } })],
  ]) {
    const w = trialWorld()
    const r = await androidClaim(w, deviceKey(), 'alice', 'p1', shape)
    assert.equal(r.status, 403, name)
    assert.equal(r.body.reason, 'device_ineligible', name)
    assert.equal(claimsIn(w).length, 0, name)
  }
  const w = trialWorld()
  const unsigned = await w.call('/v1/trial/claim', idToken('alice'), { installId: 'p1', platform: 'android' })
  assert.equal(unsigned.body.reason, 'device_unverifiable')
  // A verdict older than the claim window has to be made again; it isn't held against the device.
  const key = deviceKey()
  await androidClaim(w, key, 'bob', 'p2')
  w.DB.db.prepare("UPDATE devices SET integrity_at = ? WHERE id = 'p2'").run(Date.now() - 60 * 60_000)
  w.DB.db.prepare('DELETE FROM trial_claims').run()
  assert.equal((await claim(w, key, 'bob', 'p2')).status, 428)
})

test('account rules: only new accounts that never paid; off means off; unverified email never gets in', async () => {
  const w = trialWorld()
  const key = deviceKey()
  await sync(w, key, 'old', 'p1')
  w.DB.db.prepare("UPDATE accounts SET created_at = ? WHERE firebase_uid = 'old'").run(Date.now() - 31 * DAY)
  assert.equal((await claim(w, key, 'old', 'p1')).body.reason, 'not_new')
  assert.equal((await sync(w, key, 'old', 'p1')).body.trial.reason, 'not_new')

  const k2 = deviceKey()
  w.state.subs.set('tok', activeSub())
  await signedCall(w, k2, '/v1/verify', idToken('payer'), { purchaseToken: 'tok', productId: IDS[0], installId: 'p2', packageName: 'app.aurora' })
  assert.equal((await claim(w, k2, 'payer', 'p2')).body.reason, 'purchased')

  assert.equal((await claim(w, deviceKey(), 'x', 'p3', {}, { email_verified: false })).status, 403)
  w.env.TRIAL = 'off'
  const off = await claim(w, deviceKey(), 'y', 'p4')
  assert.equal(off.status, 404)
  assert.equal((await sync(w, deviceKey(), 'z', 'p5')).body.trial.status, 'unavailable')
})

test('iOS: DeviceCheck bit decides the device, and is set before the grant counts', async () => {
  const w = trialWorld()
  const ios = (uid, installId, token, key = deviceKey()) => claim(w, key, uid, installId, { platform: 'ios', deviceToken: token })
  const ok = await ios('alice', 'i1', 'dev1:t1')
  assert.equal(ok.status, 200, JSON.stringify(ok.body))
  assert.equal(snapshot(ok).source, 'trial')
  const update = w.state.dcCalls.find((c) => c.url.endsWith('/update_two_bits'))
  assert.equal(update.req.bit0, true)
  assert.match(update.url, /^https:\/\/api\.devicecheck\.apple\.com\/v1\//)
  assert.ok(update.auth.startsWith('Bearer ') && update.auth.split('.').length === 3)

  // Reinstall wiped the install id and Keychain; Apple still remembers the device.
  const again = await ios('bob', 'i2', 'dev1:t2')
  assert.equal(again.body.reason, 'device_used')
  // A token Apple rejects (simulator, modified app) is unverifiable, not abuse.
  assert.equal((await ios('carol', 'i3', 'bad:t')).body.reason, 'device_unverifiable')
  // Apple down: try later, nothing granted.
  w.state.dcDown = true
  assert.equal((await ios('dan', 'i4', 'dev2:t')).status, 503)
  w.state.dcDown = false
  // Apple took the question but not the write: the trial is undone, so a reinstall can't claim it twice.
  w.state.dcUpdateDown = true
  const erin = deviceKey()
  const half = await ios('erin', 'i5', 'dev3:t', erin)
  assert.equal(half.status, 503)
  assert.equal(w.DB.rows("SELECT * FROM trial_claims WHERE installation_id = 'i5'").length, 0)
  w.state.dcUpdateDown = false
  assert.equal((await ios('erin', 'i5', 'dev3:t', erin)).status, 200)
  // An Android build can't pass as iOS to skip Play Integrity: it has no token Apple accepts.
  assert.equal((await claim(w, deviceKey(), 'mallory', 'a9', { platform: 'ios', deviceToken: 'bad:forged' })).body.reason, 'device_unverifiable')
})

test('a sixth phone on a trial account is Free, like a paid one', async () => {
  const w = trialWorld()
  const key = deviceKey()
  await androidClaim(w, key, 'alice', 'p1')
  const keys = Array.from({ length: 7 }, () => deviceKey())
  for (let i = 2; i <= 6; i++) await sync(w, keys[i], 'alice', `p${i}`)
  assert.equal(snapshot(await sync(w, keys[6], 'alice', 'p6')).state, 'too_many_devices')
  assert.equal(snapshot(await sync(w, keys[5], 'alice', 'p5')).tier, 'plus')
})

test('a purchase during the trial takes over; the trial stays recorded', async () => {
  const w = trialWorld()
  const key = deviceKey()
  await androidClaim(w, key, 'alice', 'p1')
  w.state.subs.set('tok', activeSub())
  const bought = await signedCall(w, key, '/v1/verify', idToken('alice'), { purchaseToken: 'tok', productId: IDS[0], installId: 'p1', packageName: 'app.aurora' })
  const s = JSON.parse(bought.body.payload)
  assert.equal(s.source, 'subscription')
  assert.equal(s.autoRenewing, true)
  assert.equal((await sync(w, key, 'alice', 'p1')).body.trial.status, 'active')
})

test('housekeeping keeps claims of live accounts and drops unlinked ones three years after they ended', async () => {
  const w = trialWorld()
  await androidClaim(w, deviceKey(), 'alice', 'p1')
  await androidClaim(w, deviceKey(), 'bob', 'p2')
  w.DB.db.prepare("UPDATE trial_claims SET expires_at = ?, granted_at = ?").run(Date.now() - 4 * 365 * DAY, Date.now() - 4 * 365 * DAY - TRIAL)
  w.DB.db.prepare("UPDATE trial_claims SET account_id = NULL WHERE installation_id = 'p2'").run()
  await housekeeping(w.env, w.fetcher)
  assert.deepEqual(claimsIn(w).map((r) => r.installation_id), ['p1'])
})
