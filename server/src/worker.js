// Aurora's purchase verification service: a Cloudflare Worker with no dependencies (docs/BILLING.md).
//
// POST /v1/verify   The app sends a purchase token. We ask Google Play, never the app, what it is worth, and
//                   return a snapshot signed with our private key. Only that signature unlocks Plus.
// POST /v1/rtdn     Google Play Real-time Developer Notifications, via a Pub/Sub push subscription. Duplicate,
//                   delayed or out-of-order messages are harmless: each one only makes us ask Google again.
//
// Environment: PACKAGE_NAME, PRODUCT_IDS (comma-separated), GOOGLE_SERVICE_ACCOUNT (JSON), SIGNING_KEY (Base64
// PKCS#8 RSA), RTDN_TOKEN, and the KV namespace PURCHASES.

const PLAY = 'https://androidpublisher.googleapis.com/androidpublisher/v3'
const TOKEN_URL = 'https://oauth2.googleapis.com/token'
const MAX_INSTALLS = 5
// Longer than any retry window a client needs; RTDN and each verify refresh it.
const KV_TTL_SECONDS = 400 * 24 * 60 * 60

const b64 = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes)))
const b64url = (bytes) => b64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
const unb64 = (text) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0))
const utf8 = (text) => new TextEncoder().encode(text)
const json = (status, body) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

const rsa = (usage) => ({ name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256', usages: [usage] })

async function sign(pkcs8, text) {
  const key = await crypto.subtle.importKey('pkcs8', pkcs8, rsa('sign'), false, ['sign'])
  return crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, utf8(text))
}

async function googleAccessToken(env, fetcher) {
  const account = JSON.parse(env.GOOGLE_SERVICE_ACCOUNT)
  const now = Math.floor(Date.now() / 1000)
  const claims = { iss: account.client_email, scope: 'https://www.googleapis.com/auth/androidpublisher', aud: TOKEN_URL, iat: now, exp: now + 3600 }
  const unsigned = `${b64url(utf8(JSON.stringify({ alg: 'RS256', typ: 'JWT' })))}.${b64url(utf8(JSON.stringify(claims)))}`
  const pkcs8 = unb64(account.private_key.replace(/-----[A-Z ]+-----|\s/g, ''))
  const jwt = `${unsigned}.${b64url(await sign(pkcs8, unsigned))}`
  const res = await fetcher(TOKEN_URL, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: jwt }),
  })
  if (!res.ok) throw new Error(`token ${res.status}`)
  return (await res.json()).access_token
}

async function playSubscription(env, token, fetcher) {
  const access = await googleAccessToken(env, fetcher)
  const url = `${PLAY}/applications/${env.PACKAGE_NAME}/purchases/subscriptionsv2/tokens/${encodeURIComponent(token)}`
  const res = await fetcher(url, { headers: { authorization: `Bearer ${access}` } })
  if (res.status === 404 || res.status === 410) return null // Google doesn't know it, or it expired long ago.
  if (!res.ok) throw new Error(`play ${res.status}`)
  return res.json()
}

// The rule that decides Plus, from Google's own answer only.
const ENTITLED = new Set(['SUBSCRIPTION_STATE_ACTIVE', 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD', 'SUBSCRIPTION_STATE_CANCELED'])

export function entitlementFrom(sub, productIds, now) {
  const items = (sub?.lineItems ?? []).filter((l) => productIds.includes(l.productId))
  const line = items.sort((a, b) => Date.parse(b.expiryTime) - Date.parse(a.expiryTime))[0]
  const paidThrough = line ? Date.parse(line.expiryTime) : 0
  const state = sub?.subscriptionState ?? 'SUBSCRIPTION_STATE_UNSPECIFIED'
  const plus = Boolean(line) && ENTITLED.has(state) && paidThrough > now
  return {
    tier: plus ? 'plus' : 'free',
    state: state.replace('SUBSCRIPTION_STATE_', '').toLowerCase(),
    productId: line?.productId ?? null,
    paidThrough,
    autoRenewing: plus && state !== 'SUBSCRIPTION_STATE_CANCELED' && Boolean(line.autoRenewingPlan?.autoRenewEnabled),
  }
}

const tokenKey = async (token) => b64url(await crypto.subtle.digest('SHA-256', utf8(token)))

async function record(env) {
  return { get: (k) => env.PURCHASES.get(k, 'json'), put: (k, v) => env.PURCHASES.put(k, JSON.stringify(v), { expirationTtl: KV_TTL_SECONDS }) }
}

async function verify(request, env, fetcher) {
  let body
  try {
    body = await request.json()
  } catch {
    return json(400, { error: 'bad json' })
  }
  const { purchaseToken, productId, installId, packageName } = body ?? {}
  if (![purchaseToken, productId, installId, packageName].every((v) => typeof v === 'string' && v.length > 0 && v.length < 4096)) return json(400, { error: 'bad request' })
  if (packageName !== env.PACKAGE_NAME) return json(400, { error: 'wrong package' })

  const productIds = env.PRODUCT_IDS.split(',').map((s) => s.trim())
  const store = await record(env)
  const key = await tokenKey(purchaseToken)
  const known = (await store.get(key)) ?? { installs: [] }

  let sub
  try {
    sub = await playSubscription(env, purchaseToken, fetcher)
  } catch {
    // Google is unreachable. The app keeps what it has and tries again. We never guess.
    return json(503, { error: 'try again' })
  }

  const now = Date.now()
  const entitlement = entitlementFrom(sub, productIds, now)
  // One subscription, a handful of a person's phones: a leaked token can't unlock the world.
  const installs = known.installs.includes(installId) ? known.installs : [...known.installs, installId]
  if (entitlement.tier === 'plus' && installs.length > MAX_INSTALLS) {
    return json(200, await signed(env, { ...entitlement, tier: 'free', state: 'too_many_devices', autoRenewing: false }, installId, now))
  }
  await store.put(key, { installs, state: entitlement.state, checkedAt: now })
  return json(200, await signed(env, entitlement, installId, now))
}

async function signed(env, entitlement, installId, now) {
  const payload = JSON.stringify({ ...entitlement, issuedAt: now, installId, packageName: env.PACKAGE_NAME })
  return { payload, signature: b64(await sign(unb64(env.SIGNING_KEY), payload)) }
}

async function rtdn(request, env, fetcher) {
  // Pub/Sub push is configured with this token in its URL. Anything else is turned away.
  if (new URL(request.url).searchParams.get('token') !== env.RTDN_TOKEN) return json(403, { error: 'forbidden' })
  const message = (await request.json().catch(() => null))?.message?.data
  if (!message) return json(204, {})
  const note = JSON.parse(atob(message))
  const token = note.subscriptionNotification?.purchaseToken
  // Test and one-time notifications: acknowledged, nothing to do.
  if (!token || note.packageName !== env.PACKAGE_NAME) return new Response(null, { status: 204 })

  const productIds = env.PRODUCT_IDS.split(',').map((s) => s.trim())
  const store = await record(env)
  const key = await tokenKey(token)
  const known = (await store.get(key)) ?? { installs: [] }
  // A message older than what we hold says nothing new. Only asking Google decides anything.
  const at = Number(note.eventTimeMillis ?? 0)
  if (known.eventAt && at < known.eventAt) return new Response(null, { status: 204 })
  let sub
  try {
    sub = await playSubscription(env, token, fetcher)
  } catch {
    return json(503, { error: 'try again' }) // Pub/Sub redelivers.
  }
  const entitlement = entitlementFrom(sub, productIds, Date.now())
  await store.put(key, { ...known, state: entitlement.state, checkedAt: Date.now(), eventAt: at })
  return new Response(null, { status: 204 })
}

export default {
  async fetch(request, env, _ctx, fetcher = fetch) {
    const path = new URL(request.url).pathname
    if (request.method !== 'POST') return json(405, { error: 'POST only' })
    if (path === '/v1/verify') return verify(request, env, fetcher)
    if (path === '/v1/rtdn') return rtdn(request, env, fetcher)
    return json(404, { error: 'not found' })
  },
}
