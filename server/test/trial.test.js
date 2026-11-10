// The 14-day trial: one per account, address and device; granted by the server; ends by itself (MONETIZATION_PLAN.md 3.2).
import test from 'node:test'
import assert from 'node:assert/strict'
import { generateKeyPairSync } from 'node:crypto'
import { housekeeping } from '../src/worker.js'
import { normalizeEmail } from '../src/trial.js'
import { activeSub, deviceKey, idToken, IDS, signedCall, verifySignature, world } from './fixture.js'
import { attestation } from './appattest-fixture.js'

const DAY = 86_400_000
const TRIAL = 14 * DAY
const dcKey = generateKeyPairSync('ec', { namedCurve: 'prime256v1' }).privateKey.export({ type: 'pkcs8', format: 'pem' })

function trialWorld() {
  const w = world()
  // Android trials exist only with Device Recall enforcing (founder, 2026-11-10).
  Object.assign(w.env, { TRIAL: 'on', TRIAL_PEPPER: 'pepper', DEVICE_RECALL: 'enforce', APPLE_TEAM_ID: 'TEAM', DEVICECHECK_KEY_ID: 'KID', DEVICECHECK_PRIVATE_KEY: dcKey })
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
  deviceIntegrity: { deviceRecognitionVerdict: ['MEETS_DEVICE_INTEGRITY'], deviceRecall: { values: { bitFirst: false }, writeDates: {} } },
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

/** The Android path: ask, get a nonce, ask again with the Integrity token made for it (Device Recall writes with it). */
async function androidClaim(w, key, uid, installId, shape = verdict, token = {}) {
  const first = await claim(w, key, uid, installId, {}, token)
  if (first.status !== 428) return first
  assert.equal(first.body.reason, 'attestation_required')
  w.state.verdict = () => shape(first.body.attestNonce)
  return claim(w, key, uid, installId, { integrityToken: `tok-${uid}-${installId}` }, token)
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
  assert.equal(row.evidence, 'play_integrity+recall')
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
  const results = await Promise.all(Array.from({ length: 20 }, (_, i) => claim(w, key, `a${i}`, 'p1', { integrityToken: 't' })))
  assert.equal(results.filter((r) => r.status === 200).length, 1, JSON.stringify(results.map((r) => r.body.reason ?? r.body.error ?? 'ok')))
  // The nonce is spent once, so the losers are refused before or at the grant; none is granted.
  assert.ok(results.filter((r) => r.status !== 200).every((r) => r.body.reason === 'device_used' || r.body.error === 'stale_nonce' || r.status === 429))
  assert.equal(claimsIn(w).length, 1)
})

test('racing claims: one account asking twice at once gets one trial and the same end both times', async () => {
  const w = trialWorld()
  const key = deviceKey()
  const first = await claim(w, key, 'alice', 'p1')
  w.state.verdict = () => verdict(first.body.attestNonce)
  const both = await Promise.all([claim(w, key, 'alice', 'p1', { integrityToken: 't' }), claim(w, key, 'alice', 'p1', { integrityToken: 't' })])
  const won = both.filter((r) => r.status === 200)
  assert.ok(won.length >= 1)
  // The slower one either found the grant or lost the nonce; asking again (what the app does) returns the same end.
  assert.ok(both.every((r) => r.status === 200 || r.body.error === 'stale_nonce'))
  const again = await claim(w, key, 'alice', 'p1')
  assert.equal(again.status, 200)
  assert.ok(won.every((r) => r.body.trial.endsAt === again.body.trial.endsAt))
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

test('account rules: any account age, never paid; off means off; unverified email never gets in', async () => {
  const w = trialWorld()
  const key = deviceKey()
  await sync(w, key, 'old', 'p1')
  // No account-age rule (founder, 2026-11-10): a two-year-old Free account that never had a trial gets one.
  w.DB.db.prepare("UPDATE accounts SET created_at = ? WHERE firebase_uid = 'old'").run(Date.now() - 730 * DAY)
  assert.equal((await sync(w, key, 'old', 'p1')).body.trial.status, 'available')
  assert.equal((await androidClaim(w, key, 'old', 'p1')).status, 200)

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

/** The inline path: the token for the claim's nonce comes with the claim, as the app sends it. */
async function inlineClaim(w, key, uid, installId, shape = verdict) {
  const first = await claim(w, key, uid, installId)
  if (first.status !== 428) return first
  w.state.verdict = () => shape(first.body.attestNonce)
  return claim(w, key, uid, installId, { integrityToken: `tok-${uid}` })
}
const recalled = (bit) => (n) => verdict(n, { deviceIntegrity: { deviceRecognitionVerdict: ['MEETS_DEVICE_INTEGRITY'], deviceRecall: { values: bit === null ? {} : { bitFirst: bit }, writeDates: {} } } })

test('the Integrity token rides on the claim itself; a nonce is spent once', async () => {
  const w = trialWorld()
  const key = deviceKey()
  const ok = await inlineClaim(w, key, 'alice', 'p1')
  assert.equal(ok.status, 200, JSON.stringify(ok.body))
  // Replaying the same token on another account: the nonce is gone.
  const replay = await claim(w, key, 'bob', 'p1', { integrityToken: 'tok-alice' })
  assert.ok(replay.status === 409 || replay.body.reason === 'device_used')
  assert.equal(w.state.recallWrites.length, 1, 'the one grant wrote its bit')
})

test('Android trials stay off unless Device Recall enforces: Play Integrity alone is not reinstall-proof', async () => {
  for (const mode of [undefined, 'off', 'shadow']) {
    const w = trialWorld()
    w.env.DEVICE_RECALL = mode
    const key = deviceKey()
    const offered = await signedCall(w, key, '/v1/account/sync', idToken('alice'), { installId: 'p1', zone: 'UTC', platform: 'android' })
    assert.deepEqual(offered.body.trial, { status: 'unavailable', reason: 'off' }, String(mode))
    const r = await inlineClaim(w, key, 'alice', 'p1')
    assert.equal(r.status, 404, String(mode))
    assert.equal(r.body.reason, 'off')
    assert.equal(claimsIn(w).length, 0)
    // iOS keeps its own device gate (DeviceCheck) and isn't affected.
    assert.equal((await claim(w, deviceKey(), 'bob', 'i1', { platform: 'ios', deviceToken: 'dev1:t' })).status, 200, String(mode))
  }
})

test('Device Recall enforce: a reset phone whose recall bit is set gets no second trial; a grant writes the bit', async () => {
  const w = trialWorld()
  w.env.DEVICE_RECALL = 'enforce'
  // The attestation-route verdict isn't enough with recall on: the claim needs its own token to write with.
  const key = deviceKey()
  const asked = await claim(w, key, 'alice', 'p1')
  w.state.verdict = () => recalled(false)(asked.body.attestNonce)
  assert.equal((await signedCall(w, key, '/v1/device/attest', idToken('alice'), { installId: 'p1', token: 'route' })).status, 200)
  const viaRoute = await claim(w, key, 'alice', 'p1')
  assert.equal(viaRoute.status, 428)
  assert.equal(viaRoute.body.inline, true)
  w.state.verdict = () => recalled(false)(viaRoute.body.attestNonce)
  const granted = await claim(w, key, 'alice', 'p1', { integrityToken: 'tok-a' })
  assert.equal(granted.status, 200, JSON.stringify(granted.body))
  assert.deepEqual(w.state.recallWrites, [{ integrityToken: 'tok-a', newValues: { bitFirst: true } }])
  assert.equal(claimsIn(w)[0].evidence, 'play_integrity+recall')

  // Factory reset: new install id, new key, new account; Google still remembers the phone.
  const reset = await inlineClaim(w, deviceKey(), 'bob', 'p2', recalled(true))
  assert.equal(reset.status, 403)
  assert.equal(reset.body.reason, 'device_used')
  // Unevaluated recall (no Play-licensed account, say): a reinstall would look new, so no trial.
  const unknown = await inlineClaim(w, deviceKey(), 'carol', 'p3', recalled(null))
  assert.equal(unknown.status, 403)
  assert.equal(unknown.body.reason, 'device_unverifiable')
  // Google can't record the bit: no trial yet, try later.
  w.state.recallDown = true
  const down = await inlineClaim(w, deviceKey(), 'dan', 'p4', recalled(false))
  assert.equal(down.status, 503)
  assert.equal(w.DB.rows("SELECT * FROM trial_claims WHERE installation_id = 'p4'").length, 0)
})

test('matrix: 90 strict minutes used, reinstall, same account: 30 minutes left, not a fresh 120', async () => {
  const w = trialWorld()
  w.env.DEVICE_QUOTA = 'enforce'
  const zone = 'UTC'
  const at = Date.now() + 60_000
  // A window that crosses UTC midnight is charged to two days; this test is about one.
  if (new Date(at + 91 * 60_000).getUTCDate() !== new Date(at).getUTCDate()) return
  const before = deviceKey()
  const r = await signedCall(w, before, '/v1/quota/reserve', idToken('alice'), { installId: 'old-install', idempotencyKey: 'k1', commitmentId: 'c1', mode: 'always_block', minutes: 90, zone, startAt: at })
  assert.equal(r.body.allowed, true, JSON.stringify(r.body))
  const after = deviceKey()
  const synced = await sync(w, after, 'alice', 'new-install')
  assert.equal(synced.body.quota.remainingMs, 30 * 60_000)
  assert.equal(synced.body.quota.limitedBy, 'account')
})

test('App Attest enforce: an iOS claim must come from Aurora itself, over a one-use nonce', async () => {
  const w = trialWorld()
  Object.assign(w.env, { APP_ATTEST: 'enforce', APPLE_BUNDLE_ID: 'com.aagamjain.Aurora' })
  const app = 'TEAM.com.aagamjain.Aurora'
  const ios = (uid, installId, key, more = {}) => claim(w, key, uid, installId, { platform: 'ios', deviceToken: `dev-${installId}:t`, ...more })

  // A DeviceCheck token alone is no longer enough: the server hands out a nonce to attest over.
  const key = deviceKey()
  const asked = await ios('alice', 'i1', key)
  assert.equal(asked.status, 428)
  assert.equal(asked.body.appAttest, true)
  const proof = attestation(asked.body.attestNonce, app)
  const ok = await ios('alice', 'i1', key, { appAttest: proof })
  assert.equal(ok.status, 200, JSON.stringify(ok.body))
  assert.equal(snapshot(ok).source, 'trial')

  // The same attestation again, from another account on another phone: its nonce isn't this device's.
  const k2 = deviceKey()
  await ios('bob', 'i2', k2)
  assert.equal((await ios('bob', 'i2', k2, { appAttest: proof })).status, 403)
  assert.equal(w.DB.rows("SELECT COUNT(*) AS n FROM abuse_events WHERE kind = 'app_attest_failed'")[0].n, 1)
  // That spent bob's nonce: the next try needs a new one, and an attestation for another app is refused.
  const again = await ios('bob', 'i2', k2)
  assert.equal(again.status, 428)
  const other = await ios('bob', 'i2', k2, { appAttest: attestation(again.body.attestNonce, 'OTHER.com.example') })
  assert.equal(other.body.reason, 'device_unverifiable')
  // Nothing about App Attest touches Android or the DeviceCheck bit for a refused claim.
  assert.equal(claimsIn(w).length, 1)
})
