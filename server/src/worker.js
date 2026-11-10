// Aurora's account service: a Cloudflare Worker with D1 and no dependencies (docs/ACCOUNT_SYSTEM.md, docs/BILLING.md).
//
// Every /v1 route but /v1/rtdn needs a verified Firebase ID token, and every query is scoped to that token's
// account. What the app stores for offline use (Plus, a commitment's authorization) is signed with our private key.
// Free's allowance is held twice, by the account and by the installation (docs/ANTI_ABUSE.md).
//
// Environment: PACKAGE_NAME, PRODUCT_IDS, FIREBASE_PROJECT_ID; secrets GOOGLE_SERVICE_ACCOUNT, FIREBASE_SERVICE_ACCOUNT,
// SIGNING_KEY, RTDN_TOKEN; the D1 database DB. Rollout flags: DEVICE_QUOTA (off, shadow, enforce; default enforce),
// INTEGRITY (off, observe, enforce; default off), APP_CERT_SHA256 (Play's app signing certificate digests).

import { AuthError, bearer, isEmulated, verifyIdToken } from './auth.js'
import { ATTEST_EVERY_MS, attestNonce, deviceOf, judgeIntegrity } from './device.js'
import { fits, quotaZone, statusOf, validZone } from './quota.js'

const PLAY = 'https://androidpublisher.googleapis.com/androidpublisher/v3'
const TOKEN_URL = 'https://oauth2.googleapis.com/token'
const IDENTITY = 'https://identitytoolkit.googleapis.com/v1/projects'
const PLUS_SEATS = 5
const FRESH_AUTH_S = 10 * 60
// The same 30 days the privacy policy, the deletion page and the app promise.
export const DELETION_GRACE_MS = 30 * 86_400_000
const TRANSFER_COOLDOWN_MS = 30 * 86_400_000
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

/** What the account's verified purchases grant this installation, ready to store. */
async function accountSnapshot(env, account, uid, installId, now, extra = {}) {
  const { results: rows } = await env.DB.prepare('SELECT * FROM purchases WHERE account_id = ? ORDER BY paid_through DESC').bind(account.id).all()
  const best = rows.find((r) => r.tier === 'plus' && r.paid_through > now)
  const seat = await seated(env, account, installId)
  const shown = best ?? rows[0]
  const tier = best && seat ? 'plus' : 'free'
  return signed(env, {
    tier,
    state: best && !seat ? 'too_many_devices' : (shown?.state ?? 'none'),
    productId: shown?.product_id ?? null,
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

/** Plus here and now: a verified, paid-through purchase, on one of the account's seats. A sixth phone is Free. */
const hasPlus = async (env, account, installId, now) =>
  Boolean(await env.DB.prepare("SELECT 1 FROM purchases WHERE account_id = ? AND tier = 'plus' AND paid_through > ? LIMIT 1").bind(account.id, now).first()) &&
  (await seated(env, account, installId))

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
  if (!text(purchaseToken, 4096) || !isId(installId)) return json(400, { error: 'bad_request' })
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
  if ((env.INTEGRITY ?? 'off') === 'off') return json(404, { error: 'not_found' })
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
       integrity = COALESCE(?1, integrity), integrity_at = CASE WHEN ?1 IS NULL THEN integrity_at ELSE ?2 END
     WHERE id = ?3 AND attest_nonce = ?4`,
  ).bind(integrity, now, installId, nonce).run()
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
  const token = note.subscriptionNotification?.purchaseToken
  // Test and one-time notifications: acknowledged, nothing to do.
  if (!token || note.packageName !== env.PACKAGE_NAME) return new Response(null, { status: 204 })
  const key = await tokenKey(token)
  const known = await env.DB.prepare('SELECT * FROM purchases WHERE token_hash = ?').bind(key).first()
  // Nothing bound yet: the app's own verify will bind it. A message older than what we hold says nothing new.
  const at = Number(note.eventTimeMillis ?? 0)
  if (!known || at < known.event_at) return new Response(null, { status: 204 })
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
  ])
}

const ROUTES = {
  '/v1/account/sync': sync,
  '/v1/account/delete': requestDeletion,
  '/v1/account/delete/cancel': cancelDeletion,
  '/v1/installations/remove': removeInstallation,
  '/v1/verify': verify,
  '/v1/purchases/transfer': transfer,
  '/v1/quota/reserve': reserve,
  '/v1/quota/release': release,
  '/v1/quota/import': importLedger,
  '/v1/device/attest': attest,
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


