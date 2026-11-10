// Aurora's account service: a Cloudflare Worker with D1 and no dependencies (docs/ACCOUNT_SYSTEM.md, docs/BILLING.md).
//
// Every /v1 route but the two store notifications needs a verified Firebase ID token, and every query is scoped to that
// token's account. What the app stores for offline use (Plus, a commitment's authorization) is signed with our private key.
// Free's allowance is held twice, by the account and by the installation (docs/ANTI_ABUSE.md).
//
// Environment: PACKAGE_NAME, PRODUCT_IDS (Google Play), APPLE_BUNDLE_ID, APPLE_PRODUCT_IDS, APPLE_ALLOW_SANDBOX (App Store),
// FIREBASE_PROJECT_ID; secrets GOOGLE_SERVICE_ACCOUNT, FIREBASE_SERVICE_ACCOUNT, SIGNING_KEY, RTDN_TOKEN, APPLE_ISSUER_ID,
// APPLE_KEY_ID, APPLE_PRIVATE_KEY; the D1 database DB. Rollout flags: DEVICE_QUOTA (off, shadow, enforce; default
// enforce), INTEGRITY (off, observe, enforce; default off), APP_CERT_SHA256 (Play's app signing certificate digests).

import { appAccountToken, appleEntitlement, appleStatus, appleTransaction, environmentAllowed, verifySigned } from './apple.js'
import { AuthError, bearer, isEmulated, verifyIdToken } from './auth.js'
import { ATTEST_EVERY_MS, attestNonce, deviceOf, issueNonce, judgeIntegrity, licensedOf } from './device.js'
import {
  appleLifetimeId, commitSlot, holdSlot, lifetimeEntitlement, lifetimeOn, offerFor, playLifetimeFrom, playLifetimeId, refundStatements,
} from './lifetime.js'
import { fits, quotaZone, statusOf, validZone } from './quota.js'
import {
  DeviceCheckInvalid, TRIAL_MS, TRIAL_NEW_ACCOUNT_MS, TRIAL_RETENTION_MS, androidVouches, deviceCheckReady, deviceCheckUsed, keyed, markDeviceCheck,
  normalizeEmail,
} from './trial.js'

const PLAY = 'https://androidpublisher.googleapis.com/androidpublisher/v3'
const TOKEN_URL = 'https://oauth2.googleapis.com/token'
const IDENTITY = 'https://identitytoolkit.googleapis.com/v1/projects'
const PLUS_SEATS = 5
const FRESH_AUTH_S = 10 * 60
// The same 30 days the privacy policy, the deletion page and the app promise.
export const DELETION_GRACE_MS = 30 * 86_400_000
const LOOK_AHEAD_MS = 12 * 3_600_000
const LEDGER_LOOKBACK_MS = 3 * 86_400_000
const LEDGER_RETENTION_MS = 35 * 86_400_000
const COMMIT_ATTEMPTS = 6
const ABUSE_RETENTION_MS = 30 * 86_400_000
const DEVICE_RETENTION_MS = 180 * 86_400_000
const MAX_BODY = 64 * 1024
// More accounts than this on one installation in a month is worth a look. It is never refused for it.
const ACCOUNTS_PER_DEVICE = 3
// What one account may import from phones that predate its ledger. A real import is the last few days of one phone.
const MAX_IMPORTED = 400
const IMPORT_HORIZON_MS = 86_400_000

const b64 = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes)))
const b64url = (bytes) => b64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
const unb64 = (text) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0))
const utf8 = (text) => new TextEncoder().encode(text)
const json = (status, body) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
const hex = (bytes) => [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, '0')).join('')
const text = (v, max = 256) => typeof v === 'string' && v.length > 0 && v.length <= max
// The app's install id is a UUID. Tests and older builds use other short ids, so this is a shape, not a format.
const isId = (v) => typeof v === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(v)

const rsa = (usage) => ({ name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256', usages: [usage] })

async function sign(pkcs8, value) {
  const key = await crypto.subtle.importKey('pkcs8', pkcs8, rsa('sign'), false, ['sign'])
  return crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, utf8(value))
}

async function signed(env, body) {
  const payload = JSON.stringify(body)
  return { payload, signature: b64(await sign(unb64(env.SIGNING_KEY), payload)) }
}

// Google's OAuth tokens last an hour. Minting one per request cost an RSA signature and a round trip to Google on
// every call, and counts against Google's token quota. Kept per isolate, and dropped five minutes before it expires.
// ponytail: a token Google revokes early is used until then; drop the entry on a 401 if that ever happens.
const accessTokens = new Map()

async function googleAccessToken(serviceAccount, scope, fetcher) {
  const account = JSON.parse(serviceAccount)
  const cacheKey = `${account.client_email}\n${scope}`
  const cached = accessTokens.get(cacheKey)
  if (cached && Date.now() < cached.until) return cached.token
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
  const { access_token: token, expires_in: life } = await res.json()
  if (typeof token !== 'string' || !token) throw new Error('token missing')
  accessTokens.set(cacheKey, { token, until: Date.now() + (Number(life) > 0 ? Number(life) : 3600) * 1000 - 5 * 60_000 })
  return token
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

async function playProduct(env, productId, token, fetcher) {
  const access = await googleAccessToken(env.GOOGLE_SERVICE_ACCOUNT, 'https://www.googleapis.com/auth/androidpublisher', fetcher)
  const url = `${PLAY}/applications/${env.PACKAGE_NAME}/purchases/products/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(token)}`
  const res = await fetcher(url, { headers: { authorization: `Bearer ${access}` } })
  if (res.status === 404 || res.status === 410) return null
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
  if (isEmulated(env)) return
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
  const joined = await env.DB.prepare(
    `INSERT INTO installations (id, account_id, label, registered_at, last_seen_at) VALUES (?1, ?2, ?3, ?4, ?4)
     ON CONFLICT (account_id, id) DO UPDATE SET label = COALESCE(?3, label), last_seen_at = ?4, revoked_at = NULL,
       registered_at = CASE WHEN revoked_at IS NULL THEN registered_at ELSE ?4 END
     RETURNING registered_at`,
  ).bind(installId, account.id, text(label, 80) ? label : null, now).first()
  if (!row && joined.registered_at === now) {
    const { n } = await env.DB.prepare('SELECT COUNT(*) AS n FROM installations WHERE id = ? AND last_seen_at > ?').bind(installId, now - ABUSE_RETENTION_MS).first()
    if (n > ACCOUNTS_PER_DEVICE) await audit(env, 'many_accounts', installId, account.id, String(n))
  }
  return true
}

/** Suspected abuse, for a person to look at. Never a token, target, key or address; never fails the request. */
async function audit(env, kind, installId, accountId, detail = null) {
  try {
    await env.DB.prepare('INSERT INTO abuse_events (kind, installation_id, account_id, detail, created_at) VALUES (?, ?, ?, ?, ?)')
      .bind(kind, installId ?? null, accountId ?? null, detail, Date.now()).run()
  } catch {
    // Observability only.
  }
}

/** Whether [installId] holds one of the account's Plus seats: the five registered first. */
async function seated(env, account, installId) {
  const { results: seats } = await env.DB.prepare('SELECT id FROM installations WHERE account_id = ? AND revoked_at IS NULL ORDER BY registered_at, id LIMIT ?').bind(account.id, PLUS_SEATS).all()
  return seats.some((s) => s.id === installId)
}

/**
 * The account's entitlement, from durable rows only (docs/MONETIZATION_PLAN.md, 3.1). A paid production purchase,
 * lifetime or subscription, outranks a trial: lifetime rows are paid through year 9999, so ordering by paid_through
 * already puts them first. A trial counts from the moment its grant committed until it expires or is revoked.
 */
async function entitlementOf(env, account, now) {
  const { results: rows } = await env.DB.prepare('SELECT * FROM purchases WHERE account_id = ? ORDER BY paid_through DESC').bind(account.id).all()
  const production = rows.filter((r) => r.environment !== 'Sandbox')
  const best = production.find((r) => r.tier === 'plus' && r.paid_through > now) ?? null
  const trial = await env.DB.prepare('SELECT * FROM trial_claims WHERE account_id = ?').bind(account.id).first()
  const trialLive = Boolean(trial) && !trial.revoked_at && trial.granted_at <= now && now < trial.expires_at
  return { rows, production, best, trial, trialPlus: !best && trialLive ? trial : null }
}

const trialEnd = (trial) => (trial.revoked_at ? Math.min(trial.expires_at, trial.revoked_at) : trial.expires_at)

/** What the account's verified purchases, or its trial, grant this installation, ready to store. */
async function accountSnapshot(env, account, uid, installId, now, extra = {}) {
  const e = await entitlementOf(env, account, now)
  const review = environmentAllowed(env, 'Sandbox') ? e.rows.find((r) => r.environment === 'Sandbox' && r.tier === 'plus' && r.paid_through > now) : null
  const seat = await seated(env, account, installId)
  const plus = Boolean(e.best ?? e.trialPlus)
  const tier = plus && seat ? 'plus' : 'free'
  const shown = e.best ?? (e.trialPlus ? null : e.production[0])
  // A trial is "paid through" its end and never renews, so clients that predate trials still end it on time offline.
  const trial = e.trialPlus
  return signed(env, {
    tier,
    state: plus && !seat ? 'too_many_devices' : trial ? 'trial' : (shown?.state ?? (e.trial ? 'trial_ended' : 'none')),
    source: !plus ? null : trial ? 'trial' : e.best.state === 'lifetime' ? 'lifetime' : 'subscription',
    productId: shown?.product_id ?? null,
    store: shown?.store ?? null,
    iosSandbox: review && seat ? { scope: 'ios_sandbox', productId: review.product_id, expiresAt: Math.min(review.paid_through, now + 15 * 60_000) } : null,
    paidThrough: trial ? trial.expires_at : (shown?.paid_through ?? 0),
    autoRenewing: tier === 'plus' && !trial && Boolean(e.best.auto_renewing),
    trialEndsAt: e.trial ? trialEnd(e.trial) : null,
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

/** Plus here and now: a paid production purchase or a live trial, on one of the account's seats. A sixth phone is Free. */
const hasPlus = async (env, account, installId, now) => {
  const e = await entitlementOf(env, account, now)
  return Boolean(e.best ?? e.trialPlus) && (await seated(env, account, installId))
}

async function ledgerOf(env, account, now) {
  const { results } = await env.DB.prepare("SELECT * FROM quota_reservations WHERE account_id = ? AND status <> 'released' AND end_at > ?").bind(account.id, now - LEDGER_LOOKBACK_MS).all()
  return results
}

/** Everything committed on this installation, by any account, including accounts deleted since. */
async function deviceLedgerOf(env, installId, now) {
  const since = now - LEDGER_LOOKBACK_MS
  const { results } = await env.DB.prepare(
    `SELECT installation_id, start_at, end_at, zone FROM quota_reservations WHERE installation_id = ?1 AND status <> 'released' AND end_at > ?2
     UNION ALL SELECT installation_id, start_at, end_at, zone FROM device_usage WHERE installation_id = ?1 AND end_at > ?2`,
  ).bind(installId, since).all()
  return results
}

const deviceQuota = (env) => env.DEVICE_QUOTA ?? 'enforce'

/** Whether any installation this account has used was judged a modified build or a failing device this week. */
const failedRecently = async (env, account, now) => Boolean(await env.DB.prepare(
  "SELECT 1 FROM installations i JOIN devices d ON d.id = i.id WHERE i.account_id = ? AND d.integrity = 'failed' AND d.integrity_at > ? LIMIT 1",
).bind(account.id, now - ATTEST_EVERY_MS).first())

/** Today's allowance as the person can use it: the account's or the installation's, whichever has less left. */
async function quotaFor(env, account, installId, now, zone) {
  const ledger = await ledgerOf(env, account, now)
  const own = { ...statusOf(ledger, now, quotaZone(ledger, now, zone)), limitedBy: 'account' }
  if (deviceQuota(env) !== 'enforce') return own
  const machine = await deviceLedgerOf(env, installId, now)
  const here = { ...statusOf(machine, now, quotaZone(machine, now, zone)), limitedBy: 'device' }
  return here.remainingMs < own.remainingMs ? here : own
}

const raws = new WeakMap()
/** The body exactly as sent: the device key signs these bytes. */
async function rawBody(request) {
  if (!raws.has(request)) raws.set(request, await request.text().catch(() => ''))
  return raws.get(request)
}

async function body(request) {
  try {
    const parsed = JSON.parse(await rawBody(request))
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
}

/** The proved device behind this request, or a refusal to send back. */
async function device(request, env, installId, now) {
  const row = await deviceOf(env, request, installId, await rawBody(request), now)
  if (!row) await audit(env, 'device_proof_failed', installId, null)
  return row
}

const deviceRefused = () => json(403, { error: 'device_proof_required' })

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
  if (!isId(installId)) return json(400, { error: 'bad_request' })
  if (!(await allowed(env, `sync:${claims.sub}`, 60, 60))) return json(429, { error: 'slow_down' })
  const now = Date.now()
  const here = await device(request, env, installId, now)
  if (!here) return deviceRefused()
  const account = await ensureAccount(env, claims, now)
  if (!(await ensureInstallation(env, account, claims, installId, label, now))) return json(401, { error: 'installation_removed' })
  const plus = await hasPlus(env, account, installId, now)
  return json(200, {
    account: { id: account.id, email: account.email, displayName: account.display_name, deletionRequestedAt: account.deletion_requested_at },
    entitlement: await accountSnapshot(env, account, claims.sub, installId, now),
    quota: plus ? null : await quotaFor(env, account, installId, now, validZone(zone) ? zone : 'UTC'),
    devices: await devicesOf(env, account, installId),
    trial: await trialStatus(env, account, now),
    lifetime: { offer: await offerFor(env, account, now) },
    device: { proved: here.proved, integrity: here.integrity, attestNonce: await attestNonce(env, here, now) },
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
  if (![productId, packageName].every((v) => text(v)) || !isId(installId) || !text(purchaseToken, 4096)) return json(400, { error: 'bad_request' })
  if (packageName !== env.PACKAGE_NAME) return json(400, { error: 'wrong_package' })
  if (!(await allowed(env, `verify:${claims.sub}`, 30, 60))) return json(429, { error: 'slow_down' })
  const now = Date.now()
  const account = await ensureAccount(env, claims, now)
  if (!(await ensureInstallation(env, account, claims, installId, null, now))) return json(401, { error: 'installation_removed' })
  if (productId === playLifetimeId(env)) return verifyPlayLifetime(env, fetcher, { claims, account, installId, purchaseToken, productId, now })

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

/** Purchase tokens are replayable; a destination sign-in does not authorize reassignment. */
async function transfer(request, env, fetcher) {
  await authenticate(request, env, fetcher, { revocation: true })
  return json(403, { error: 'transfer_disabled' })
}

// ---- App Store --------------------------------------------------------------------------------------------------
// Only Apple-signed evidence plus an independent status read can bind a purchase to its verified Aurora account.
// Sandbox grants separate, short-lived iOS review access. Purchase evidence never authorizes ownership transfers.
const APPLE_SETTINGS = ['APPLE_BUNDLE_ID', 'APPLE_PRODUCT_IDS', 'APPLE_ISSUER_ID', 'APPLE_KEY_ID', 'APPLE_PRIVATE_KEY']
const appleReady = (env) => APPLE_SETTINGS.every((name) => env[name])
const appleConfig = (env) => ({ bundleId: env.APPLE_BUNDLE_ID, productIds: env.APPLE_PRODUCT_IDS.split(',').map((s) => s.trim()) })
const appleKey = (id, environment) => tokenKey(`apple:${environment}:${id}`)
const APPLE_LAPSE_RECHECK_MS = 6 * 3_600_000

async function appleAsk(env, originalTransactionId, fetcher, environment = 'Production') {
  return appleEntitlement(await appleStatus(env, originalTransactionId, fetcher, environment), originalTransactionId, appleConfig(env))
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
  if (claimed.inAppOwnershipType !== 'PURCHASED') return { refused: json(403, { error: 'family_sharing_disabled' }) }
  const id = claimed.originalTransactionId
  if (!text(id, 64)) return { refused: json(400, { error: 'invalid_transaction' }) }
  const checkedAt = Date.now()
  try {
    const entitlement = await appleAsk(env, id, fetcher, claimed.environment)
    if (entitlement.known && entitlement.ownership !== 'PURCHASED') return { refused: json(403, { error: 'family_sharing_disabled' }) }
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
  const claimed = await verifySigned(signedTransaction).catch(() => null)
  if (claimed?.productId === appleLifetimeId(env) && claimed.bundleId === env.APPLE_BUNDLE_ID) return verifyAppleLifetime(env, fetcher, { claims, account, installId, claimed, now })

  const answer = await appleAnswer(env, signedTransaction, fetcher)
  if (answer.refused) return answer.refused
  const snapshot = (extra) => accountSnapshot(env, account, claims.sub, installId, now, extra)
  if (!answer.entitlement.known) return json(200, await snapshot({ purchaseValid: false }))

  const key = await appleKey(answer.id, answer.entitlement.environment)
  const conflict = async (owner, extra = {}) => {
    const holder = owner ? await env.DB.prepare('SELECT email FROM accounts WHERE id = ?').bind(owner).first() : null
    return json(200, { ...(await snapshot({ purchaseValid: true })), conflict: true, conflictHint: maskEmail(holder?.email), ...extra })
  }
  const ownerOf = async () => (await env.DB.prepare('SELECT account_id FROM purchases WHERE token_hash = ?').bind(key).first())?.account_id
  const owner = await ownerOf()
  if (owner && owner !== account.id) return conflict(owner)
  if (!owner) {
    const token = answer.entitlement.appAccountToken
    // Missing signed ownership requires original-account recovery, never an automatic claim.
    if (!token || answer.entitlement.ownership !== 'PURCHASED') return conflict(null, { claimRequired: true })
    if (token !== (await appAccountToken(claims.sub))) return conflict(null)
  }
  if (!(await storeApple(env, account, key, answer))) {
    const holder = await ownerOf() // Lost the race, or a newer answer got there first.
    if (holder !== account.id) return conflict(holder)
  }
  return json(200, await snapshot({ purchaseValid: true }))
}

/** A signed transaction is replayable evidence, not authority to change its owner. */
async function transferApple(request, env, fetcher) {
  await authenticate(request, env, fetcher, { revocation: true })
  return json(403, { error: 'transfer_disabled' })
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
  const raw = await rawBody(request)
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
  const key = await appleKey(tx.originalTransactionId, data.environment)
  const known = await env.DB.prepare('SELECT event_at, store, environment FROM purchases WHERE token_hash = ?').bind(key).first()
  // Nothing bound yet: the app's own verify will bind it. A repeat or a delayed message says nothing new: the state we
  // hold was read from Apple after an event at least this recent.
  if (!known || known.store !== 'app_store' || known.environment !== data.environment || note.signedDate <= known.event_at) return ack()
  const checkedAt = Date.now()
  if (tx.productId === appleLifetimeId(env)) {
    let current
    try {
      current = await appleTransaction(env, tx.transactionId, fetcher, data.environment)
    } catch {
      return json(503, { error: 'try_again' })
    }
    // Apple's own record, not the notification's fields, decides. Only a revocation changes a lifetime purchase.
    if (current && Number.isFinite(current.revocationDate)) await revokeLifetime(env, key, 'refunded', checkedAt)
    return ack()
  }
  let entitlement
  try {
    entitlement = await appleAsk(env, tx.originalTransactionId, fetcher, data.environment)
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
    "SELECT token_hash, store_ref, environment FROM purchases WHERE store = 'app_store' AND auto_renewing = 1 AND paid_through < ? AND checked_at < ? ORDER BY checked_at LIMIT 20",
  ).bind(now, now - APPLE_LAPSE_RECHECK_MS).all()
  for (const row of results) {
    try {
      const checkedAt = Date.now()
      await refreshApple(env, row.token_hash, await appleAsk(env, row.store_ref, fetcher, row.environment), checkedAt)
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
  if (!isId(installId) || ![idempotencyKey, commitmentId].every((v) => text(v, 128)) || !MODES.has(mode) || !validZone(zone)) return json(400, { error: 'bad_request' })
  if (!Number.isInteger(minutes) || minutes < 1 || minutes > 1440) return json(400, { error: 'bad_request' })
  if (startAt !== undefined && !Number.isFinite(startAt)) return json(400, { error: 'bad_request' })
  if (!(await allowed(env, `reserve:${claims.sub}`, 30, 60))) return json(429, { error: 'slow_down' })
  const now = Date.now()
  const here = await device(request, env, installId, now)
  if (!here) return deviceRefused()
  // Many accounts on one phone share this one, so a phone can't out-ask its own allowance by switching. Counted
  // only once the request is the installation's own, so nobody can use up another phone's budget.
  if (!(await allowed(env, `reserve-device:${installId}`, 30, 60))) return json(429, { error: 'slow_down' })
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

  if (await hasPlus(env, account, installId, now)) return json(200, { allowed: true, tier: 'plus', authorization: await authorization(null, start, end, 'plus', {}) })

  // Risk-based: only a verdict that this is a modified Aurora or a failing device holds Free back, and only when
  // enforcing. No verdict at all (no Play services, a development build) is never a reason to refuse, except right
  // after a failure on this account: a new install id (or none signed at all) mustn't wash a failed verdict away.
  // This installation's own verdict, 'ok' or 'basic', clears it at once.
  if (env.INTEGRITY === 'enforce' && (here.integrity === 'failed' || (here.integrity === 'unknown' && (await failedRecently(env, account, now))))) {
    await audit(env, 'integrity_refused', installId, account.id)
    return json(403, { error: 'integrity_failed' })
  }

  const enforceDevice = deviceQuota(env) === 'enforce'
  const watchDevice = deviceQuota(env) !== 'off'
  for (let attempt = 0; attempt < COMMIT_ATTEMPTS; attempt++) {
    // Both revisions before either ledger: whatever is written after this read makes the insert below a no-op.
    const { quota_revision: revision } = await env.DB.prepare('SELECT quota_revision FROM accounts WHERE id = ?').bind(account.id).first()
    const { quota_revision: deviceRevision } = await env.DB.prepare('SELECT quota_revision FROM devices WHERE id = ?').bind(installId).first()
    const ledger = await ledgerOf(env, account, now)
    const machine = watchDevice ? await deviceLedgerOf(env, installId, now) : []
    const day = quotaZone(ledger, now, zone)
    const deviceDay = quotaZone(machine, now, zone)
    // The key after the ledgers: a duplicate request racing this one is either in the ledgers read above and found
    // here (a replay), or wrote after the revisions were read, so this one's insert is a no-op and it looks again.
    // Read before the ledgers, a duplicate committed in between was judged as new time and refused.
    const existing = await env.DB.prepare('SELECT * FROM quota_reservations WHERE account_id = ? AND idempotency_key = ?').bind(account.id, idempotencyKey).first()
    const status = (at, extra = []) => {
      const own = { ...statusOf([...ledger, ...extra], at, day), limitedBy: 'account' }
      if (!enforceDevice) return own
      const onDevice = { ...statusOf([...machine, ...extra], at, deviceDay), limitedBy: 'device' }
      return onDevice.remainingMs < own.remainingMs ? onDevice : own
    }
    // A key names one commitment on one installation. Another installation can't borrow its authorization.
    if (existing && existing.installation_id !== installId) return json(409, { error: 'idempotency_conflict' })
    if (existing) {
      return json(200, { allowed: true, tier: 'free', replayed: true, authorization: await authorization(existing.id, existing.start_at, existing.end_at, 'free', status(now)) })
    }
    if (!fits(ledger, installId, start, end, day)) return json(200, { allowed: false, tier: 'free', ...status(start), limitedBy: 'account' })
    if (watchDevice && !fits(machine, installId, start, end, deviceDay)) {
      await audit(env, enforceDevice ? 'device_quota_refused' : 'device_quota_shadow', installId, account.id)
      if (enforceDevice) return json(200, { allowed: false, tier: 'free', ...status(start), limitedBy: 'device' })
    }
    const id = crypto.randomUUID()
    // One transaction, and only if neither ledger changed since it was read: two phones on one account, or two
    // accounts on one phone, can't both spend the last minutes. D1 runs a batch as one serialized transaction.
    const [inserted] = await env.DB.batch([
      env.DB.prepare(
        `INSERT OR IGNORE INTO quota_reservations (id, account_id, installation_id, idempotency_key, commitment_id, mode, start_at, end_at, zone, created_at)
         SELECT ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10 FROM accounts a, devices d
         WHERE a.id = ?2 AND a.quota_revision = ?11 AND d.id = ?3 AND d.quota_revision = ?12`,
      ).bind(id, account.id, installId, idempotencyKey, commitmentId, mode, start, end, day, now, revision, deviceRevision),
      env.DB.prepare('UPDATE accounts SET quota_revision = quota_revision + 1 WHERE id = ? AND EXISTS (SELECT 1 FROM quota_reservations WHERE id = ?)').bind(account.id, id),
      env.DB.prepare('UPDATE devices SET quota_revision = quota_revision + 1 WHERE id = ? AND EXISTS (SELECT 1 FROM quota_reservations WHERE id = ?)').bind(installId, id),
    ])
    if (inserted.meta.changes > 0) {
      const added = [{ installation_id: installId, start_at: start, end_at: end, zone: day }]
      return json(200, { allowed: true, tier: 'free', authorization: await authorization(id, start, end, 'free', status(now, added)) })
    }
  }
  return json(503, { error: 'try_again' })
}

/**
 * Gives back a reservation that never began: a schedule removed in time. Anything started is spent. Its key is
 * freed, so asking again for the same occurrence (a schedule paused and turned back on) is charged again rather
 * than replaying an authorization whose time was given back.
 */
async function release(request, env, fetcher) {
  const claims = await authenticate(request, env, fetcher)
  const { reservationId } = await body(request)
  if (!text(reservationId, 64)) return json(400, { error: 'bad_request' })
  const now = Date.now()
  const account = await ensureAccount(env, claims, now)
  const row = await env.DB.prepare('SELECT start_at, status, installation_id FROM quota_reservations WHERE id = ? AND account_id = ?').bind(reservationId, account.id).first()
  if (!row) return json(404, { error: 'not_found' })
  if (row.start_at <= now) return json(409, { error: 'already_started' })
  await env.DB.batch([
    env.DB.prepare("UPDATE quota_reservations SET status = 'released', idempotency_key = idempotency_key || ':released:' || id WHERE id = ? AND account_id = ? AND start_at > ? AND status <> 'released'").bind(reservationId, account.id, now),
    env.DB.prepare('UPDATE accounts SET quota_revision = quota_revision + 1 WHERE id = ?').bind(account.id),
    env.DB.prepare('UPDATE devices SET quota_revision = quota_revision + 1 WHERE id = ?').bind(row.installation_id),
  ])
  return json(200, { released: true })
}

/** An existing phone's recent committed time joins the ledger once. It can only add to what the account has used. */
async function importLedger(request, env, fetcher) {
  const claims = await authenticate(request, env, fetcher)
  const { installId, entries } = await body(request)
  if (!isId(installId) || !Array.isArray(entries) || entries.length > 200) return json(400, { error: 'bad_request' })
  if (!(await allowed(env, `import:${claims.sub}`, 10, 3600))) return json(429, { error: 'slow_down' })
  const now = Date.now()
  // Imported time lands on this installation's ledger too, so only the installation itself may add it.
  if (!(await device(request, env, installId, now))) return deviceRefused()
  const account = await ensureAccount(env, claims, now)
  const { n: imported } = await env.DB.prepare("SELECT COUNT(*) AS n FROM quota_reservations WHERE account_id = ? AND status = 'migrated'").bind(account.id).first()
  const statements = []
  for (const e of entries) {
    if (!Number.isFinite(e?.start) || !Number.isFinite(e?.end) || e.end <= e.start || !validZone(e.zone)) return json(400, { error: 'bad_request' })
    if (e.end <= now - LEDGER_LOOKBACK_MS || e.start > now + IMPORT_HORIZON_MS) continue
    // Bounded, so every row ages out with housekeeping and one account can't grow the database without end.
    if (imported + statements.length >= MAX_IMPORTED) break
    const end = Math.min(e.end, now + IMPORT_HORIZON_MS)
    statements.push(env.DB.prepare(
      `INSERT OR IGNORE INTO quota_reservations (id, account_id, installation_id, idempotency_key, commitment_id, mode, start_at, end_at, zone, status, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'migrated', ?)`,
    ).bind(crypto.randomUUID(), account.id, installId, `import:${installId}:${e.start}`, `import:${e.start}`, e.mode === 'cooldown' ? 'cooldown' : 'always_block', e.start, end, e.zone, now))
  }
  if (statements.length) {
    await env.DB.batch([
      ...statements,
      env.DB.prepare('UPDATE accounts SET quota_revision = quota_revision + 1 WHERE id = ?').bind(account.id),
      env.DB.prepare('UPDATE devices SET quota_revision = quota_revision + 1 WHERE id = ?').bind(installId),
    ])
  }
  return json(200, { imported: statements.length })
}

async function removeInstallation(request, env, fetcher) {
  const claims = await authenticate(request, env, fetcher, { revocation: true })
  const { installId, currentInstallId } = await body(request)
  if (!isId(installId)) return json(400, { error: 'bad_request' })
  const now = Date.now()
  const account = await ensureAccount(env, claims, now)
  const { meta } = await env.DB.prepare('UPDATE installations SET revoked_at = ? WHERE account_id = ? AND id = ? AND revoked_at IS NULL').bind(now, account.id, installId).run()
  if (meta.changes === 0) return json(404, { error: 'not_found' })
  return json(200, { devices: await devicesOf(env, account, currentInstallId) })
}

async function decodeIntegrity(env, token, fetcher) {
  const access = await googleAccessToken(env.GOOGLE_SERVICE_ACCOUNT, 'https://www.googleapis.com/auth/playintegrity', fetcher)
  const res = await fetcher(`https://playintegrity.googleapis.com/v1/${env.PACKAGE_NAME}:decodeIntegrityToken`, {
    method: 'POST',
    headers: { authorization: `Bearer ${access}`, 'content-type': 'application/json' },
    body: JSON.stringify({ integrity_token: token }),
  })
  if (!res.ok) throw new Error(`integrity ${res.status}`)
  return (await res.json()).tokenPayloadExternal
}

/** A Play Integrity token for the nonce this device was given. Each nonce counts once; the verdict is kept a week. */
async function attest(request, env, fetcher) {
  const claims = await authenticate(request, env, fetcher)
  const { installId, token } = await body(request)
  if (!isId(installId) || !text(token, 8192)) return json(400, { error: 'bad_request' })
  if ((env.INTEGRITY ?? 'off') === 'off' && env.TRIAL !== 'on') return json(404, { error: 'not_found' })
  const now = Date.now()
  const here = await device(request, env, installId, now)
  if (!here) return deviceRefused()
  // Only a keyed installation is ever given a nonce. Not a reason to start over as a new installation.
  if (!here.proved) return json(403, { error: 'device_unproved' })
  if (!(await allowed(env, `attest:${installId}`, 5, 3600))) return json(429, { error: 'slow_down' })
  const nonce = here.attest_nonce
  if (!nonce || !(here.attest_nonce_expires_at > now)) return json(409, { error: 'stale_nonce' })
  let verdict
  try {
    verdict = await decodeIntegrity(env, token, fetcher)
  } catch {
    // Google can't say. Nothing changes: inconclusive is never held against the device.
    return json(503, { error: 'try_again' })
  }
  const integrity = judgeIntegrity(verdict, env, nonce, now)
  // Spent either way, in one statement, so the same token or nonce can't be presented twice.
  const { meta } = await env.DB.prepare(
    `UPDATE devices SET attest_nonce = NULL, attest_nonce_expires_at = NULL,
       integrity = COALESCE(?1, integrity), integrity_at = CASE WHEN ?1 IS NULL THEN integrity_at ELSE ?2 END,
       licensed = CASE WHEN ?1 IS NULL THEN licensed ELSE ?5 END
     WHERE id = ?3 AND attest_nonce = ?4`,
  ).bind(integrity, now, installId, nonce, licensedOf(verdict) ? 1 : 0).run()
  if (meta.changes === 0) return json(409, { error: 'stale_nonce' })
  if (integrity === null) return json(400, { error: 'integrity_mismatch' })
  if (integrity === 'failed') {
    const account = await ensureAccount(env, claims, now)
    await audit(env, 'integrity_failed', installId, account.id)
  }
  return json(200, { integrity })
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
  const message = (await body(request)).message?.data
  if (!message) return new Response(null, { status: 204 })
  let note
  try {
    note = JSON.parse(atob(message))
  } catch {
    return new Response(null, { status: 204 })
  }
  if (note.packageName !== env.PACKAGE_NAME) return new Response(null, { status: 204 })
  const oneTime = note.oneTimeProductNotification ?? note.voidedPurchaseNotification
  if (oneTime?.purchaseToken) return rtdnOneTime(env, fetcher, note)
  const token = note.subscriptionNotification?.purchaseToken
  // Test notifications: acknowledged, nothing to do.
  if (!token) return new Response(null, { status: 204 })
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

// ---- Founding Lifetime --------------------------------------------------------------------------------------------

/** Binds a verified lifetime purchase to the account, numbers its slot, and records what happened. Null on conflict. */
async function grantLifetime(env, account, key, store, region, test, store_) {
  if (!(await store_())) return null
  const committed = await commitSlot(env, { key, accountId: account.id, store, region, test }, Date.now())
  await entitlementEvent(env, account.id, `lifetime_${committed.status}`, committed.slot ? String(committed.slot) : region)
  // Over the cap, outside the US, or bought twice: honored, and someone should look. Logged for alerting.
  if (committed.status === 'overflow' || committed.status === 'duplicate') console.warn('lifetime_attention', committed.status)
  return committed
}

/** Google Play's word on a one-time lifetime purchase, bound to the account that bought it. */
async function verifyPlayLifetime(env, fetcher, { claims, account, installId, purchaseToken, productId, now }) {
  let p
  try {
    p = playLifetimeFrom(await playProduct(env, productId, purchaseToken, fetcher))
  } catch {
    return json(503, { error: 'try_again' })
  }
  const snapshot = (extra) => accountSnapshot(env, account, claims.sub, installId, now, extra)
  if (!p || !p.purchased) return json(200, await snapshot({ purchaseValid: false, pending: Boolean(p?.pending) }))
  const key = await tokenKey(purchaseToken)
  // A lifetime purchase is always made under the buyer's Aurora account id. One that isn't can't be claimed here.
  if (!p.external) return json(200, { ...(await snapshot({ purchaseValid: true })), conflict: true, claimRequired: true })
  const owner = await ownerConflict(env, account, claims.sub, { externalAccountIdentifiers: { obfuscatedExternalAccountId: p.external } }, key)
  const committed = owner === null && (await grantLifetime(env, account, key, 'google_play', p.region, p.test,
    () => storePurchase(env, account, key, lifetimeEntitlement(productId), now)))
  if (!committed) {
    const holder = owner && owner !== 'unknown' ? await env.DB.prepare('SELECT email FROM accounts WHERE id = ?').bind(owner).first() : null
    return json(200, { ...(await snapshot({ purchaseValid: true })), conflict: true, conflictHint: maskEmail(holder?.email) })
  }
  return json(200, { ...(await snapshot({ purchaseValid: true })), lifetime: committed })
}

/** Apple's own record of a non-consumable lifetime purchase, read independently of what the app sent. */
async function verifyAppleLifetime(env, fetcher, { claims, account, installId, claimed, now }) {
  const snapshot = (extra) => accountSnapshot(env, account, claims.sub, installId, now, extra)
  if (!environmentAllowed(env, claimed.environment)) return json(400, { error: 'wrong_environment' })
  if (claimed.inAppOwnershipType !== 'PURCHASED') return json(403, { error: 'family_sharing_disabled' })
  let tx
  try {
    tx = await appleTransaction(env, claimed.transactionId, fetcher, claimed.environment)
  } catch {
    return json(503, { error: 'try_again' })
  }
  const genuine = tx && tx.bundleId === env.APPLE_BUNDLE_ID && tx.productId === appleLifetimeId(env) && tx.type === 'Non-Consumable' && tx.inAppOwnershipType === 'PURCHASED'
  if (!genuine || Number.isFinite(tx.revocationDate)) return json(200, await snapshot({ purchaseValid: false }))
  // Bought under this account: the app sets appAccountToken from the signed-in UID; the server derives it again.
  if (String(tx.appAccountToken ?? '').toLowerCase() !== (await appAccountToken(claims.sub))) return json(200, { ...(await snapshot({ purchaseValid: true })), conflict: true, claimRequired: true })
  const key = await appleKey(tx.originalTransactionId, tx.environment)
  const entitlement = lifetimeEntitlement(tx.productId, tx.environment)
  const committed = await grantLifetime(env, account, key, 'app_store', tx.storefront, tx.environment !== 'Production',
    () => storeApple(env, account, key, { id: tx.originalTransactionId, checkedAt: now, entitlement }))
  if (!committed) {
    const holder = await env.DB.prepare('SELECT a.email FROM purchases p JOIN accounts a ON a.id = p.account_id WHERE p.token_hash = ?').bind(key).first()
    return json(200, { ...(await snapshot({ purchaseValid: true })), conflict: true, conflictHint: maskEmail(holder?.email) })
  }
  return json(200, { ...(await snapshot({ purchaseValid: true })), lifetime: committed })
}

/**
 * Holds a founding slot for 15 minutes before the app opens the store sheet. No hold, no purchase button: the offer
 * is never shown to more people than there are slots. A hold is not a promise; the slot is numbered at verification.
 */
async function lifetimeHold(request, env, fetcher) {
  const claims = await authenticate(request, env, fetcher, { revocation: true })
  const { installId } = await body(request)
  if (!isId(installId)) return json(400, { error: 'bad_request' })
  if (!(await allowed(env, `lifetime:${claims.sub}`, 20, 3600))) return json(429, { error: 'slow_down' })
  const now = Date.now()
  const account = await ensureAccount(env, claims, now)
  if (account.status !== 'active') return json(403, { error: 'account_deleting' })
  if (!(await ensureInstallation(env, account, claims, installId, null, now))) return json(401, { error: 'installation_removed' })
  const offer = await offerFor(env, account, now)
  if (offer === 'off' || offer === 'owned') return json(200, { held: false, offer })
  const until = await holdSlot(env, account, now)
  return json(200, until ? { held: true, offer: 'held', expiresAt: until } : { held: false, offer: 'sold_out' })
}

// ---- Trial ------------------------------------------------------------------------------------------------------

/** Why this account can't have a trial, whatever the device: null when it can. Needs TRIAL_PEPPER. */
async function accountIneligible(env, account, rows, now) {
  if (account.status !== 'active') return 'account_deleting'
  if (now - account.created_at > TRIAL_NEW_ACCOUNT_MS) return 'not_new'
  if (rows.some((r) => r.environment !== 'Sandbox')) return 'purchased'
  const used = await env.DB.prepare('SELECT 1 FROM trial_claims WHERE uid_hash = ? OR email_hash = ? LIMIT 1')
    .bind(await keyed(env, `uid:${account.firebase_uid}`), await keyed(env, `email:${normalizeEmail(account.email ?? '')}`)).first()
  return used ? 'account_used' : null
}

const trialsOn = (env) => env.TRIAL === 'on' && Boolean(env.TRIAL_PEPPER)

/** What the app shows about the trial. 'available' is the account's side only; the device is judged at the claim. */
async function trialStatus(env, account, now) {
  const e = await entitlementOf(env, account, now)
  if (e.trial) return { status: e.trialPlus || (!e.trial.revoked_at && now < e.trial.expires_at) ? 'active' : 'ended', startedAt: e.trial.granted_at, endsAt: trialEnd(e.trial) }
  if (!trialsOn(env)) return { status: 'unavailable', reason: 'off' }
  const reason = await accountIneligible(env, account, e.rows, now)
  return reason ? { status: 'unavailable', reason } : { status: 'available', days: TRIAL_MS / 86_400_000 }
}

const trialRefused = (status, reason, more = {}) => json(status, { error: 'trial_unavailable', reason, ...more })

/**
 * Grants the 14-day trial: a new verified account, a device that vouches for itself, and no trial before on any of
 * them. Idempotent per account: asking again, from any phone, returns the same trial and the same end. The grant is
 * one INSERT guarded by three UNIQUE constraints (account, address, installation), so racing claims can't both win.
 *
 * Device evidence. Android: a Play Integrity verdict made for this claim (fresh), judged 'ok' and LICENSED. iOS:
 * Apple's DeviceCheck says no trial was granted on this device, and is told when one is. No evidence is not
 * evidence of abuse, but it is never a trial either: the person is told why and keeps Free, and can still buy.
 */
async function claimTrial(request, env, fetcher) {
  const claims = await authenticate(request, env, fetcher, { revocation: true })
  const { installId, platform, deviceToken } = await body(request)
  if (!isId(installId) || !['android', 'ios'].includes(platform)) return json(400, { error: 'bad_request' })
  if (platform === 'ios' && !text(deviceToken, 8192)) return json(400, { error: 'bad_request' })
  if (!trialsOn(env)) return trialRefused(404, 'off')
  if (!(await allowed(env, `trial:${claims.sub}`, 10, 3600))) return json(429, { error: 'slow_down' })
  const now = Date.now()
  const here = await device(request, env, installId, now)
  if (!here) return deviceRefused()
  if (!(await allowed(env, `trial-device:${installId}`, 10, 3600))) return json(429, { error: 'slow_down' })
  const account = await ensureAccount(env, claims, now)
  if (!(await ensureInstallation(env, account, claims, installId, null, now))) return json(401, { error: 'installation_removed' })
  const granted = async () => json(200, { trial: await trialStatus(env, account, now), entitlement: await accountSnapshot(env, account, claims.sub, installId, now) })

  const e = await entitlementOf(env, account, now)
  if (e.trial) return granted()
  const refuse = async (status, reason, more) => {
    await entitlementEvent(env, account.id, 'trial_refused', reason)
    return trialRefused(status, reason, more)
  }
  const ineligible = await accountIneligible(env, account, e.rows, now)
  if (ineligible) return refuse(403, ineligible)
  if (await env.DB.prepare('SELECT 1 FROM trial_claims WHERE installation_id = ?').bind(installId).first()) return refuse(403, 'device_used')
  // Without a key the installation can't be told apart from any other request naming it.
  if (!here.proved) return refuse(403, 'device_unverifiable')

  let evidence
  if (platform === 'android') {
    const verdict = androidVouches(here, now)
    // No verdict made for this claim yet: ask for one. A fresh verdict that doesn't vouch is an answer, not a retry.
    if (!verdict.fresh) return trialRefused(428, 'attestation_required', { attestNonce: await issueNonce(env, here, now) })
    if (!verdict.ok) return refuse(403, 'device_ineligible')
    evidence = 'play_integrity'
  } else {
    if (!deviceCheckReady(env)) return refuse(503, 'not_configured')
    try {
      if (await deviceCheckUsed(env, deviceToken, fetcher)) return refuse(403, 'device_used')
    } catch (err) {
      if (err instanceof DeviceCheckInvalid) return refuse(403, 'device_unverifiable')
      return json(503, { error: 'try_again' })
    }
    evidence = 'devicecheck'
  }

  const id = crypto.randomUUID()
  const { meta } = await env.DB.prepare(
    `INSERT OR IGNORE INTO trial_claims (id, account_id, uid_hash, email_hash, installation_id, platform, evidence, granted_at, expires_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).bind(id, account.id, await keyed(env, `uid:${claims.sub}`), await keyed(env, `email:${normalizeEmail(claims.email)}`), installId, platform, evidence, now, now + TRIAL_MS).run()
  if (meta.changes === 0) {
    // Someone got there first: this account (a duplicate request, now a replay), its address, or this installation.
    if (await env.DB.prepare('SELECT 1 FROM trial_claims WHERE account_id = ?').bind(account.id).first()) return granted()
    const sameDevice = await env.DB.prepare('SELECT 1 FROM trial_claims WHERE installation_id = ?').bind(installId).first()
    return refuse(403, sameDevice ? 'device_used' : 'account_used')
  }
  if (platform === 'ios') {
    try {
      await markDeviceCheck(env, deviceToken, fetcher)
    } catch {
      // A trial Apple doesn't know about could be claimed again after a reinstall. Not granted until it does.
      await env.DB.prepare('DELETE FROM trial_claims WHERE id = ?').bind(id).run()
      return json(503, { error: 'try_again' })
    }
  }
  await entitlementEvent(env, account.id, 'trial_granted', evidence)
  return granted()
}

/** The entitlement audit trail. Never fails the request it describes. */
async function entitlementEvent(env, accountId, kind, detail = null) {
  try {
    await env.DB.prepare('INSERT INTO entitlement_events (account_id, kind, detail, created_at) VALUES (?, ?, ?, ?)').bind(accountId ?? null, kind, detail, Date.now()).run()
  } catch {
    // Observability only.
  }
}

/** Ends a lifetime purchase: refunded or revoked by the store. Its founding slot stays consumed. */
async function revokeLifetime(env, key, state, now) {
  const row = await env.DB.prepare('SELECT account_id FROM purchases WHERE token_hash = ?').bind(key).first()
  await env.DB.batch(refundStatements(env, key, state, now))
  await entitlementEvent(env, row?.account_id, 'lifetime_revoked', state)
}

/**
 * Play's one-time product and voided-purchase notifications. A voided purchase (refund, chargeback) grants nothing;
 * a cancellation is checked with Google before anything changes. Purchases nobody verified yet are left to the app.
 */
async function rtdnOneTime(env, fetcher, note) {
  const done = new Response(null, { status: 204 })
  const voided = note.voidedPurchaseNotification
  const token = (voided ?? note.oneTimeProductNotification).purchaseToken
  const key = await tokenKey(token)
  const known = await env.DB.prepare('SELECT * FROM purchases WHERE token_hash = ?').bind(key).first()
  if (!known || known.store === 'app_store' || known.state !== 'lifetime') return done
  const now = Date.now()
  if (voided) {
    await revokeLifetime(env, key, 'refunded', now)
    return done
  }
  let p
  try {
    p = playLifetimeFrom(await playProduct(env, known.product_id, token, fetcher))
  } catch {
    return json(503, { error: 'try_again' })
  }
  if (!p?.purchased) await revokeLifetime(env, key, 'canceled', now)
  return done
}

/** Daily: finishes deletions past their grace, and drops what no longer serves a purpose. */
export async function housekeeping(env, fetcher, now = Date.now()) {
  const { results } = await env.DB.prepare("SELECT id, firebase_uid FROM accounts WHERE status = 'deletion_requested' AND deletion_requested_at < ? LIMIT 100").bind(now - DELETION_GRACE_MS).all()
  for (const a of results) {
    try {
      await deleteFirebaseUser(a.firebase_uid, env, fetcher)
      // Reservations and installations cascade. A purchase row stays, unowned, so the subscription can bind to a new account.
      // The last days of committed time stay with each installation, with nothing that says whose: deleting the
      // account doesn't give the same phone its allowance back.
      await env.DB.batch([
        env.DB.prepare(
          `INSERT INTO device_usage (installation_id, start_at, end_at, zone)
           SELECT installation_id, start_at, end_at, zone FROM quota_reservations WHERE account_id = ? AND status <> 'released' AND end_at > ?`,
        ).bind(a.id, now - LEDGER_LOOKBACK_MS),
        env.DB.prepare('DELETE FROM accounts WHERE id = ?').bind(a.id),
      ])
    } catch {
      // Tomorrow's run tries again.
    }
  }
  await env.DB.batch([
    env.DB.prepare('DELETE FROM rate_limits WHERE expires_at < ?').bind(now),
    env.DB.prepare('DELETE FROM quota_reservations WHERE end_at < ?').bind(now - LEDGER_RETENTION_MS),
    env.DB.prepare('DELETE FROM device_usage WHERE end_at < ?').bind(now - LEDGER_LOOKBACK_MS),
    env.DB.prepare('DELETE FROM abuse_events WHERE created_at < ?').bind(now - ABUSE_RETENTION_MS),
    // A device unseen for half a year has nothing left in any ledger; its key goes with it.
    env.DB.prepare('DELETE FROM devices WHERE last_seen_at < ?').bind(now - DEVICE_RETENTION_MS),
    // A trial outlives its account so a new sign-up with the same address finds it; three years after it ended, not.
    env.DB.prepare('DELETE FROM trial_claims WHERE account_id IS NULL AND expires_at < ?').bind(now - TRIAL_RETENTION_MS),
    env.DB.prepare('DELETE FROM entitlement_events WHERE created_at < ?').bind(now - TRIAL_RETENTION_MS),
  ])
  if (appleReady(env)) await recheckLapsedApple(env, fetcher, now)
}

// Store servers post these; their own signature or token is the check, not an IP budget.
const NOTIFICATIONS = new Set(['/v1/rtdn', '/v1/apple/notifications'])

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
  '/v1/device/attest': attest,
  '/v1/trial/claim': claimTrial,
  '/v1/lifetime/hold': lifetimeHold,
  '/v1/rtdn': rtdn,
}

export default {
  async fetch(request, env, _ctx, fetcher = fetch) {
    const path = new URL(request.url).pathname
    if (request.method !== 'POST') return json(405, { error: 'POST only' })
    const route = ROUTES[path]
    if (!route) return json(404, { error: 'not_found' })
    try {
      // A declared size is refused before anything is read; a body without one is still measured after.
      if (Number(request.headers.get('content-length')) > MAX_BODY) return json(413, { error: 'too_large' })
      if ((await rawBody(request)).length > MAX_BODY) return json(413, { error: 'too_large' })
      const ip = request.headers.get('cf-connecting-ip')
      if (!NOTIFICATIONS.has(path) && ip && !(await allowed(env, `ip:${b64url(await sha256(ip))}`, 300, 60))) return json(429, { error: 'slow_down' })
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


