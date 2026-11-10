// The App Store side of Aurora Plus, with Web Crypto and no dependencies (docs/APP_STORE_BILLING.md).
//
// Decoding a JWS proves nothing, so nothing here returns a payload before its signature checks out to Apple's root.
// Even then a signed transaction the app sends is only a pointer: what grants Plus is the App Store Server API's
// current answer, fetched here over TLS with our own key, and itself verified.

const PRODUCTION = 'https://api.storekit.apple.com'
const SANDBOX = 'https://api.storekit-sandbox.apple.com'
const AUTO_RENEWABLE = 'Auto-Renewable Subscription'
const SKEW_MS = 60_000

// Apple Root CA - G3, from apple.com/certificateauthority. SHA-256 63343abf...653e9179, pinned by test/apple.test.js.
const APPLE_ROOT_G3 = 'MIICQzCCAcmgAwIBAgIILcX8iNLFS5UwCgYIKoZIzj0EAwMwZzEbMBkGA1UEAwwSQXBwbGUgUm9vdCBDQSAtIEczMSYwJAYDVQQLDB1BcHBsZSBDZXJ0aWZpY2F0aW9uIEF1dGhvcml0eTETMBEGA1UECgwKQXBwbGUgSW5jLjELMAkGA1UEBhMCVVMwHhcNMTQwNDMwMTgxOTA2WhcNMzkwNDMwMTgxOTA2WjBnMRswGQYDVQQDDBJBcHBsZSBSb290IENBIC0gRzMxJjAkBgNVBAsMHUFwcGxlIENlcnRpZmljYXRpb24gQXV0aG9yaXR5MRMwEQYDVQQKDApBcHBsZSBJbmMuMQswCQYDVQQGEwJVUzB2MBAGByqGSM49AgEGBSuBBAAiA2IABJjpLz1AcqTtkyJygRMc3RCV8cWjTnHcFBbZDuWmBSp3ZHtfTjjTuxxEtX/1H7YyYl3J6YRbTzBPEVoA/VhYDKX1DyxNB0cTddqXl5dvMVztK517IDvYuVTZXpmkOlEKMaNCMEAwHQYDVR0OBBYEFLuw3qFYM4iapIqZ3r6966/ayySrMA8GA1UdEwEB/wQFMAMBAf8wDgYDVR0PAQH/BAQDAgEGMAoGCCqGSM49BAMDA2gAMGUCMQCD6cHEFl4aXTQY2e3v9GwOAEZLuN+yRhHFD/3meoyhpmvOwgPUnPWTxnS4at+qIxUCMG1mihDK1A3UT82NQz60imOlM27jbdoXt2QfyFMm+YhidDkLF1vLUagM6BgD56KyKA=='

const unb64 = (text) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0))
const unb64url = (text) => unb64(text.replace(/-/g, '+').replace(/_/g, '/'))
const b64url = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
const utf8 = (text) => new TextEncoder().encode(text)
const hex = (bytes) => [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, '0')).join('')

// Not a Worker setting on purpose: no configuration or request can change what we trust. Only tests replace it.
export const appleTrust = { root: unb64(APPLE_ROOT_G3) }

export class AppleInvalid extends Error {}

// ---- X.509, as far as Apple's chain needs it -----------------------------------------------------------------

// Object identifiers, as the hex of their DER content.
const OID = {
  ecPublicKey: '2a8648ce3d0201',
  ecdsaSha256: '2a8648ce3d040302',
  ecdsaSha384: '2a8648ce3d040303',
  p256: '2a8648ce3d030107',
  p384: '2b81040022',
  basicConstraints: '551d13',
  keyUsage: '551d0f',
  appleIntermediate: '2a864886f76364060201', // 1.2.840.113635.100.6.2.1
  appleReceiptSigning: '2a864886f76364060b01', // 1.2.840.113635.100.6.11.1
}
const HASHES = { [OID.ecdsaSha256]: 'SHA-256', [OID.ecdsaSha384]: 'SHA-384' }
const CURVES = { [OID.p256]: { namedCurve: 'P-256', size: 32 }, [OID.p384]: { namedCurve: 'P-384', size: 48 } }

/** One DER element at [at]. Bounds are checked here, so a hostile length can't read past its parent. */
export function tlv(der, at, limit = der.length) {
  if (at + 2 > limit || (der[at] & 0x1f) === 0x1f) throw new AppleInvalid()
  let length = der[at + 1]
  let start = at + 2
  if (length & 0x80) {
    const count = length & 0x7f
    if (count < 1 || count > 3 || start + count > limit) throw new AppleInvalid()
    length = 0
    for (let i = 0; i < count; i++) length = length * 256 + der[start++]
  }
  if (start + length > limit) throw new AppleInvalid()
  return { tag: der[at], at, start, end: start + length }
}

export function children(der, node) {
  const out = []
  for (let at = node.start; at < node.end;) {
    const child = tlv(der, at, node.end)
    out.push(child)
    at = child.end
  }
  return out
}

export const content = (der, node) => der.subarray(node.start, node.end)
const element = (der, node) => der.subarray(node.at, node.end)

function timeOf(der, node) {
  const value = new TextDecoder().decode(content(der, node))
  const match = (node.tag === 0x17 ? /^(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})Z$/ : /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})Z$/).exec(value)
  if (!match || (node.tag !== 0x17 && node.tag !== 0x18)) throw new AppleInvalid()
  const year = node.tag === 0x17 ? Number(match[1]) + (Number(match[1]) < 50 ? 2000 : 1900) : Number(match[1])
  return Date.UTC(year, match[2] - 1, match[3], match[4], match[5], match[6])
}

export function parseCertificate(der) {
  const certificate = tlv(der, 0)
  if (certificate.tag !== 0x30 || certificate.end !== der.length) throw new AppleInvalid()
  const [tbs, algorithm, signature, ...extra] = children(der, certificate)
  if (extra.length || signature?.tag !== 0x03 || der[signature.start] !== 0) throw new AppleInvalid()
  const [version, , innerAlgorithm, issuer, validity, subject, spki, extensions] = children(der, tbs)
  if (version?.tag !== 0xa0 || extensions?.tag !== 0xa3) throw new AppleInvalid()
  if (hex(element(der, innerAlgorithm)) !== hex(element(der, algorithm))) throw new AppleInvalid()
  const hash = HASHES[hex(content(der, children(der, algorithm)[0]))]
  const [keyType, curve] = children(der, children(der, spki)[0])
  const curveInfo = CURVES[hex(content(der, curve))]
  if (!hash || !curveInfo || hex(content(der, keyType)) !== OID.ecPublicKey) throw new AppleInvalid()
  const [notBefore, notAfter] = children(der, validity)
  const parsedExtensions = new Map()
  for (const extension of children(der, children(der, extensions)[0])) {
    const parts = children(der, extension)
    const value = parts.at(-1)
    parsedExtensions.set(hex(content(der, parts[0])), {
      critical: parts.length === 3 && der[parts[1].start] !== 0,
      value: content(der, value),
    })
  }
  return {
    tbs: element(der, tbs),
    issuer: hex(element(der, issuer)),
    subject: hex(element(der, subject)),
    notBefore: timeOf(der, notBefore),
    notAfter: timeOf(der, notAfter),
    spki: element(der, spki),
    curve: curveInfo,
    hash,
    signature: der.subarray(signature.start + 1, signature.end),
    extensions: parsedExtensions,
  }
}

export function isCa(certificate) {
  const value = certificate.extensions.get(OID.basicConstraints)?.value
  if (!value) return false
  const [flag] = children(value, tlv(value, 0))
  return flag?.tag === 0x01 && value[flag.start] !== 0
}

const importKey = (certificate) => crypto.subtle.importKey('spki', certificate.spki, { name: 'ECDSA', namedCurve: certificate.curve.namedCurve }, false, ['verify'])

/** X.509 carries ECDSA signatures as a DER pair of integers. Web Crypto wants them as fixed-width r || s. */
function rawSignature(der, size) {
  const pair = tlv(der, 0)
  const [r, s, ...extra] = children(der, pair)
  if (pair.tag !== 0x30 || pair.end !== der.length || extra.length || r?.tag !== 0x02 || s?.tag !== 0x02) throw new AppleInvalid()
  const out = new Uint8Array(size * 2)
  ;[r, s].forEach((integer, i) => {
    let value = content(der, integer)
    // Exactly one form per number: a zero byte only where the next byte's top bit would otherwise read as negative.
    if (value.length === 0 || value[0] & 0x80 || (value[0] === 0 && value.length > 1 && !(value[1] & 0x80))) throw new AppleInvalid()
    if (value[0] === 0 && value.length > 1) value = value.subarray(1)
    if (value.length > size) throw new AppleInvalid()
    out.set(value, (i + 1) * size - value.length)
  })
  return out
}

export async function assertIssued(child, parent) {
  if (child.issuer !== parent.subject) throw new AppleInvalid()
  const ok = await crypto.subtle.verify({ name: 'ECDSA', hash: child.hash }, await importKey(parent), rawSignature(child.signature, parent.curve.size), child.tbs)
  if (!ok) throw new AppleInvalid()
}

function assertUsable(certificate, at) {
  if (at < certificate.notBefore - SKEW_MS || at > certificate.notAfter + SKEW_MS) throw new AppleInvalid()
  for (const [oid, { critical }] of certificate.extensions) {
    if (critical && oid !== OID.basicConstraints && oid !== OID.keyUsage) throw new AppleInvalid()
  }
}

/**
 * The signing key of an x5c chain that runs to the pinned root at the instant [at]. Same rules as Apple's own library,
 * minus its OCSP lookup: a signature here only ever points at the Server API, which is what decides (see above).
 * x5c[2] is never read: the root is ours, not the sender's.
 */
export async function verifyChain(x5c, at) {
  if (!Array.isArray(x5c) || x5c.length !== 3) throw new AppleInvalid()
  const root = parseCertificate(appleTrust.root)
  const intermediate = parseCertificate(unb64(x5c[1]))
  const leaf = parseCertificate(unb64(x5c[0]))
  await assertIssued(intermediate, root)
  await assertIssued(leaf, intermediate)
  for (const certificate of [root, intermediate, leaf]) assertUsable(certificate, at)
  if (!isCa(intermediate) || isCa(leaf)) throw new AppleInvalid()
  if (!intermediate.extensions.has(OID.appleIntermediate) || !leaf.extensions.has(OID.appleReceiptSigning)) throw new AppleInvalid()
  if (leaf.curve.namedCurve !== 'P-256') throw new AppleInvalid()
  return importKey(leaf)
}

/** The payload of a JWS Apple really signed. Anything else throws AppleInvalid. */
export async function verifySigned(jws, now = Date.now()) {
  try {
    const parts = typeof jws === 'string' ? jws.split('.') : []
    if (parts.length !== 3) throw new AppleInvalid()
    const decode = (part) => JSON.parse(new TextDecoder().decode(unb64url(part)))
    const header = decode(parts[0])
    const payload = decode(parts[1])
    if (header?.alg !== 'ES256' || !payload || typeof payload !== 'object') throw new AppleInvalid()
    // Certificates are judged when Apple signed, so a device's older transaction outlives its leaf certificate.
    if (!Number.isFinite(payload.signedDate) || payload.signedDate > now + SKEW_MS) throw new AppleInvalid()
    const key = await verifyChain(header.x5c, payload.signedDate)
    const signature = unb64url(parts[2])
    if (signature.length !== 64) throw new AppleInvalid()
    const ok = await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, signature, utf8(`${parts[0]}.${parts[1]}`))
    if (!ok) throw new AppleInvalid()
    return payload
  } catch {
    throw new AppleInvalid()
  }
}

// ---- App Store Server API -----------------------------------------------------------------------------------

async function apiToken(env) {
  const pkcs8 = unb64(env.APPLE_PRIVATE_KEY.replace(/-----[A-Z ]+-----|\s/g, ''))
  const key = await crypto.subtle.importKey('pkcs8', pkcs8, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign'])
  const now = Math.floor(Date.now() / 1000)
  const encode = (value) => b64url(utf8(JSON.stringify(value)))
  const unsigned = `${encode({ alg: 'ES256', kid: env.APPLE_KEY_ID, typ: 'JWT' })}.${encode({ iss: env.APPLE_ISSUER_ID, iat: now, exp: now + 1800, aud: 'appstoreconnect-v1', bid: env.APPLE_BUNDLE_ID })}`
  return `${unsigned}.${b64url(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, key, utf8(unsigned)))}`
}

export const sandboxAllowed = (env) => env.APPLE_ALLOW_SANDBOX === '1'
export const environmentAllowed = (env, environment) => environment === 'Production' || (environment === 'Sandbox' && sandboxAllowed(env))

/** Use the environment in verified Apple evidence; never cross environments on a 404. */
export async function appleStatus(env, transactionId, fetcher, environment = 'Production') {
  if (!environmentAllowed(env, environment)) throw new Error('apple environment')
  const token = await apiToken(env)
  const host = environment === 'Sandbox' ? SANDBOX : PRODUCTION
  const res = await fetcher(`${host}/inApps/v1/subscriptions/${encodeURIComponent(transactionId)}`, { headers: { authorization: `Bearer ${token}` } })
  if (res.status === 404) return null
  if (!res.ok) throw new Error(`apple ${res.status}`)
  return { environment, body: await res.json() }
}

/**
 * Apple's own signed record of one transaction (Get Transaction Info), verified: a one-time purchase has no
 * subscription status to read. Null when Apple doesn't know it. Throws on an outage or anything unverifiable.
 */
export async function appleTransaction(env, transactionId, fetcher, environment = 'Production') {
  if (!environmentAllowed(env, environment)) throw new Error('apple environment')
  const host = environment === 'Sandbox' ? SANDBOX : PRODUCTION
  const res = await fetcher(`${host}/inApps/v1/transactions/${encodeURIComponent(transactionId)}`, { headers: { authorization: `Bearer ${await apiToken(env)}` } })
  if (res.status === 404) return null
  if (!res.ok) throw new Error(`apple ${res.status}`)
  const tx = await verifySigned((await res.json())?.signedTransactionInfo)
  return tx.environment === environment ? tx : null
}

const PLUS_STATUS = new Set([1, 4]) // 1 active, 4 billing grace period.
const STATE = { 1: 'active', 2: 'expired', 3: 'on_hold', 4: 'in_grace_period', 5: 'revoked' }

function entitlementOf(item, tx, renewal, products, now) {
  const paidThrough = item.status === 4 ? Math.max(tx.expiresDate, renewal.gracePeriodExpiresDate ?? 0) : tx.expiresDate
  const revoked = Number.isFinite(tx.revocationDate)
  const plus = tx.inAppOwnershipType === 'PURCHASED' && PLUS_STATUS.has(item.status) && !revoked && tx.isUpgraded !== true && paidThrough > now
  const renewing = renewal.autoRenewStatus === 1
  let state = STATE[item.status] ?? 'unknown'
  if (revoked) state = 'revoked'
  else if (item.status === 1 && !renewing) state = 'canceled' // Paid through its end, and won't renew: Google's word for the same thing.
  return {
    known: products.includes(tx.productId),
    tier: plus ? 'plus' : 'free',
    state,
    productId: tx.productId,
    paidThrough,
    autoRenewing: plus && renewing,
    appAccountToken: typeof tx.appAccountToken === 'string' && tx.appAccountToken ? tx.appAccountToken.toLowerCase() : null,
    ownership: tx.inAppOwnershipType,
  }
}

/**
 * What Apple's answer says about exactly [originalTransactionId], in the same shape entitlementFrom gives for Google.
 * The answer covers every subscription of that customer, so anything else in it is ignored, never chosen by position.
 * Only Apple's signed fields count. A response that isn't what Apple documents throws: an outage, not a verdict.
 */
export async function appleEntitlement(answer, originalTransactionId, { bundleId, productIds }, now = Date.now()) {
  const unknown = { known: false }
  if (!answer) return unknown
  const { body, environment } = answer
  if (!Array.isArray(body?.data)) throw new Error('apple response')
  if (body.bundleId !== bundleId || body.environment !== environment) return unknown
  const items = body.data.flatMap((group) => (Array.isArray(group?.lastTransactions) ? group.lastTransactions : [])).filter((item) => item?.originalTransactionId === originalTransactionId)
  let best = unknown
  for (const item of items) {
    const tx = await verifySigned(item.signedTransactionInfo, now)
    const renewal = item.signedRenewalInfo ? await verifySigned(item.signedRenewalInfo, now) : {}
    const consistent = tx.originalTransactionId === originalTransactionId && tx.bundleId === bundleId && tx.environment === environment && tx.type === AUTO_RENEWABLE && Number.isFinite(tx.expiresDate)
      && (!item.signedRenewalInfo || (renewal.originalTransactionId === originalTransactionId && renewal.environment === environment))
    if (!consistent) continue
    const entitlement = { ...entitlementOf(item, tx, renewal, productIds, now), environment }
    if (!entitlement.known) continue
    const better = !best.known || (entitlement.tier === 'plus' && best.tier !== 'plus') || (entitlement.tier === best.tier && entitlement.paidThrough > best.paidThrough)
    if (better) best = entitlement
  }
  return best
}

/**
 * The appAccountToken a purchase by this Firebase user carries: its UID's SHA-256 shaped as a UUID, the App Store's
 * version of Google's obfuscatedAccountId. The server derives it from the verified token and never accepts it from a
 * client. Knowing someone's UID only lets you pay for their subscription.
 */
export async function appAccountToken(uid) {
  const bytes = new Uint8Array((await crypto.subtle.digest('SHA-256', utf8(uid))).slice(0, 16))
  bytes[6] = (bytes[6] & 0x0f) | 0x50
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const h = hex(bytes)
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}
