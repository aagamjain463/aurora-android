// Free's allowance can't be refilled by switching, creating or deleting accounts (docs/ANTI_ABUSE.md).
import test from 'node:test'
import assert from 'node:assert/strict'
import { housekeeping } from '../src/worker.js'
import { readFileSync } from 'node:fs'
import { derToRaw } from '../src/device.js'
import { IDS, activeSub, deviceKey, idToken, signedCall, world } from './fixture.js'

const MIN = 60_000
// A zone where it is about 6 in the morning, so no test here runs into midnight and splits a day.
const UTC = (() => {
  let offset = 6 - new Date().getUTCHours()
  if (offset < -12) offset += 24
  return offset === 0 ? 'Etc/GMT' : offset > 0 ? `Etc/GMT-${offset}` : `Etc/GMT+${-offset}`
})()
// Time the phone isn't already committed for. Inside a commitment still running, more costs the phone nothing.
const T0 = Date.now()
const after = (minutes) => T0 + minutes * MIN
const reserve = (w, uid, minutes, over = {}) => w.call('/v1/quota/reserve', idToken(uid), {
  installId: 'phone', idempotencyKey: crypto.randomUUID(), commitmentId: crypto.randomUUID(), mode: 'always_block', minutes, zone: UTC, ...over,
})
const sync = (w, uid, installId = 'phone', zone = UTC) => w.call('/v1/account/sync', idToken(uid), { installId, zone })
const remaining = async (w, uid, installId = 'phone') => (await sync(w, uid, installId)).body.quota.remainingMs
const events = (w, kind) => w.DB.rows('SELECT * FROM abuse_events WHERE kind = ?', kind)
const buy = (w, uid, installId = 'phone') => w.call('/v1/verify', idToken(uid), { purchaseToken: 'tok', productId: IDS[0], installId, packageName: 'app.aurora' })

// ---- Account switching on one installation ----------------------------------------------------------------

test('1. A uses 110 minutes, switches to B on the same phone, and B has 10', async () => {
  const w = world()
  assert.equal((await reserve(w, 'a', 110, { startAt: after(1) })).body.allowed, true)
  assert.equal(await remaining(w, 'b'), 10 * MIN)
  const over = await reserve(w, 'b', 11, { startAt: after(111) })
  assert.equal(over.body.allowed, false)
  assert.equal(over.body.limitedBy, 'device')
  assert.equal(over.body.remainingMs, 10 * MIN)
  assert.equal((await reserve(w, 'b', 10, { startAt: after(111) })).body.allowed, true)
})

test('3. switching back to A resets nothing: A, B and the phone are all spent', async () => {
  const w = world()
  await reserve(w, 'a', 110, { startAt: after(1) })
  await reserve(w, 'b', 10, { startAt: after(111) })
  assert.equal(await remaining(w, 'a'), 0)
  assert.equal((await reserve(w, 'a', 1, { startAt: after(121) })).body.allowed, false)
  assert.equal(await remaining(w, 'b'), 0)
  // Many accounts later, still the same phone.
  for (const uid of ['c', 'd', 'e']) assert.equal((await reserve(w, uid, 1, { startAt: after(121) })).body.allowed, false, uid)
})

test('4. simultaneous reservations from many accounts on one phone can\'t exceed its allowance', async () => {
  const w = world()
  // Six windows that don't overlap: any two fit in 120 minutes, no three do.
  const results = await Promise.all(['a', 'b', 'c', 'd', 'e', 'f'].map((uid, i) => reserve(w, uid, 50, { startAt: after(1 + i * 50) })))
  assert.equal(results.filter((r) => r.body.allowed).length, 2, JSON.stringify(results.map((r) => r.body)))
  assert.equal(w.DB.rows('SELECT * FROM quota_reservations').length, 2)
})

test('4b. simultaneous reservations from one account on many phones still share the account', async () => {
  const w = world()
  const results = await Promise.all(Array.from({ length: 6 }, (_, i) => reserve(w, 'a', 50, { installId: `p${i}` })))
  assert.equal(results.filter((r) => r.body.allowed).length, 2)
})

test('5. several phones on one account share the account\'s allowance; a fresh phone and account are their own', async () => {
  const w = world()
  await reserve(w, 'a', 90, { installId: 'p1' })
  assert.equal(await remaining(w, 'a', 'p2'), 30 * MIN)
  assert.equal((await reserve(w, 'a', 31, { installId: 'p2' })).body.limitedBy, 'account')
  assert.equal((await reserve(w, 'c', 120, { installId: 'p3' })).body.allowed, true)
})

test('6. overlapping commitments on one phone count once, whichever account made them', async () => {
  const w = world()
  const start = after(1)
  await reserve(w, 'a', 100, { startAt: start })
  // Covers only time A already covers on this phone: the phone pays nothing more, B's account pays its own.
  assert.equal((await reserve(w, 'b', 100, { startAt: start })).body.allowed, true)
  assert.equal(await remaining(w, 'b'), 20 * MIN)
  assert.equal((await reserve(w, 'b', 21, { startAt: start + 100 * MIN })).body.allowed, false)
  assert.equal((await reserve(w, 'b', 20, { startAt: start + 100 * MIN })).body.allowed, true)
})

test('7. an existing commitment survives another account using the phone, and only its owner can touch it', async () => {
  const w = world()
  const later = await reserve(w, 'a', 30, { startAt: Date.now() + 3_600_000 })
  const id = JSON.parse(later.body.authorization.payload).reservationId
  await sync(w, 'b')
  await reserve(w, 'b', 30)
  assert.equal((await w.call('/v1/quota/release', idToken('b'), { reservationId: id })).status, 404)
  assert.equal(w.DB.rows('SELECT status FROM quota_reservations WHERE id = ?', id)[0].status, 'reserved')
})

test('8. a new time zone on another account doesn\'t open a new day on the phone', async () => {
  const w = world()
  await reserve(w, 'a', 120)
  const after = Date.now() + 125 * MIN
  for (const zone of ['Pacific/Kiritimati', 'Pacific/Pago_Pago', 'Asia/Kolkata']) {
    const res = await reserve(w, 'b', 5, { startAt: after, zone })
    assert.equal(res.body.allowed, false, zone)
  }
})

test('the client\'s clock never decides: a start in the past is charged from the server\'s now', async () => {
  const w = world()
  const res = await reserve(w, 'a', 30, { startAt: Date.now() - 20 * MIN })
  const a = JSON.parse(res.body.authorization.payload)
  assert.ok(a.start >= Date.now() - 1000)
  assert.ok(Math.abs(await remaining(w, 'b') - 110 * MIN) < 1000, 'only the 10 minutes still ahead were reserved')
})

// ---- Replay and idempotency ---------------------------------------------------------------------------------

test('9. a replayed reservation returns the same authorization and spends nothing more', async () => {
  const w = world()
  const key = deviceKey()
  const token = idToken('a')
  const request = { installId: 'phone', idempotencyKey: 'k1', commitmentId: 'c1', mode: 'always_block', minutes: 60, zone: UTC }
  const first = await signedCall(w, key, '/v1/quota/reserve', token, request)
  const again = await signedCall(w, key, '/v1/quota/reserve', token, request)
  assert.equal(again.body.replayed, true)
  assert.equal(JSON.parse(again.body.authorization.payload).reservationId, JSON.parse(first.body.authorization.payload).reservationId)
  assert.equal(w.DB.rows('SELECT * FROM quota_reservations').length, 1)
  // The authorization names its account, installation and commitment; the app accepts it for nothing else.
  const a = JSON.parse(first.body.authorization.payload)
  assert.deepEqual([a.uid, a.installId, a.commitmentId], ['a', 'phone', 'c1'])
})

test('9b. a captured device proof is worthless with another sign-in or another body', async () => {
  const w = world()
  const key = deviceKey()
  await signedCall(w, key, '/v1/account/sync', idToken('a'), { installId: 'phone', zone: UTC })
  const raw = JSON.stringify({ installId: 'phone', idempotencyKey: 'k', commitmentId: 'c', mode: 'always_block', minutes: 10, zone: UTC })
  const proof = key.proof('/v1/quota/reserve', idToken('a'), raw)
  assert.equal((await w.call('/v1/quota/reserve', idToken('b', { iat: Math.floor(Date.now() / 1000) - 5 }), raw, proof)).status, 403)
  const altered = raw.replace('"minutes":10', '"minutes":100')
  assert.equal((await w.call('/v1/quota/reserve', idToken('a'), altered, proof)).status, 403)
})

// ---- Plus ---------------------------------------------------------------------------------------------------

test('10. Plus has no daily limit and charges no phone; once it expires, Free\'s limits apply again', async () => {
  const w = world()
  w.state.subs.set('tok', activeSub())
  await buy(w, 'a')
  assert.equal((await reserve(w, 'a', 600)).body.tier, 'plus')
  assert.equal(await remaining(w, 'b'), 120 * MIN, 'Plus time isn\'t Free allowance')
  w.DB.db.prepare('UPDATE purchases SET paid_through = ?').run(Date.now() - 1000)
  assert.equal((await reserve(w, 'a', 121)).body.allowed, false)
  assert.equal((await reserve(w, 'a', 120)).body.tier, 'free')
})

test('Plus doesn\'t erase Free time already spent on the phone', async () => {
  const w = world()
  await reserve(w, 'free-a', 110)
  w.state.subs.set('tok', activeSub())
  await buy(w, 'plus-p')
  assert.equal((await reserve(w, 'plus-p', 300)).body.tier, 'plus')
  assert.equal(await remaining(w, 'free-b'), 10 * MIN)
})

test('a sixth phone on a Plus account is Free in its reservations too, not only in its snapshot', async () => {
  const w = world()
  w.state.subs.set('tok', activeSub())
  await buy(w, 'a', 'p1')
  for (const p of ['p2', 'p3', 'p4', 'p5']) await sync(w, 'a', p)
  const sixth = await reserve(w, 'a', 121, { installId: 'p6' })
  assert.equal(sixth.body.tier, 'free')
  assert.equal(sixth.body.allowed, false)
})

// ---- Deletion and recreation --------------------------------------------------------------------------------

test('11. deleting the account doesn\'t give the phone a new allowance, during the grace period or after', async () => {
  const w = world()
  await reserve(w, 'a', 110)
  await w.call('/v1/account/delete', idToken('a'), {})
  assert.equal(await remaining(w, 'b'), 10 * MIN, 'during the grace period')

  // Purge now (the manual runbook's path), while today's time still matters.
  w.DB.db.prepare('UPDATE accounts SET deletion_requested_at = 0 WHERE firebase_uid = ?').run('a')
  await housekeeping(w.env, w.fetcher)
  assert.deepEqual(w.state.deleted, ['a'])
  assert.equal(w.DB.rows("SELECT * FROM accounts WHERE firebase_uid = 'a'").length, 0)
  const kept = w.DB.rows('SELECT * FROM device_usage')
  assert.equal(kept.length, 1)
  assert.deepEqual(Object.keys(kept[0]).sort(), ['end_at', 'installation_id', 'start_at', 'zone'], 'nothing that says whose')
  assert.equal(await remaining(w, 'a-again'), 10 * MIN, 'the same person, a new account, the same phone')

  // Kept only as long as the quota can look back.
  await housekeeping(w.env, w.fetcher, Date.now() + 4 * 86_400_000)
  assert.equal(w.DB.rows('SELECT * FROM device_usage').length, 0)
})

// ---- Device credentials ---------------------------------------------------------------------------------------

test('12. a replacement phone keeps the account\'s ledger; a lost key recovers as a new installation', async () => {
  const w = world()
  const old = deviceKey()
  const body = (minutes, installId) => ({ installId, idempotencyKey: crypto.randomUUID(), commitmentId: crypto.randomUUID(), mode: 'always_block', minutes, zone: UTC })
  assert.equal((await signedCall(w, old, '/v1/quota/reserve', idToken('a'), body(110, 'old-phone'))).body.allowed, true)
  assert.ok(w.DB.rows("SELECT public_key FROM devices WHERE id = 'old-phone'")[0].public_key, 'first key seen is bound')

  // The Keystore key is gone (or someone else has the id): that id can't be used without its key.
  const fresh = deviceKey()
  assert.equal((await signedCall(w, fresh, '/v1/account/sync', idToken('a'), { installId: 'old-phone', zone: UTC })).body.error, 'device_proof_required')
  assert.equal((await w.call('/v1/account/sync', idToken('a'), { installId: 'old-phone', zone: UTC })).status, 403, 'nor unsigned')

  // The app starts over as a new installation. The account's ledger came along; nothing was refilled.
  const res = await signedCall(w, fresh, '/v1/quota/reserve', idToken('a'), body(11, 'new-phone'))
  assert.equal(res.body.allowed, false)
  assert.equal(res.body.limitedBy, 'account')
  assert.equal((await signedCall(w, fresh, '/v1/quota/reserve', idToken('a'), body(10, 'new-phone'))).body.allowed, true)
  // A different person on the second-hand phone has the phone's own allowance, not the previous owner's account.
  assert.equal((await signedCall(w, fresh, '/v1/account/sync', idToken('buyer'), { installId: 'new-phone', zone: UTC })).body.quota.remainingMs, 110 * MIN)
})

test('forged installation ids: a bound id can\'t be spent or charged by anyone without its key', async () => {
  const w = world()
  const victim = deviceKey()
  await signedCall(w, victim, '/v1/account/sync', idToken('victim'), { installId: 'victims-phone', zone: UTC })
  const attacker = deviceKey()
  const grief = { installId: 'victims-phone', idempotencyKey: 'g', commitmentId: 'g', mode: 'always_block', minutes: 120, zone: UTC }
  assert.equal((await signedCall(w, attacker, '/v1/quota/reserve', idToken('mallory'), grief)).status, 403)
  assert.equal((await reserve(w, 'mallory', 120, { installId: 'victims-phone' })).status, 403)
  const entries = [{ start: Date.now() - 10 * MIN, end: Date.now() + 100 * MIN, zone: UTC }]
  assert.equal((await w.call('/v1/quota/import', idToken('mallory'), { installId: 'victims-phone', entries })).status, 403)
  assert.equal(w.DB.rows('SELECT * FROM quota_reservations').length, 0)
  assert.ok(events(w, 'device_proof_failed').length >= 3)
  const proof = { installId: 'victims-phone', zone: UTC }
  assert.equal((await signedCall(w, victim, '/v1/account/sync', idToken('victim'), proof)).body.quota.remainingMs, 120 * MIN)
})

test('two first requests racing with different keys bind exactly one', async () => {
  const w = world()
  const keys = [deviceKey(), deviceKey(), deviceKey()]
  const results = await Promise.all(keys.map((k, i) => signedCall(w, k, '/v1/account/sync', idToken(`u${i}`), { installId: 'contested', zone: UTC })))
  assert.equal(results.filter((r) => r.status === 200).length, 1, JSON.stringify(results.map((r) => r.status)))
})

test('an unproved installation (an older app, no Keystore) still works and still has one allowance', async () => {
  const w = world()
  const res = await sync(w, 'a')
  assert.equal(res.body.device.proved, false)
  await reserve(w, 'a', 120, { startAt: after(1) })
  assert.equal((await reserve(w, 'b', 1, { startAt: after(121) })).body.allowed, false)
  assert.equal(w.DB.rows("SELECT public_key FROM devices WHERE id = 'phone'")[0].public_key, null)
})

test('a malformed signature or key proves nothing and breaks nothing', async () => {
  const w = world()
  const headers = { 'x-aurora-device-key': 'bm90IGEga2V5', 'x-aurora-device-signature': '!!!' }
  const res = await w.call('/v1/account/sync', idToken('a'), { installId: 'phone', zone: UTC }, headers)
  assert.equal(res.status, 200)
  assert.equal(res.body.device.proved, false)
  assert.equal(derToRaw(new Uint8Array([0x30, 0x02, 0x02, 0x00])), null)
})

// ---- Malformed requests ---------------------------------------------------------------------------------------

test('malformed requests are refused with a structured error and change nothing', async () => {
  const w = world()
  for (const installId of ['', 'has space', 'x'.repeat(65), '../etc', 42, null]) {
    assert.equal((await reserve(w, 'a', 10, { installId })).status, 400, String(installId))
    assert.equal((await w.call('/v1/account/sync', idToken('a'), { installId, zone: UTC })).status, 400, String(installId))
  }
  for (const raw of ['not json', '[]', '"string"', 'null', '5']) {
    const res = await w.call('/v1/quota/reserve', idToken('a'), raw)
    assert.equal(res.status, 400, raw)
    assert.equal(res.body.error, 'bad_request')
  }
  const huge = await w.call('/v1/account/sync', idToken('a'), { installId: 'phone', pad: 'x'.repeat(70_000) })
  assert.equal(huge.status, 413)
  assert.equal(w.DB.rows('SELECT * FROM quota_reservations').length, 0)
})

test('one phone can\'t out-ask the rate limit by spreading requests over accounts', async () => {
  const w = world()
  let last
  for (let i = 0; i < 31; i++) last = await reserve(w, `u${i}`, 121)
  assert.equal(last.status, 429)
})

// ---- Rollout flags ----------------------------------------------------------------------------------------------

test('shadow mode records what it would refuse and refuses nothing; off reads no device ledger', async () => {
  const w = world()
  w.env.DEVICE_QUOTA = 'shadow'
  await reserve(w, 'a', 110, { startAt: after(1) })
  assert.equal((await reserve(w, 'b', 60, { startAt: after(111) })).body.allowed, true)
  assert.equal(events(w, 'device_quota_shadow').length, 1)
  w.env.DEVICE_QUOTA = 'off'
  assert.equal((await reserve(w, 'c', 60, { startAt: after(171) })).body.allowed, true)
  assert.equal(events(w, 'device_quota_shadow').length, 1)
})

test('enforcement records each refusal, and many accounts on one phone are noted, never refused for it', async () => {
  const w = world()
  await reserve(w, 'a', 120, { startAt: after(1) })
  await reserve(w, 'b', 10, { startAt: after(121) })
  assert.equal(events(w, 'device_quota_refused').length, 1)
  for (const uid of ['c', 'd', 'e']) await sync(w, uid)
  assert.equal(events(w, 'many_accounts').length, 2, 'the fourth and fifth account on one phone')
  assert.equal((await sync(w, 'f')).status, 200)
  for (const e of w.DB.rows('SELECT * FROM abuse_events')) assert.ok(!JSON.stringify(e).includes('eyJ'), 'no token in an event')
})

// ---- Play Integrity ---------------------------------------------------------------------------------------------

const verdict = (nonce, over = {}) => ({
  requestDetails: { requestPackageName: 'app.aurora', nonce, timestampMillis: String(Date.now()) },
  appIntegrity: { appRecognitionVerdict: 'PLAY_RECOGNIZED', packageName: 'app.aurora', certificateSha256Digest: ['cert'] },
  deviceIntegrity: { deviceRecognitionVerdict: ['MEETS_DEVICE_INTEGRITY'] },
  ...over,
})

async function attested(w, key, uid, shape) {
  const synced = await signedCall(w, key, '/v1/account/sync', idToken(uid), { installId: 'phone', zone: UTC })
  const nonce = synced.body.device.attestNonce
  w.state.verdict = () => shape(nonce)
  return { nonce, res: await signedCall(w, key, '/v1/device/attest', idToken(uid), { installId: 'phone', token: 'integrity-token' }) }
}

test('13. a genuine verdict is recorded once; its nonce can\'t be presented again', async () => {
  const w = world()
  w.env.INTEGRITY = 'observe'
  w.env.APP_CERT_SHA256 = 'cert'
  const key = deviceKey()
  const { nonce, res } = await attested(w, key, 'a', (n) => verdict(n))
  assert.ok(nonce)
  assert.equal(res.body.integrity, 'ok')
  const again = await signedCall(w, key, '/v1/device/attest', idToken('a'), { installId: 'phone', token: 'integrity-token' })
  assert.equal(again.body.error, 'stale_nonce')
  const later = await signedCall(w, key, '/v1/account/sync', idToken('a'), { installId: 'phone', zone: UTC })
  assert.equal(later.body.device.attestNonce, null, 'not asked again for a week')
})

test('13b. unavailable or inconclusive integrity is never held against anyone', async () => {
  const w = world()
  w.env.INTEGRITY = 'enforce'
  const key = deviceKey()
  const synced = await signedCall(w, key, '/v1/account/sync', idToken('a'), { installId: 'phone', zone: UTC })
  w.state.integrityDown = true
  const down = await signedCall(w, key, '/v1/device/attest', idToken('a'), { installId: 'phone', token: 't' })
  assert.equal(down.status, 503)
  assert.ok(synced.body.device.attestNonce)
  w.state.integrityDown = false
  // A verdict for another nonce (or another app) says nothing, and spends the nonce.
  w.state.verdict = () => verdict('someone-elses')
  assert.equal((await signedCall(w, key, '/v1/device/attest', idToken('a'), { installId: 'phone', token: 't' })).body.error, 'integrity_mismatch')
  // A genuine phone with an unlocked bootloader is 'basic', and nothing is refused.
  const { res } = await attested(w, key, 'a', (n) => verdict(n, { deviceIntegrity: { deviceRecognitionVerdict: ['MEETS_BASIC_INTEGRITY'] } }))
  assert.equal(res.body.integrity, 'basic')
  assert.equal((await reserve(w, 'a', 30)).status, 403, 'still needs its key')
  const body = { installId: 'phone', idempotencyKey: 'x', commitmentId: 'x', mode: 'always_block', minutes: 30, zone: UTC }
  assert.equal((await signedCall(w, key, '/v1/quota/reserve', idToken('a'), body)).body.allowed, true)
  // No Play Integrity at all: an unproved installation is never asked and never refused.
  assert.equal((await reserve(w, 'b', 30, { installId: 'degoogled' })).body.allowed, true)
})

test('13c. a modified build is held back from Free only when enforcing, never from Plus, and can recover', async () => {
  const w = world()
  w.env.INTEGRITY = 'observe'
  const key = deviceKey()
  const body = (uid) => ({ installId: 'phone', idempotencyKey: crypto.randomUUID(), commitmentId: uid, mode: 'always_block', minutes: 10, zone: UTC })
  const { res } = await attested(w, key, 'a', (n) => verdict(n, { appIntegrity: { appRecognitionVerdict: 'UNRECOGNIZED_VERSION' } }))
  assert.equal(res.body.integrity, 'failed')
  assert.equal(events(w, 'integrity_failed').length, 1)
  assert.equal((await signedCall(w, key, '/v1/quota/reserve', idToken('a'), body('a'))).body.allowed, true, 'observe only watches')
  w.env.INTEGRITY = 'enforce'
  assert.equal((await signedCall(w, key, '/v1/quota/reserve', idToken('a'), body('a'))).body.error, 'integrity_failed')
  // The way back: install Aurora from Play. A failed device is asked again at once, and a genuine verdict clears it.
  const { res: fixed } = await attested(w, key, 'a', (n) => verdict(n))
  assert.equal(fixed.body.integrity, 'ok')
  assert.equal((await signedCall(w, key, '/v1/quota/reserve', idToken('a'), body('a2'))).body.allowed, true)
})

test('13d. Plus is never held back by integrity', async () => {
  const w = world()
  w.env.INTEGRITY = 'enforce'
  const key = deviceKey()
  await attested(w, key, 'a', (n) => verdict(n, { deviceIntegrity: { deviceRecognitionVerdict: [] } }))
  assert.equal(w.DB.rows("SELECT integrity FROM devices WHERE id = 'phone'")[0].integrity, 'failed')
  w.state.subs.set('tok', activeSub())
  await signedCall(w, key, '/v1/verify', idToken('a'), { purchaseToken: 'tok', productId: IDS[0], installId: 'phone', packageName: 'app.aurora' })
  const res = await signedCall(w, key, '/v1/quota/reserve', idToken('a'), { installId: 'phone', idempotencyKey: 'p', commitmentId: 'p', mode: 'always_block', minutes: 600, zone: UTC })
  assert.equal(res.body.tier, 'plus')
})

test('integrity is off unless turned on, and an old verdict or another app\'s is ignored', async () => {
  const w = world()
  const key = deviceKey()
  const synced = await signedCall(w, key, '/v1/account/sync', idToken('a'), { installId: 'phone', zone: UTC })
  assert.equal(synced.body.device.attestNonce, null)
  assert.equal((await signedCall(w, key, '/v1/device/attest', idToken('a'), { installId: 'phone', token: 't' })).status, 404)
  w.env.INTEGRITY = 'observe'
  const stale = await attested(w, key, 'a', (n) => verdict(n, { requestDetails: { requestPackageName: 'app.aurora', nonce: n, timestampMillis: String(Date.now() - 3_600_000) } }))
  assert.equal(stale.res.body.error, 'integrity_mismatch')
  const other = await attested(w, key, 'a', (n) => verdict(n, { requestDetails: { requestPackageName: 'com.other', nonce: n, timestampMillis: String(Date.now()) } }))
  assert.equal(other.res.body.error, 'integrity_mismatch')
  assert.equal(w.DB.rows("SELECT integrity FROM devices WHERE id = 'phone'")[0].integrity, 'unknown')
})

test('nobody can use up another phone\'s request budget by naming its id', async () => {
  const w = world()
  const victim = deviceKey()
  await signedCall(w, victim, '/v1/account/sync', idToken('victim'), { installId: 'victims-phone', zone: UTC })
  for (let i = 0; i < 31; i++) await reserve(w, `mallory${i}`, 5, { installId: 'victims-phone' })
  const body = { installId: 'victims-phone', idempotencyKey: 'v', commitmentId: 'v', mode: 'always_block', minutes: 5, zone: UTC }
  assert.equal((await signedCall(w, victim, '/v1/quota/reserve', idToken('victim'), body)).body.allowed, true)
})

// ---- Found in review (2026-11-04) ------------------------------------------------------------------------------

const release = (w, uid, reservationId) => w.call('/v1/quota/release', idToken(uid), { reservationId })
const reservationOf = (res) => JSON.parse(res.body.authorization.payload).reservationId

test('a schedule paused and turned back on is charged again: a released key never replays for free', async () => {
  const w = world()
  // The app's key for a recurring occurrence is fixed ("rec:<schedule>:<start>"), so the same key comes back.
  const occurrence = { idempotencyKey: 'rec:s1:1', startAt: after(60) }
  const first = await reserve(w, 'a', 100, occurrence)
  assert.equal((await release(w, 'a', reservationOf(first))).body.released, true)
  assert.equal(await remaining(w, 'a'), 120 * MIN, 'given back')
  const again = await reserve(w, 'a', 100, occurrence)
  assert.equal(again.body.allowed, true)
  assert.notEqual(again.body.replayed, true)
  assert.notEqual(reservationOf(again), reservationOf(first))
  assert.equal(await remaining(w, 'a'), 20 * MIN, 'charged again')
  // Paused and resumed once more: still one live reservation, never more time than the allowance.
  await release(w, 'a', reservationOf(again))
  assert.equal((await reserve(w, 'a', 100, occurrence)).body.allowed, true)
  assert.equal((await reserve(w, 'a', 21, { startAt: after(200) })).body.allowed, false)
})

test('keys released before the fix are freed by migration 0003', async () => {
  const w = world()
  const first = await reserve(w, 'a', 100, { idempotencyKey: 'rec:s1:2', startAt: after(60) })
  // As the previous worker released it: status only, key kept.
  w.DB.db.prepare("UPDATE quota_reservations SET status = 'released' WHERE id = ?").run(reservationOf(first))
  w.DB.db.exec(readFileSync(new URL('../migrations/0003_released_keys.sql', import.meta.url), 'utf8'))
  const again = await reserve(w, 'a', 100, { idempotencyKey: 'rec:s1:2', startAt: after(60) })
  assert.notEqual(again.body.replayed, true)
  assert.equal(await remaining(w, 'a'), 20 * MIN)
})

test('one installation can\'t borrow another\'s reservation by reusing its key', async () => {
  const w = world()
  await reserve(w, 'a', 100, { installId: 'p1', idempotencyKey: 'k' })
  const borrowed = await reserve(w, 'a', 100, { installId: 'p2', idempotencyKey: 'k' })
  assert.equal(borrowed.status, 409)
  assert.equal(borrowed.body.error, 'idempotency_conflict')
  assert.equal(w.DB.rows("SELECT * FROM quota_reservations WHERE installation_id = 'p2'").length, 0)
})

test('a failed verdict isn\'t washed away by a new install id on the same account, and a genuine one clears it', async () => {
  const w = world()
  w.env.INTEGRITY = 'enforce'
  await attested(w, deviceKey(), 'a', (n) => verdict(n, { appIntegrity: { appRecognitionVerdict: 'UNRECOGNIZED_VERSION' } }))
  // Start over as a fresh installation: unsigned, or with a new key.
  assert.equal((await reserve(w, 'a', 10, { installId: 'fresh-unsigned' })).body.error, 'integrity_failed')
  const fresh = deviceKey()
  const body = { installId: 'fresh', idempotencyKey: 'f', commitmentId: 'f', mode: 'always_block', minutes: 10, zone: UTC }
  const synced = await signedCall(w, fresh, '/v1/account/sync', idToken('a'), { installId: 'fresh', zone: UTC })
  assert.equal((await signedCall(w, fresh, '/v1/quota/reserve', idToken('a'), body)).body.error, 'integrity_failed')
  // Other accounts, and the same account outside enforce, are untouched.
  assert.equal((await reserve(w, 'b', 10, { installId: 'elsewhere' })).body.allowed, true)
  w.env.INTEGRITY = 'observe'
  assert.equal((await reserve(w, 'a', 10, { installId: 'fresh-unsigned' })).body.allowed, true)
  w.env.INTEGRITY = 'enforce'
  // This installation's own genuine verdict clears it, as does a week passing.
  w.state.verdict = () => verdict(synced.body.device.attestNonce)
  assert.equal((await signedCall(w, fresh, '/v1/device/attest', idToken('a'), { installId: 'fresh', token: 't' })).body.integrity, 'ok')
  assert.equal((await signedCall(w, fresh, '/v1/quota/reserve', idToken('a'), body)).body.allowed, true)
  w.DB.db.prepare("UPDATE devices SET integrity_at = integrity_at - 8 * 86400000 WHERE id = 'phone'").run()
  assert.equal((await reserve(w, 'a', 10, { installId: 'fresh-unsigned', startAt: after(30) })).body.allowed, true)
})
