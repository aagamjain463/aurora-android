import test from 'node:test'
import assert from 'node:assert/strict'
import { generateKeyPairSync, createVerify } from 'node:crypto'
import worker, { entitlementFrom } from '../src/worker.js'

const signing = generateKeyPairSync('rsa', { modulusLength: 2048 })
const google = generateKeyPairSync('rsa', { modulusLength: 2048 })
const pem = (k) => k.export({ type: 'pkcs8', format: 'pem' })
const der = (k) => k.export({ type: 'pkcs8', format: 'der' }).toString('base64')
const IDS = ['aurora_plus_monthly', 'aurora_plus_annual']
const FUTURE = new Date(Date.now() + 30 * 86_400_000).toISOString()
const PAST = new Date(Date.now() - 86_400_000).toISOString()

function env() {
  const kv = new Map()
  return {
    PACKAGE_NAME: 'app.aurora',
    PRODUCT_IDS: IDS.join(','),
    GOOGLE_SERVICE_ACCOUNT: JSON.stringify({ client_email: 'a@b.c', private_key: pem(google.privateKey) }),
    SIGNING_KEY: der(signing.privateKey),
    RTDN_TOKEN: 'secret',
    PURCHASES: { get: async (k) => (kv.has(k) ? JSON.parse(kv.get(k)) : null), put: async (k, v) => void kv.set(k, v) },
    kv,
  }
}

const play = (sub, status = 200, calls = { n: 0 }) => async (url) => {
  if (String(url).includes('oauth2')) return new Response(JSON.stringify({ access_token: 't' }))
  calls.n++
  return new Response(JSON.stringify(sub), { status })
}

const verify = (e, fetcher, over = {}) =>
  worker.fetch(new Request('https://x/v1/verify', { method: 'POST', body: JSON.stringify({ purchaseToken: 'tok', productId: IDS[0], installId: 'i1', packageName: 'app.aurora', ...over }) }), e, null, fetcher)

const active = { subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE', lineItems: [{ productId: IDS[0], expiryTime: FUTURE, autoRenewingPlan: { autoRenewEnabled: true } }] }

test('a verified active purchase is signed as Plus for this install', async () => {
  const res = await verify(env(), play(active))
  const { payload, signature } = await res.json()
  const body = JSON.parse(payload)
  assert.equal(body.tier, 'plus')
  assert.equal(body.installId, 'i1')
  assert.equal(body.autoRenewing, true)
  assert.ok(createVerify('RSA-SHA256').update(payload).verify(signing.publicKey, signature, 'base64'))
})

test('states that grant Plus, and those that do not', () => {
  const now = Date.now()
  const of = (state, expiry = FUTURE, productId = IDS[0]) => entitlementFrom({ subscriptionState: `SUBSCRIPTION_STATE_${state}`, lineItems: [{ productId, expiryTime: expiry }] }, IDS, now)
  assert.equal(of('IN_GRACE_PERIOD').tier, 'plus')
  // Cancelled: access continues through the paid-through date, then ends.
  assert.equal(of('CANCELED').tier, 'plus')
  assert.equal(of('CANCELED').autoRenewing, false)
  assert.equal(of('CANCELED', PAST).tier, 'free')
  for (const s of ['ON_HOLD', 'PAUSED', 'EXPIRED', 'PENDING', 'PENDING_PURCHASE_CANCELED', 'REVOKED']) assert.equal(of(s).tier, 'free', s)
  // Somebody else's product is not Plus, whatever its state.
  assert.equal(of('ACTIVE', FUTURE, 'other_app_product').tier, 'free')
  assert.equal(entitlementFrom(null, IDS, now).tier, 'free')
})

test('a token Google does not know is free', async () => {
  const { payload } = await (await verify(env(), play({}, 404))).json()
  assert.equal(JSON.parse(payload).tier, 'free')
})

test('Google unreachable answers 503 and signs nothing', async () => {
  assert.equal((await verify(env(), play({}, 500))).status, 503)
})

test('a wrong package is refused', async () => {
  assert.equal((await verify(env(), play(active), { packageName: 'evil.app' })).status, 400)
})

test('a token shared past five installs stops unlocking new ones, not old ones', async () => {
  const e = env()
  for (let i = 1; i <= 5; i++) assert.equal(JSON.parse((await (await verify(e, play(active), { installId: `i${i}` })).json()).payload).tier, 'plus')
  assert.equal(JSON.parse((await (await verify(e, play(active), { installId: 'i6' })).json()).payload).state, 'too_many_devices')
  assert.equal(JSON.parse((await (await verify(e, play(active), { installId: 'i1' })).json()).payload).tier, 'plus')
})

const note = (eventTimeMillis = 1000) => ({ message: { data: btoa(JSON.stringify({ packageName: 'app.aurora', eventTimeMillis, subscriptionNotification: { purchaseToken: 'tok', notificationType: 12 } })) } })
const rtdn = (e, fetcher, body, token = 'secret') => worker.fetch(new Request(`https://x/v1/rtdn?token=${token}`, { method: 'POST', body: JSON.stringify(body) }), e, null, fetcher)

test('RTDN without the token is refused', async () => {
  assert.equal((await rtdn(env(), play(active), note(), 'nope')).status, 403)
})

test('duplicate and delayed RTDN are harmless', async () => {
  const e = env()
  const calls = { n: 0 }
  const revoked = { subscriptionState: 'SUBSCRIPTION_STATE_REVOKED', lineItems: [{ productId: IDS[0], expiryTime: FUTURE }] }
  assert.equal((await rtdn(e, play(revoked, 200, calls), note(2000))).status, 204)
  assert.equal((await rtdn(e, play(revoked, 200, calls), note(2000))).status, 204)
  const stored = [...e.kv.values()].map(JSON.parse)[0]
  assert.equal(stored.state, 'revoked')
  assert.equal(calls.n, 2)
  // Older than what we hold: Google isn't even asked.
  assert.equal((await rtdn(e, play(active, 200, calls), note(1000))).status, 204)
  assert.equal(calls.n, 2)
  assert.equal([...e.kv.values()].map(JSON.parse)[0].state, 'revoked')
})

test('RTDN that Google cannot answer asks Pub/Sub to redeliver', async () => {
  assert.equal((await rtdn(env(), play({}, 500), note())).status, 503)
})
