// Aurora's account service: a Cloudflare Worker with D1 and no dependencies (docs/ACCOUNT_SYSTEM.md, docs/BILLING.md).
//
// Every /v1 route but the two store notifications needs a verified Firebase ID token, and every query is scoped to that
// token's account. What the app stores for offline use (Plus, a commitment's authorization) is signed with our private key.
//
// Environment: PACKAGE_NAME, PRODUCT_IDS (Google Play), APPLE_BUNDLE_ID, APPLE_PRODUCT_IDS, APPLE_ALLOW_SANDBOX (App Store),
// FIREBASE_PROJECT_ID; secrets GOOGLE_SERVICE_ACCOUNT, FIREBASE_SERVICE_ACCOUNT, SIGNING_KEY, RTDN_TOKEN, APPLE_ISSUER_ID,
// APPLE_KEY_ID, APPLE_PRIVATE_KEY; the D1 database DB.

import { appAccountToken, appleEntitlement, appleStatus, environmentAllowed, verifySigned } from './apple.js'
import { AuthError, bearer, verifyIdToken } from './auth.js'
import { fits, quotaZone, statusOf, validZone } from './quota.js'

const PLAY = 'https://androidpublisher.googleapis.com/androidpublisher/v3'
const TOKEN_URL = 'https://oauth2.googleapis.com/token'
const IDENTITY = 'https://identitytoolkit.googleapis.com/v1/projects'
const PLUS_SEATS = 5
const FRESH_AUTH_S = 10 * 60
const DELETION_GRACE_MS = 7 * 86_400_000
const TRANSFER_COOLDOWN_MS = 30 * 86_400_000
const LOOK_AHEAD_MS = 12 * 3_600_000
const LEDGER_LOOKBACK_MS = 3 * 86_400_000
const LEDGER_RETENTION_MS = 35 * 86_400_000
const COMMIT_ATTEMPTS = 6

const b64 = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes)))
const b64url = (bytes) => b64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
const unb64 = (text) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0))
const utf8 = (text) => new TextEncoder().encode(text)
const json = (status, body) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
const hex = (bytes) => [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, '0')).join('')
const text = (v, max = 256) => typeof v === 'string' && v.length > 0 && v.length <= max

const rsa = (usage) => ({ name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256', usages: [usage] })

async function sign(pkcs8, value) {
  const key = await crypto.subtle.importKey('pkcs8', pkcs8, rsa('sign'), false, ['sign'])
  return crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, utf8(value))
}

async function signed(env, body) {
  const payload = JSON.stringify(body)
  return { payload, signature: b64(await sign(unb64(env.SIGNING_KEY), payload)) }
}

async function googleAccessToken(serviceAccount, scope, fetcher) {
  const account = JSON.parse(serviceAccount)
  const now = Math.floor(Date.now() / 1000)
  const claims = { iss: account.client_email, scope, aud: TOKEN_URL, iat: now, exp: now + 3600 }
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

// ---- Google Play ----------------------------------------------------------------------------------------

async function playSubscription(env, token, fetcher) {
  const access = await googleAccessToken(env.GOOGLE_SERVICE_ACCOUNT, 'https://www.googleapis.com/auth/androidpublisher', fetcher)
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
    known: Boolean(line),
  }
}

const sha256 = async (value) => crypto.subtle.digest('SHA-256', utf8(value))
const tokenKey = async (token) => b64url(await sha256(token))
const productIdsOf = (env) => env.PRODUCT_IDS.split(',').map((s) => s.trim())

// ---- Identity ---------------------------------------------------------------------------------------------

/** Whether Firebase still honours this sign-in: the user exists, isn't disabled, and hasn't been revoked since. */
async function assertNotRevoked(claims, env, fetcher) {
  if (env.FIREBASE_AUTH_EMULATOR === '1' && env.ENVIRONMENT !== 'production') return
  if (!env.FIREBASE_SERVICE_ACCOUNT) throw new AuthError(503, 'try_again')
  let user
  try {
    const access = await googleAccessToken(env.FIREBASE_SERVICE_ACCOUNT, 'https://www.googleapis.com/auth/identitytoolkit', fetcher)
    const res = await fetcher(`${IDENTITY}/${env.FIREBASE_PROJECT_ID}/accounts:lookup`, {
      method: 'POST',
      headers: { authorization: `Bearer ${access}`, 'content-type': 'application/json' },
      body: JSON.stringify({ localId: [claims.sub] }),
    })
    if (!res.ok) throw new Error(`lookup ${res.status}`)
    user = (await res.json()).users?.[0]
  } catch {
    throw new AuthError(503, 'try_again')
  }
  if (!user || user.disabled || claims.auth_time < Number(user.validSince ?? 0)) throw new AuthError(401, 'revoked')
}

async function deleteFirebaseUser(uid, env, fetcher) {
  const access = await googleAccessToken(env.FIREBASE_SERVICE_ACCOUNT, 'https://www.googleapis.com/auth/identitytoolkit', fetcher)
  const res = await fetcher(`${IDENTITY}/${env.FIREBASE_PROJECT_ID}/accounts:delete`, {
    method: 'POST',
    headers: { authorization: `Bearer ${access}`, 'content-type': 'application/json' },
    body: JSON.stringify({ localId: uid }),
  })
  // Already gone counts as done.
  if (!res.ok && !(res.status === 400 && (await res.text()).includes('USER_NOT_FOUND'))) throw new Error(`delete ${res.status}`)
}

/** The verified caller, or throws. [fresh] needs a recent sign-in; [revocation] also asks Firebase. */
async function authenticate(request, env, fetcher, { fresh = false, revocation = false } = {}) {
  const claims = await verifyIdToken(bearer(request), env, fetcher)
  // Without this anyone could call Firebase's public sign-up API with someone else's address.
  if (claims.email_verified !== true || !claims.email) throw new AuthError(403, 'email_unverified')
  if (fresh && Math.floor(Date.now() / 1000) - claims.auth_time > FRESH_AUTH_S) throw new AuthError(401, 'reauth_required')
  if (revocation) await assertNotRevoked(claims, env, fetcher)
  return claims
}

/** Creates the account on first sight of a verified identity, and keeps its profile current. Atomic on the UID. */
async function ensureAccount(env, claims, now) {
  return env.DB.prepare(
    `INSERT INTO accounts (id, firebase_uid, email, display_name, photo_url, created_at, last_auth_at)
     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
     ON CONFLICT (firebase_uid) DO UPDATE SET email = ?3, display_name = ?4, photo_url = ?5, last_auth_at = ?7
     RETURNING *`,
  ).bind(crypto.randomUUID(), claims.sub, claims.email, (claims.name ?? null), (claims.picture ?? null), now, claims.auth_time * 1000).first()
}

/** Null when this installation was removed from the account after this sign-in began: it has to sign in again. */
async function ensureInstallation(env, account, claims, installId, label, now) {
  const row = await env.DB.prepare('SELECT * FROM installations WHERE account_id = ? AND id = ?').bind(account.id, installId).first()
  if (row?.revoked_at && claims.auth_time * 1000 < row.revoked_at) return null
  await env.DB.prepare(
    `INSERT INTO installations (id, account_id, label, registered_at, last_seen_at) VALUES (?1, ?2, ?3, ?4, ?4)
     ON CONFLICT (account_id, id) DO UPDATE SET label = COALESCE(?3, label), last_seen_at = ?4, revoked_at = NULL,
       registered_at = CASE WHEN revoked_at IS NULL THEN registered_at ELSE ?4 END`,
  ).bind(installId, account.id, text(label, 80) ? label : null, now).run()
  return true
}

/** What the account's verified purchases grant this installation, ready to store. */
async function accountSnapshot(env, account, uid, installId, now, extra = {}) {
  const { results: rows } = await env.DB.prepare('SELECT * FROM purchases WHERE account_id = ? ORDER BY paid_through DESC').bind(account.id).all()
  const best = rows.find((r) => r.tier === 'plus' && r.paid_through > now)
  const { results: seats } = await env.DB.prepare('SELECT id FROM installations WHERE account_id = ? AND revoked_at IS NULL ORDER BY registered_at, id LIMIT ?').bind(account.id, PLUS_SEATS).all()
  const seated = seats.some((s) => s.id === installId)
  const shown = best ?? rows[0]
  const tier = best && seated ? 'plus' : 'free'
  return signed(env, {
    tier,
    state: best && !seated ? 'too_many_devices' : (shown?.state ?? 'none'),
    productId: shown?.product_id ?? null,
    store: shown?.store ?? null,
    paidThrough: shown?.paid_through ?? 0,
    autoRenewing: tier === 'plus' && Boolean(best.auto_renewing),
    issuedAt: now,
    installId,
    uid,
    packageName: env.PACKAGE_NAME,
    ...extra,
  })
}

const devicesOf = async (env, account, installId) => {
  const { results } = await env.DB.prepare('SELECT id, label, registered_at, last_seen_at FROM installations WHERE account_id = ? AND revoked_at IS NULL ORDER BY last_seen_at DESC').bind(account.id).all()
  return results.map((d) => ({ id: d.id, label: d.label, registeredAt: d.registered_at, lastSeenAt: d.last_seen_at, current: d.id === installId }))
}

const hasPlus = async (env, account, now) =>
  Boolean(await env.DB.prepare("SELECT 1 FROM purchases WHERE account_id = ? AND tier = 'plus' AND paid_through > ? LIMIT 1").bind(account.id, now).first())

async function ledgerOf(env, account, now) {
  const { results } = await env.DB.prepare("SELECT * FROM quota_reservations WHERE account_id = ? AND status <> 'released' AND end_at > ?").bind(account.id, now - LEDGER_LOOKBACK_MS).all()
  return results
}

async function body(request) {
  try {
    return (await request.json()) ?? {}
  } catch {
    return {}
  }
}

// ---- Rate limits -----------------------------------------------------------------------------------------

async function allowed(env, bucket, max, windowSeconds) {
  const now = Date.now()
  const window = Math.floor(now / (windowSeconds * 1000))
  const row = await env.DB.prepare(
    `INSERT INTO rate_limits (bucket, count, expires_at) VALUES (?1, 1, ?2)
     ON CONFLICT (bucket) DO UPDATE SET count = count + 1 RETURNING count`,
  ).bind(`${bucket}:${window}`, (window + 1) * windowSeconds * 1000).first()
  return row.count <= max
}

// ---- Routes ------------------------------------------------------------------------------------------------

async function sync(request, env, fetcher) {
  const claims = await authenticate(request, env, fetcher, { revocation: true })
  const { installId, label, zone } = await body(request)
  if (!text(installId, 64)) return json(400, { error: 'bad_request' })
  if (!(await allowed(env, `sync:${claims.sub}`, 60, 60))) return json(429, { error: 'slow_down' })
  const now = Date.now()
  const account = await ensureAccount(env, claims, now)
  if (!(await ensureInstallation(env, account, claims, installId, label, now))) return json(401, { error: 'installation_removed' })
  const plus = await hasPlus(env, account, now)
  const ledger = plus ? [] : await ledgerOf(env, account, now)
  const asked = validZone(zone) ? zone : 'UTC'
  return json(200, {
    account: { id: account.id, email: account.email, displayName: account.display_name, deletionRequestedAt: account.deletion_requested_at },
    entitlement: await accountSnapshot(env, account, claims.sub, installId, now),
    quota: plus ? null : statusOf(ledger, now, quotaZone(ledger, now, asked)),
    devices: await devicesOf(env, account, installId),
    serverTime: now,
  })
}

function maskEmail(email) {
  const [name, domain] = String(email ?? '').split('@')
  return name && domain ? `${name[0]}•••@${domain}` : null
}

/** A purchase is the account's if nobody else holds it, the plan it replaces, or the id it was bought under. */
async function ownerConflict(env, account, uid, sub, key) {
  const row = await env.DB.prepare('SELECT account_id FROM purchases WHERE token_hash = ?').bind(key).first()
  if (row?.account_id) return row.account_id === account.id ? null : row.account_id
  if (sub?.linkedPurchaseToken) {
    const linked = await env.DB.prepare('SELECT account_id FROM purchases WHERE token_hash = ?').bind(await tokenKey(sub.linkedPurchaseToken)).first()
    if (linked?.account_id && linked.account_id !== account.id) return linked.account_id
  }
  const external = sub?.externalAccountIdentifiers?.obfuscatedExternalAccountId
  if (external && external !== hex(await sha256(uid))) return 'unknown'
  return null
}

async function storePurchase(env, account, key, entitlement, now, event = 0) {
  const { meta } = await env.DB.prepare(
    `INSERT INTO purchases (token_hash, account_id, product_id, tier, state, paid_through, auto_renewing, checked_at, event_at, bound_at)
     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?8)
     ON CONFLICT (token_hash) DO UPDATE SET product_id = ?3, tier = ?4, state = ?5, paid_through = ?6, auto_renewing = ?7, checked_at = ?8,
       account_id = COALESCE(purchases.account_id, ?2), event_at = MAX(purchases.event_at, ?9)
     WHERE purchases.account_id IS NULL OR purchases.account_id = ?2`,
  ).bind(key, account.id, entitlement.productId, entitlement.tier, entitlement.state, entitlement.paidThrough, entitlement.autoRenewing ? 1 : 0, now, event).run()
  return meta.changes > 0
}

async function verify(request, env, fetcher) {
  const claims = await authenticate(request, env, fetcher, { revocation: true })
  const { purchaseToken, productId, installId, packageName } = await body(request)
  if (![productId, installId, packageName].every((v) => text(v)) || !text(purchaseToken, 4096)) return json(400, { error: 'bad_request' })
  if (packageName !== env.PACKAGE_NAME) return json(400, { error: 'wrong_package' })
  if (!(await allowed(env, `verify:${claims.sub}`, 30, 60))) return json(429, { error: 'slow_down' })
  const now = Date.now()
  const account = await ensureAccount(env, claims, now)
  if (!(await ensureInstallation(env, account, claims, installId, null, now))) return json(401, { error: 'installation_removed' })

  let sub
  try {
    sub = await playSubscription(env, purchaseToken, fetcher)
  } catch {
    // Google is unreachable. The app keeps what it has and tries again. We never guess.
    return json(503, { error: 'try_again' })
  }
  const entitlement = entitlementFrom(sub, productIdsOf(env), now)
  const key = await tokenKey(purchaseToken)
  if (!entitlement.known) return json(200, await accountSnapshot(env, account, claims.sub, installId, now, { purchaseValid: false }))

  const owner = await ownerConflict(env, account, claims.sub, sub, key)
  if (owner !== null || !(await storePurchase(env, account, key, entitlement, now))) {
    const holder = owner && owner !== 'unknown' ? await env.DB.prepare('SELECT email FROM accounts WHERE id = ?').bind(owner).first() : null
    return json(200, { ...(await accountSnapshot(env, account, claims.sub, installId, now, { purchaseValid: true })), conflict: true, conflictHint: maskEmail(holder?.email) })
  }
  return json(200, await accountSnapshot(env, account, claims.sub, installId, now, { purchaseValid: true }))
}

/** The person moves a purchase they hold on this phone to this account: a recent sign-in, Google's word, and rarely. */
async function transfer(request, env, fetcher) {
  const claims = await authenticate(request, env, fetcher, { fresh: true, revocation: true })
  const { purchaseToken, installId } = await body(request)
  if (!text(purchaseToken, 4096) || !text(installId, 64)) return json(400, { error: 'bad_request' })
  if (!(await allowed(env, `transfer:${claims.sub}`, 5, 3600))) return json(429, { error: 'slow_down' })
  const now = Date.now()
  const account = await ensureAccount(env, claims, now)
  if (!(await ensureInstallation(env, account, claims, installId, null, now))) return json(401, { error: 'installation_removed' })
  let sub
  try {
    sub = await playSubscription(env, purchaseToken, fetcher)
  } catch {
    return json(503, { error: 'try_again' })
  }
  const entitlement = entitlementFrom(sub, productIdsOf(env), now)
  if (!entitlement.known) return json(404, { error: 'not_found' })
  const key = await tokenKey(purchaseToken)
  const row = await env.DB.prepare('SELECT transferred_at FROM purchases WHERE token_hash = ?').bind(key).first()
  if (row?.transferred_at && now - row.transferred_at < TRANSFER_COOLDOWN_MS) return json(429, { error: 'transfer_limit', retryAt: row.transferred_at + TRANSFER_COOLDOWN_MS })
  await env.DB.prepare(
    `INSERT INTO purchases (token_hash, account_id, product_id, tier, state, paid_through, auto_renewing, checked_at, bound_at, transferred_at)
     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?8, ?8)
     ON CONFLICT (token_hash) DO UPDATE SET account_id = ?2, product_id = ?3, tier = ?4, state = ?5, paid_through = ?6, auto_renewing = ?7, checked_at = ?8, transferred_at = ?8`,
  ).bind(key, account.id, entitlement.productId, entitlement.tier, entitlement.state, entitlement.paidThrough, entitlement.autoRenewing ? 1 : 0, now).run()
  return json(200, await accountSnapshot(env, account, claims.sub, installId, now, { purchaseValid: true }))
}

// ---- App Store --------------------------------------------------------------------------------------------------
//
// The route decides the platform, never the request. Ownership, in order: a purchase already bound stays with its
// account; an unbound one binds only to the account its Apple-signed appAccountToken names; anything else takes the
// explicit move below. A notification or a re-check can change a purchase's state, and never its owner.

const APPLE_SETTINGS = ['APPLE_BUNDLE_ID', 'APPLE_PRODUCT_IDS', 'APPLE_ISSUER_ID', 'APPLE_KEY_ID', 'APPLE_PRIVATE_KEY']
const appleReady = (env) => APPLE_SETTINGS.every((name) => env[name])
const appleConfig = (env) => ({ bundleId: env.APPLE_BUNDLE_ID, productIds: env.APPLE_PRODUCT_IDS.split(',').map((s) => s.trim()) })
const appleKey = (id) => tokenKey(`apple:${id}`)
const APPLE_LAPSE_RECHECK_MS = 6 * 3_600_000

async function appleAsk(env, originalTransactionId, fetcher) {
  return appleEntitlement(await appleStatus(env, originalTransactionId, fetcher), originalTransactionId, appleConfig(env))
}

/** Apple's current answer about the transaction this app holds, or the response that refuses it. */
async function appleAnswer(env, signedTransaction, fetcher) {
  let claimed
  try {
    claimed = await verifySigned(signedTransaction)
  } catch {
    return { refused: json(400, { error: 'invalid_transaction' }) }
  }
  if (claimed.bundleId !== env.APPLE_BUNDLE_ID) return { refused: json(400, { error: 'wrong_package' }) }
  if (!environmentAllowed(env, claimed.environment)) return { refused: json(400, { error: 'wrong_environment' }) }
  const id = claimed.originalTransactionId
  if (!text(id, 64)) return { refused: json(400, { error: 'invalid_transaction' }) }
  const checkedAt = Date.now()
  try {
    const entitlement = await appleAsk(env, id, fetcher)
    return { id, checkedAt, entitlement: entitlement.environment === claimed.environment ? entitlement : { known: false } }
  } catch {
    return { refused: json(503, { error: 'try_again' }) } // Apple is unreachable, or said something unverifiable. We never guess.
  }
}

/** Atomic on the purchase: simultaneous claims bind it once. An answer older than the stored one changes nothing. */
async function storeApple(env, account, key, { id, checkedAt, entitlement: e }) {
  const { meta } = await env.DB.prepare(
    `INSERT INTO purchases (token_hash, account_id, product_id, tier, state, paid_through, auto_renewing, checked_at, event_at, bound_at, store, store_ref, environment)
     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, 0, ?8, 'app_store', ?9, ?10)
     ON CONFLICT (token_hash) DO UPDATE SET product_id = ?3, tier = ?4, state = ?5, paid_through = ?6, auto_renewing = ?7, checked_at = ?8,
       account_id = COALESCE(purchases.account_id, ?2), store_ref = ?9, environment = ?10
     WHERE (purchases.account_id IS NULL OR purchases.account_id = ?2) AND purchases.checked_at <= ?8`,
  ).bind(key, account.id, e.productId, e.tier, e.state, e.paidThrough, e.autoRenewing ? 1 : 0, checkedAt, id, e.environment).run()
  return meta.changes > 0
}

async function verifyApple(request, env, fetcher) {
  const claims = await authenticate(request, env, fetcher, { revocation: true })
  const { signedTransaction, installId, bundleId } = await body(request)
  if (!text(installId, 64) || !text(bundleId) || !text(signedTransaction, 16384)) return json(400, { error: 'bad_request' })
  if (!appleReady(env)) return json(503, { error: 'try_again' })
  if (bundleId !== env.APPLE_BUNDLE_ID) return json(400, { error: 'wrong_package' })
  if (!(await allowed(env, `verify:${claims.sub}`, 30, 60))) return json(429, { error: 'slow_down' })
  const now = Date.now()
  const account = await ensureAccount(env, claims, now)
  if (!(await ensureInstallation(env, account, claims, installId, null, now))) return json(401, { error: 'installation_removed' })

  const answer = await appleAnswer(env, signedTransaction, fetcher)
  if (answer.refused) return answer.refused
  const snapshot = (extra) => accountSnapshot(env, account, claims.sub, installId, now, extra)
  if (!answer.entitlement.known) return json(200, await snapshot({ purchaseValid: false }))

  const key = await appleKey(answer.id)
  const conflict = async (owner, extra = {}) => {
    const holder = owner ? await env.DB.prepare('SELECT email FROM accounts WHERE id = ?').bind(owner).first() : null
    return json(200, { ...(await snapshot({ purchaseValid: true })), conflict: true, conflictHint: maskEmail(holder?.email), ...extra })
  }
  const ownerOf = async () => (await env.DB.prepare('SELECT account_id FROM purchases WHERE token_hash = ?').bind(key).first())?.account_id
  const owner = await ownerOf()
  if (owner && owner !== account.id) return conflict(owner)
  if (!owner) {
    const token = answer.entitlement.appAccountToken
    // No token, or bought by a family member: nothing Apple signed names an account, so the person has to choose.
    if (!token || answer.entitlement.ownership !== 'PURCHASED') return conflict(null, { claimRequired: true })
    if (token !== (await appAccountToken(claims.sub))) return conflict(null)
  }
  if (!(await storeApple(env, account, key, answer))) {
    const holder = await ownerOf() // Lost the race, or a newer answer got there first.
    if (holder !== account.id) return conflict(holder)
  }
  return json(200, await snapshot({ purchaseValid: true }))
}

/**
 * The person moves a subscription this phone's Apple Account holds to this account, or claims one no signed token
 * names: a recent sign-in, Apple's signature on the transaction, Apple's current word, and rarely. A bare id isn't enough.
 */
async function transferApple(request, env, fetcher) {
  const claims = await authenticate(request, env, fetcher, { fresh: true, revocation: true })
  const { signedTransaction, installId } = await body(request)
  if (!text(installId, 64) || !text(signedTransaction, 16384)) return json(400, { error: 'bad_request' })
  if (!appleReady(env)) return json(503, { error: 'try_again' })
  if (!(await allowed(env, `transfer:${claims.sub}`, 5, 3600))) return json(429, { error: 'slow_down' })
  const now = Date.now()
  const account = await ensureAccount(env, claims, now)
  if (!(await ensureInstallation(env, account, claims, installId, null, now))) return json(401, { error: 'installation_removed' })
  const answer = await appleAnswer(env, signedTransaction, fetcher)
  if (answer.refused) return answer.refused
  const { id, checkedAt, entitlement: e } = answer
  if (!e.known) return json(404, { error: 'not_found' })
  const key = await appleKey(id)
  const row = await env.DB.prepare('SELECT account_id FROM purchases WHERE token_hash = ?').bind(key).first()
  if (row?.account_id === account.id) {
    await storeApple(env, account, key, answer) // Already here: moving nothing shouldn't spend the cooldown.
  } else {
    // The cooldown is part of the write, so two racing moves can't both pass it.
    const { meta } = await env.DB.prepare(
      `INSERT INTO purchases (token_hash, account_id, product_id, tier, state, paid_through, auto_renewing, checked_at, event_at, bound_at, transferred_at, store, store_ref, environment)
       VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, 0, ?8, ?8, 'app_store', ?9, ?10)
       ON CONFLICT (token_hash) DO UPDATE SET account_id = ?2, product_id = ?3, tier = ?4, state = ?5, paid_through = ?6, auto_renewing = ?7,
         checked_at = ?8, transferred_at = ?8, store_ref = ?9, environment = ?10
       WHERE purchases.transferred_at IS NULL OR purchases.transferred_at <= ?11`,
    ).bind(key, account.id, e.productId, e.tier, e.state, e.paidThrough, e.autoRenewing ? 1 : 0, checkedAt, id, e.environment, checkedAt - TRANSFER_COOLDOWN_MS).run()
    if (meta.changes === 0) {
      const { transferred_at: at } = await env.DB.prepare('SELECT transferred_at FROM purchases WHERE token_hash = ?').bind(key).first()
      return json(429, { error: 'transfer_limit', retryAt: at + TRANSFER_COOLDOWN_MS })
    }
  }
  return json(200, await accountSnapshot(env, account, claims.sub, installId, now, { purchaseValid: true }))
}

/** Writes Apple's current state over a purchase we already hold, and nothing about who owns it. */
async function refreshApple(env, key, e, checkedAt, eventAt = 0) {
  await env.DB.prepare(
    `UPDATE purchases SET tier = ?1, state = ?2, paid_through = ?3, auto_renewing = ?4, product_id = COALESCE(?5, product_id), environment = COALESCE(?6, environment),
       checked_at = ?7, event_at = MAX(event_at, ?8)
     WHERE token_hash = ?9 AND checked_at <= ?7`,
  ).bind(e.known ? e.tier : 'free', e.known ? e.state : 'unknown', e.known ? e.paidThrough : 0, e.autoRenewing ? 1 : 0, e.productId ?? null, e.environment ?? null, checkedAt, eventAt, key).run()
}

/**
 * App Store Server Notifications V2. Anyone can reach this URL, so a notification only ever says "ask Apple again", and
 * only once Apple's signature on it checks out. Its own fields never set a state, so order and replays can't either.
 * 2xx acknowledges. Anything else, Apple sends again (after 1, 12, 24, 48 and 72 hours).
 */
async function appleNotification(request, env, fetcher) {
  if (!appleReady(env)) return json(503, { error: 'try_again' })
  const raw = await request.text()
  if (raw.length > 65_536) return json(413, { error: 'too_large' })
  const ack = () => new Response(null, { status: 200 })
  let note, tx
  try {
    note = await verifySigned(JSON.parse(raw)?.signedPayload)
    if (note.data?.signedTransactionInfo) tx = await verifySigned(note.data.signedTransactionInfo)
  } catch {
    return json(403, { error: 'forbidden' })
  }
  // Test notifications and summaries name no transaction. Another app's or a disabled environment's are not ours to act on.
  const data = note.data
  if (!tx || data.bundleId !== env.APPLE_BUNDLE_ID || tx.bundleId !== env.APPLE_BUNDLE_ID) return ack()
  if (!environmentAllowed(env, data.environment) || tx.environment !== data.environment) return ack()
  if (!text(tx.originalTransactionId, 64)) return ack()
  const key = await appleKey(tx.originalTransactionId)
  const known = await env.DB.prepare('SELECT event_at, store, environment FROM purchases WHERE token_hash = ?').bind(key).first()
  // Nothing bound yet: the app's own verify will bind it. A repeat or a delayed message says nothing new: the state we
  // hold was read from Apple after an event at least this recent.
  if (!known || known.store !== 'app_store' || known.environment !== data.environment || note.signedDate <= known.event_at) return ack()
  const checkedAt = Date.now()
  let entitlement
  try {
    entitlement = await appleAsk(env, tx.originalTransactionId, fetcher)
  } catch {
    return json(503, { error: 'try_again' })
  }
  await refreshApple(env, key, entitlement, checkedAt, note.signedDate)
  return ack()
}

/** Daily: a renewing subscription that passed its date with no word from Apple. An Android phone can't ask StoreKit. */
async function recheckLapsedApple(env, fetcher, now) {
  // ponytail: 20 a day keeps a run under the free plan's 50 subrequests (two fetches each). Raise it with a paid plan.
  const { results } = await env.DB.prepare(
    "SELECT token_hash, store_ref FROM purchases WHERE store = 'app_store' AND auto_renewing = 1 AND paid_through < ? AND checked_at < ? ORDER BY checked_at LIMIT 20",
  ).bind(now, now - APPLE_LAPSE_RECHECK_MS).all()
  for (const row of results) {
    try {
      const checkedAt = Date.now()
      await refreshApple(env, row.token_hash, await appleAsk(env, row.store_ref, fetcher), checkedAt)
    } catch {
      // Tomorrow's run tries again. The clock has already taken Plus away.
    }
  }
}

const MODES = new Set(['cooldown', 'always_block'])

async function reserve(request, env, fetcher) {
  const claims = await authenticate(request, env, fetcher, { revocation: true })
  const input = await body(request)
  const { installId, idempotencyKey, commitmentId, mode, minutes, startAt, zone } = input
  if (![installId, idempotencyKey, commitmentId].every((v) => text(v, 128)) || !MODES.has(mode) || !validZone(zone)) return json(400, { error: 'bad_request' })
  if (!Number.isInteger(minutes) || minutes < 1 || minutes > 1440) return json(400, { error: 'bad_request' })
  if (startAt !== undefined && !Number.isFinite(startAt)) return json(400, { error: 'bad_request' })
  if (!(await allowed(env, `reserve:${claims.sub}`, 30, 60))) return json(429, { error: 'slow_down' })
  const now = Date.now()
  const account = await ensureAccount(env, claims, now)
  if (account.status !== 'active') return json(403, { error: 'account_deleting' })
  if (!(await ensureInstallation(env, account, claims, installId, null, now))) return json(401, { error: 'installation_removed' })
  // The server's clock decides. A schedule may ask ahead of time, within reason, and pays only from when it can start.
  const asked = startAt ?? now
  if (asked > now + LOOK_AHEAD_MS) return json(400, { error: 'too_early' })
  const start = Math.max(asked, now)
  const end = asked + minutes * 60_000
  if (end <= now) return json(400, { error: 'in_the_past' })
  const authorization = (reservationId, s, e, tier, status) => signed(env, { type: 'reservation', reservationId, uid: claims.sub, installId, commitmentId, mode, start: s, end: e, issuedAt: now, tier, packageName: env.PACKAGE_NAME, ...status })

  if (await hasPlus(env, account, now)) return json(200, { allowed: true, tier: 'plus', authorization: await authorization(null, start, end, 'plus', {}) })

  for (let attempt = 0; attempt < COMMIT_ATTEMPTS; attempt++) {
    const existing = await env.DB.prepare('SELECT * FROM quota_reservations WHERE account_id = ? AND idempotency_key = ?').bind(account.id, idempotencyKey).first()
    const { quota_revision: revision } = await env.DB.prepare('SELECT quota_revision FROM accounts WHERE id = ?').bind(account.id).first()
    const ledger = await ledgerOf(env, account, now)
    const day = quotaZone(ledger, now, zone)
    if (existing) {
      return json(200, { allowed: true, tier: 'free', replayed: true, authorization: await authorization(existing.id, existing.start_at, existing.end_at, 'free', statusOf(ledger, now, day)) })
    }
    if (!fits(ledger, installId, start, end, day)) return json(200, { allowed: false, tier: 'free', ...statusOf(ledger, start, day) })
    const id = crypto.randomUUID()
    // One transaction, and only if the ledger is still what was just read: two devices can't both spend the last minutes.
    const [inserted] = await env.DB.batch([
      env.DB.prepare(
        `INSERT OR IGNORE INTO quota_reservations (id, account_id, installation_id, idempotency_key, commitment_id, mode, start_at, end_at, zone, created_at)
         SELECT ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10 FROM accounts WHERE id = ?2 AND quota_revision = ?11`,
      ).bind(id, account.id, installId, idempotencyKey, commitmentId, mode, start, end, day, now, revision),
      env.DB.prepare('UPDATE accounts SET quota_revision = quota_revision + 1 WHERE id = ? AND quota_revision = ?').bind(account.id, revision),
    ])
    if (inserted.meta.changes > 0) {
      const after = [...ledger, { installation_id: installId, start_at: start, end_at: end, zone: day }]
      return json(200, { allowed: true, tier: 'free', authorization: await authorization(id, start, end, 'free', statusOf(after, now, day)) })
    }
  }
  return json(503, { error: 'try_again' })
}

/** Gives back a reservation that never began: a schedule removed in time. Anything started is spent. */
async function release(request, env, fetcher) {
  const claims = await authenticate(request, env, fetcher)
  const { reservationId } = await body(request)
  if (!text(reservationId, 64)) return json(400, { error: 'bad_request' })
  const now = Date.now()
  const account = await ensureAccount(env, claims, now)
  const row = await env.DB.prepare('SELECT start_at, status FROM quota_reservations WHERE id = ? AND account_id = ?').bind(reservationId, account.id).first()
  if (!row) return json(404, { error: 'not_found' })
  if (row.start_at <= now) return json(409, { error: 'already_started' })
  await env.DB.batch([
    env.DB.prepare("UPDATE quota_reservations SET status = 'released' WHERE id = ? AND account_id = ? AND start_at > ?").bind(reservationId, account.id, now),
    env.DB.prepare('UPDATE accounts SET quota_revision = quota_revision + 1 WHERE id = ?').bind(account.id),
  ])
  return json(200, { released: true })
}

/** An existing phone's recent committed time joins the ledger once. It can only add to what the account has used. */
async function importLedger(request, env, fetcher) {
  const claims = await authenticate(request, env, fetcher)
  const { installId, entries } = await body(request)
  if (!text(installId, 64) || !Array.isArray(entries) || entries.length > 200) return json(400, { error: 'bad_request' })
  if (!(await allowed(env, `import:${claims.sub}`, 10, 3600))) return json(429, { error: 'slow_down' })
  const now = Date.now()
  const account = await ensureAccount(env, claims, now)
  const statements = []
  for (const e of entries) {
    if (!Number.isFinite(e?.start) || !Number.isFinite(e?.end) || e.end <= e.start || !validZone(e.zone)) return json(400, { error: 'bad_request' })
    if (e.end <= now - LEDGER_LOOKBACK_MS || e.start > now + 86_400_000) continue
    statements.push(env.DB.prepare(
      `INSERT OR IGNORE INTO quota_reservations (id, account_id, installation_id, idempotency_key, commitment_id, mode, start_at, end_at, zone, status, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'migrated', ?)`,
    ).bind(crypto.randomUUID(), account.id, installId, `import:${installId}:${e.start}`, `import:${e.start}`, e.mode === 'cooldown' ? 'cooldown' : 'always_block', e.start, e.end, e.zone, now))
  }
  if (statements.length) await env.DB.batch([...statements, env.DB.prepare('UPDATE accounts SET quota_revision = quota_revision + 1 WHERE id = ?').bind(account.id)])
  return json(200, { imported: statements.length })
}

async function removeInstallation(request, env, fetcher) {
  const claims = await authenticate(request, env, fetcher, { revocation: true })
  const { installId, currentInstallId } = await body(request)
  if (!text(installId, 64)) return json(400, { error: 'bad_request' })
  const now = Date.now()
  const account = await ensureAccount(env, claims, now)
  const { meta } = await env.DB.prepare('UPDATE installations SET revoked_at = ? WHERE account_id = ? AND id = ? AND revoked_at IS NULL').bind(now, account.id, installId).run()
  if (meta.changes === 0) return json(404, { error: 'not_found' })
  return json(200, { devices: await devicesOf(env, account, currentInstallId) })
}

async function requestDeletion(request, env, fetcher) {
  const claims = await authenticate(request, env, fetcher, { fresh: true, revocation: true })
  const now = Date.now()
  const account = await ensureAccount(env, claims, now)
  await env.DB.prepare("UPDATE accounts SET status = 'deletion_requested', deletion_requested_at = COALESCE(deletion_requested_at, ?) WHERE id = ?").bind(now, account.id).run()
  return json(200, { deletionAt: (account.deletion_requested_at ?? now) + DELETION_GRACE_MS })
}

async function cancelDeletion(request, env, fetcher) {
  const claims = await authenticate(request, env, fetcher, { revocation: true })
  const account = await ensureAccount(env, claims, Date.now())
  await env.DB.prepare("UPDATE accounts SET status = 'active', deletion_requested_at = NULL WHERE id = ?").bind(account.id).run()
  return json(200, { cancelled: true })
}

async function rtdn(request, env, fetcher) {
  // Pub/Sub push is configured with this token in its URL. Anything else is turned away.
  if (new URL(request.url).searchParams.get('token') !== env.RTDN_TOKEN) return json(403, { error: 'forbidden' })
  const message = (await request.json().catch(() => null))?.message?.data
  if (!message) return new Response(null, { status: 204 })
  let note
  try {
    note = JSON.parse(atob(message))
  } catch {
    return new Response(null, { status: 204 })
  }
  const token = note.subscriptionNotification?.purchaseToken
  // Test and one-time notifications: acknowledged, nothing to do.
  if (!token || note.packageName !== env.PACKAGE_NAME) return new Response(null, { status: 204 })
  const key = await tokenKey(token)
  const known = await env.DB.prepare('SELECT * FROM purchases WHERE token_hash = ?').bind(key).first()
  // Nothing bound yet: the app's own verify will bind it. A message older than what we hold says nothing new.
  const at = Number(note.eventTimeMillis ?? 0)
  if (!known || known.store === 'app_store' || at < known.event_at) return new Response(null, { status: 204 })
  let sub
  try {
    sub = await playSubscription(env, token, fetcher)
  } catch {
    return json(503, { error: 'try_again' }) // Pub/Sub redelivers.
  }
  const now = Date.now()
  const e = entitlementFrom(sub, productIdsOf(env), now)
  await env.DB.prepare('UPDATE purchases SET tier = ?, state = ?, paid_through = ?, auto_renewing = ?, product_id = COALESCE(?, product_id), checked_at = ?, event_at = ? WHERE token_hash = ?')
    .bind(e.tier, e.state, e.paidThrough, e.autoRenewing ? 1 : 0, e.productId, now, at, key).run()
  return new Response(null, { status: 204 })
}

/** Daily: finishes deletions past their grace, and drops what no longer serves a purpose. */
export async function housekeeping(env, fetcher, now = Date.now()) {
  const { results } = await env.DB.prepare("SELECT id, firebase_uid FROM accounts WHERE status = 'deletion_requested' AND deletion_requested_at < ? LIMIT 100").bind(now - DELETION_GRACE_MS).all()
  for (const a of results) {
    try {
      await deleteFirebaseUser(a.firebase_uid, env, fetcher)
      // Reservations and installations cascade. A purchase row stays, unowned, so the subscription can bind to a new account.
      await env.DB.prepare('DELETE FROM accounts WHERE id = ?').bind(a.id).run()
    } catch {
      // Tomorrow's run tries again.
    }
  }
  await env.DB.batch([
    env.DB.prepare('DELETE FROM rate_limits WHERE expires_at < ?').bind(now),
    env.DB.prepare('DELETE FROM quota_reservations WHERE end_at < ?').bind(now - LEDGER_RETENTION_MS),
  ])
  if (appleReady(env)) await recheckLapsedApple(env, fetcher, now)
}

const ROUTES = {
  '/v1/account/sync': sync,
  '/v1/account/delete': requestDeletion,
  '/v1/account/delete/cancel': cancelDeletion,
  '/v1/installations/remove': removeInstallation,
  '/v1/verify': verify,
  '/v1/verify/apple': verifyApple,
  '/v1/purchases/transfer': transfer,
  '/v1/purchases/transfer/apple': transferApple,
  '/v1/apple/notifications': appleNotification,
  '/v1/quota/reserve': reserve,
  '/v1/quota/release': release,
  '/v1/quota/import': importLedger,
  '/v1/rtdn': rtdn,
}

export default {
  async fetch(request, env, _ctx, fetcher = fetch) {
    const path = new URL(request.url).pathname
    if (request.method !== 'POST') return json(405, { error: 'POST only' })
    const route = ROUTES[path]
    if (!route) return json(404, { error: 'not_found' })
    try {
      const ip = request.headers.get('cf-connecting-ip')
      if (path !== '/v1/rtdn' && ip && !(await allowed(env, `ip:${b64url(await sha256(ip))}`, 300, 60))) return json(429, { error: 'slow_down' })
      return await route(request, env, fetcher)
    } catch (e) {
      if (e instanceof AuthError) return json(e.status, { error: e.code })
      console.error('request failed', path, e?.name)
      return json(500, { error: 'server_error' })
    }
  },
  async scheduled(_event, env) {
    await housekeeping(env, fetch)
  },
}


