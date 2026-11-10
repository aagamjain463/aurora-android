// The installation behind a request, apart from whoever is signed in on it (docs/ANTI_ABUSE.md).
//
// An install id is a random string the app made. On its own it proves nothing: anyone can send any id, and a
// reinstall makes a new one. What this adds:
// - A device key. The app makes an ECDSA P-256 key in the Android Keystore and signs each request with it. The
//   first key an id is seen with is bound to it, and from then on that id only counts with that key's signature,
//   so nobody else can spend, or be charged to, an installation that isn't theirs.
// - Play Integrity. Google's word on whether this is Aurora from Play on a genuine device, checked now and then,
//   never for every Shield.
// Neither one survives a factory reset, cleared data or a reinstall. That limit is stated, not hidden.

const MESSAGE = 'aurora-device-v1'
const NONCE_TTL_MS = 15 * 60_000
export const ATTEST_EVERY_MS = 7 * 86_400_000
const VERDICT_FRESH_MS = 10 * 60_000

const utf8 = (text) => new TextEncoder().encode(text)
const unb64 = (text) => Uint8Array.from(atob(text.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0))
const hex = (bytes) => [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, '0')).join('')
export const sha256Hex = async (text) => hex(await crypto.subtle.digest('SHA-256', utf8(text)))

/** What the app signs: the route, the sign-in it rides on and the exact body. A captured proof is good for nothing else. */
export const proofMessage = async (path, idToken, raw) => `${MESSAGE}\n${path}\n${await sha256Hex(idToken ?? '')}\n${await sha256Hex(raw)}`

/** Android Keystore signs in DER; Web Crypto wants r and s side by side. Null if it isn't a P-256 signature. */
export function derToRaw(der) {
  if (der.length < 8 || der[0] !== 0x30) return null
  let i = der[1] & 0x80 ? 2 + (der[1] & 0x7f) : 2
  const out = new Uint8Array(64)
  for (let part = 0; part < 2; part++) {
    if (der[i] !== 0x02) return null
    const len = der[i + 1]
    let v = der.slice(i + 2, i + 2 + len)
    i += 2 + len
    while (v.length > 32 && v[0] === 0) v = v.slice(1)
    if (v.length === 0 || v.length > 32) return null
    out.set(v, part * 32 + 32 - v.length)
  }
  return i === der.length ? out : null
}

export async function verifyProof(publicKey, message, signature) {
  if (typeof publicKey !== 'string' || publicKey.length > 512 || typeof signature !== 'string' || signature.length > 256) return false
  try {
    const key = await crypto.subtle.importKey('spki', unb64(publicKey), { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify'])
    const raw = derToRaw(unb64(signature))
    return raw !== null && (await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, raw, utf8(message)))
  } catch {
    return false
  }
}

/**
 * The device row for [installId], registering it on first sight. [proved]: this request was signed by the id's
 * key. Null when the id has a key and this request doesn't carry its signature: the caller refuses it.
 */
export async function deviceOf(env, request, installId, raw, now) {
  const before = await env.DB.prepare('SELECT public_key FROM devices WHERE id = ?').bind(installId).first()
  const candidate = before?.public_key ?? request.headers.get('x-aurora-device-key')
  const signature = request.headers.get('x-aurora-device-signature')
  const token = /^Bearer (.+)$/.exec(request.headers.get('authorization') ?? '')?.[1]
  const proved = Boolean(candidate && signature) && (await verifyProof(candidate, await proofMessage(new URL(request.url).pathname, token, raw), signature))
  // Binds only an unbound id, in one statement: of two first requests racing with different keys, one wins.
  const row = await env.DB.prepare(
    `INSERT INTO devices (id, public_key, key_bound_at, created_at, last_seen_at) VALUES (?1, ?2, CASE WHEN ?2 IS NULL THEN NULL ELSE ?3 END, ?3, ?3)
     ON CONFLICT (id) DO UPDATE SET last_seen_at = ?3, public_key = COALESCE(devices.public_key, ?2),
       key_bound_at = COALESCE(devices.key_bound_at, CASE WHEN ?2 IS NULL THEN NULL ELSE ?3 END)
     RETURNING *`,
  ).bind(installId, proved ? candidate : null, now).first()
  if (row.public_key && !(proved && row.public_key === candidate)) return null
  return { ...row, proved }
}

/** A fresh one-use nonce when this device is due a Play Integrity check, else null. 33 bytes: base64 with no padding, as Play wants. */
export async function attestNonce(env, device, now) {
  if ((env.INTEGRITY ?? 'off') === 'off' || !device.proved) return null
  const due = device.integrity === 'failed' || !device.integrity_at || now - device.integrity_at > ATTEST_EVERY_MS
  if (!due) return null
  return issueNonce(env, device, now)
}

/** The device's live nonce, or a new one. Also asked for outside the weekly cycle, by a trial claim. */
export async function issueNonce(env, device, now) {
  if (device.attest_nonce && device.attest_nonce_expires_at > now) return device.attest_nonce
  const nonce = btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(33)))).replace(/\+/g, '-').replace(/\//g, '_')
  await env.DB.prepare('UPDATE devices SET attest_nonce = ?, attest_nonce_expires_at = ? WHERE id = ?').bind(nonce, now + NONCE_TTL_MS, device.id).run()
  return nonce
}

/**
 * Google's decoded verdict, judged: 'ok', 'basic' or 'failed'. Null when it wasn't made for this request (another
 * app, another nonce, or old), which says nothing about the device either way.
 * - ok: Aurora as Play signed it, on a device that meets device integrity.
 * - failed: a modified or unknown build of Aurora, or a device that fails even basic integrity.
 * - basic: anything between, such as a genuine device with an unlocked bootloader. Never blocked.
 */
/** Play's word that the Google account on the device got Aurora from Play. */
export const licensedOf = (verdict) => verdict?.accountDetails?.appLicensingVerdict === 'LICENSED'

export function judgeIntegrity(verdict, env, nonce, now) {
  const request = verdict?.requestDetails
  if (!request || request.requestPackageName !== env.PACKAGE_NAME || request.nonce !== nonce) return null
  if (!(Math.abs(now - Number(request.timestampMillis)) <= VERDICT_FRESH_MS)) return null
  const app = verdict.appIntegrity ?? {}
  const labels = verdict.deviceIntegrity?.deviceRecognitionVerdict ?? []
  const certs = (env.APP_CERT_SHA256 ?? '').split(',').map((s) => s.trim()).filter(Boolean)
  const ourBuild = app.appRecognitionVerdict === 'PLAY_RECOGNIZED' && app.packageName === env.PACKAGE_NAME &&
    (certs.length === 0 || (app.certificateSha256Digest ?? []).some((d) => certs.includes(d)))
  if (app.appRecognitionVerdict === 'UNRECOGNIZED_VERSION' || labels.length === 0) return 'failed'
  if (ourBuild && (labels.includes('MEETS_DEVICE_INTEGRITY') || labels.includes('MEETS_STRONG_INTEGRITY'))) return 'ok'
  return 'basic'
}
