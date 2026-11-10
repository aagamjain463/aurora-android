// Audit regressions (audit/SECURITY_FINDINGS.md, audit/SCALABILITY_REPORT.md). Each test failed before its fix.
// Runs in its own process (node --test), so the module-level caches in auth.js and worker.js start empty here.
import test from 'node:test'
import assert from 'node:assert/strict'
import { idToken, world } from './fixture.js'

const sync = (w, uid, installId = 'i1') => w.call('/v1/account/sync', idToken(uid), { installId, zone: 'UTC' })

// SCALE-1: every authenticated request minted a new Google OAuth token (an RSA signature and a round trip to
// oauth2.googleapis.com) before the identity lookup. The token is good for an hour.
test('Google access tokens are reused across requests, not minted per request', async () => {
  const w = world()
  for (let i = 0; i < 5; i++) assert.equal((await sync(w, 'alice')).status, 200)
  assert.equal(w.state.accessTokens, 1)
  assert.equal(w.state.lookups, 5) // revocation is still asked every time
})

// SEC-2: any token naming an unknown key id made the worker refetch Google's key set, once per request.
test('an unknown key id cannot make every request refetch the key set', async () => {
  const w = world()
  assert.equal((await sync(w, 'alice')).status, 200)
  const before = w.state.jwksFetches
  for (let i = 0; i < 5; i++) {
    const res = await w.call('/v1/account/sync', idToken('mallory', {}, { kid: `nope-${i}` }), { installId: 'i1' })
    assert.equal(res.status, 401)
  }
  assert.equal(w.state.jwksFetches - before, 0)
})

test('a key id first seen after the throttle window is still looked up, so a rotation never locks anyone out', async () => {
  const w = world()
  assert.equal((await sync(w, 'alice')).status, 200)
  const real = Date.now
  try {
    const later = real() + 61_000
    Date.now = () => later
    const before = w.state.jwksFetches
    await w.call('/v1/account/sync', idToken('mallory', {}, { kid: 'rotated' }), { installId: 'i1' })
    assert.equal(w.state.jwksFetches - before, 1)
  } finally {
    Date.now = real
  }
})

// SEC-3: the unsigned Auth Emulator path was open in any environment not literally named "production" (a staging
// deploy, or one whose vars were never set), whenever FIREBASE_AUTH_EMULATOR was set there.
test('unsigned emulator tokens are refused everywhere except an explicit development environment', async () => {
  const token = idToken('u', {}, { alg: 'none' })
  for (const environment of ['staging', undefined, '']) {
    const w = world()
    w.env.FIREBASE_AUTH_EMULATOR = '1'
    w.env.ENVIRONMENT = environment
    assert.equal((await w.call('/v1/account/sync', token, { installId: 'i1' })).status, 401, String(environment))
  }
})

// SEC-4: /v1/quota/import stored up to 200 rows per call, 10 calls an hour, with any end time. Rows ending far in
// the future were never purged, so one verified account could grow the database without bound.
test('imported entries end within a day, and an account can only import so much', async () => {
  const w = world()
  const now = Date.now()
  const far = [{ start: now - 60_000, end: now + 365 * 86_400_000, zone: 'UTC' }]
  assert.equal((await w.call('/v1/quota/import', idToken('alice'), { installId: 'i1', entries: far })).status, 200)
  const [row] = w.DB.rows('SELECT end_at FROM quota_reservations')
  assert.ok(row.end_at <= Date.now() + 86_400_000, 'end is capped')

  let stored = 1
  for (let call = 0; call < 5; call++) {
    const entries = Array.from({ length: 200 }, (_, i) => ({ start: now - 3_600_000 - call * 1000 - i - 1, end: now - 60_000, zone: 'UTC' }))
    const res = await w.call('/v1/quota/import', idToken('alice'), { installId: 'i1', entries })
    assert.equal(res.status, 200)
    stored += res.body.imported
  }
  assert.ok(stored <= 400, `stored ${stored}`)
  assert.equal(w.DB.rows('SELECT * FROM quota_reservations').length, stored)
})

// REL-1: the body was read whole before its size was checked. A declared length over the limit is refused unread.
test('a body declared larger than the limit is refused before it is read', async () => {
  const w = world()
  let read = false
  const request = new Request('https://x/v1/account/sync', { method: 'POST', headers: { 'content-length': String(10 * 1024 * 1024) }, body: 'x' })
  const text = request.text.bind(request)
  request.text = () => { read = true; return text() }
  const { default: worker } = await import('../src/worker.js')
  const res = await worker.fetch(request, w.env, null, w.fetcher)
  assert.equal(res.status, 413)
  assert.equal(read, false)
})
