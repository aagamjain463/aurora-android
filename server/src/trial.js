// The 14-day Aurora Plus trial: who may have one, and the device evidence behind it (docs/MONETIZATION_PLAN.md, 3.2).
//
// No store, no payment method, no subscription: the server grants it and the signed snapshot carries it, with
// paidThrough = the end and autoRenewing = false, so every client already ends it on time, offline too.

export const TRIAL_MS = 14 * 86_400_000
// An Android verdict must be this fresh to vouch for a trial: the attestation made for this claim.
export const TRIAL_VERDICT_FRESH_MS = 15 * 60_000
// Claims of deleted accounts are dropped this long after they ended. NOT APPROVED: a placeholder pending the founder's
// and counsel's decision (docs/PRIVACY_TRIAL_RETENTION_REVIEW.md). TRIAL stays "off" until it is decided; changing the
// period is this one constant.
export const TRIAL_RETENTION_MS = 3 * 365 * 86_400_000

const utf8 = (text) => new TextEncoder().encode(text)
const b64url = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
const unb64 = (text) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0))

/**
 * One address, however it is spelled: case, a +tag, and Gmail's dots don't make a new person. Applied to the
 * address Firebase verified, never to one the client typed.
 */
export function normalizeEmail(email) {
  const e = email.trim().toLowerCase()
  const at = e.lastIndexOf('@')
  if (at < 1) return e
  let local = e.slice(0, at).split('+')[0]
  let domain = e.slice(at + 1)
  if (domain === 'googlemail.com') domain = 'gmail.com'
  if (domain === 'gmail.com') local = local.replace(/\./g, '')
  return `${local}@${domain}`
}

/** A keyed hash: without TRIAL_PEPPER the table says nothing about who claimed. */
export async function keyed(env, value) {
  const key = await crypto.subtle.importKey('raw', utf8(env.TRIAL_PEPPER), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'])
  return b64url(await crypto.subtle.sign('HMAC', key, utf8(value)))
}

/** Whether Play's verdict, judged and stored by attest, vouches for a trial on this installation right now. */
export function androidVouches(device, now) {
  const fresh = Number.isFinite(device.integrity_at) && now - device.integrity_at <= TRIAL_VERDICT_FRESH_MS
  return { fresh, ok: fresh && device.integrity === 'ok' && device.licensed === 1 }
}

// ---- Apple DeviceCheck ------------------------------------------------------------------------------------------
// Two bits per device and developer team, kept by Apple. bit0 = "an Aurora trial was granted on this device".
// Endpoints and fields: developer.apple.com/documentation/devicecheck/accessing-and-modifying-per-device-data (read
// 2026-11-10). Apple's page doesn't say how long bits survive an erase; we never claim they do.

const DEVICECHECK = { production: 'https://api.devicecheck.apple.com', development: 'https://api.development.devicecheck.apple.com' }

export class DeviceCheckInvalid extends Error {}

export const deviceCheckReady = (env) => Boolean(env.APPLE_TEAM_ID && env.DEVICECHECK_KEY_ID && env.DEVICECHECK_PRIVATE_KEY)

async function deviceCheckJwt(env) {
  const pkcs8 = unb64(env.DEVICECHECK_PRIVATE_KEY.replace(/-----[A-Z ]+-----|\s/g, ''))
  const key = await crypto.subtle.importKey('pkcs8', pkcs8, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign'])
  const encode = (value) => b64url(utf8(JSON.stringify(value)))
  const unsigned = `${encode({ alg: 'ES256', kid: env.DEVICECHECK_KEY_ID })}.${encode({ iss: env.APPLE_TEAM_ID, iat: Math.floor(Date.now() / 1000) })}`
  return `${unsigned}.${b64url(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, key, utf8(unsigned)))}`
}

async function deviceCheck(env, path, deviceToken, fetcher, extra = {}) {
  const host = env.DEVICECHECK_ENV === 'development' ? DEVICECHECK.development : DEVICECHECK.production
  const res = await fetcher(`${host}/v1/${path}`, {
    method: 'POST',
    headers: { authorization: `Bearer ${await deviceCheckJwt(env)}`, 'content-type': 'application/json' },
    body: JSON.stringify({ device_token: deviceToken, transaction_id: crypto.randomUUID(), timestamp: Date.now(), ...extra }),
  })
  // 400 is Apple saying the token is bad: not a genuine device, or not our app. Anything else unexpected is an outage.
  if (res.status === 400) throw new DeviceCheckInvalid()
  if (!res.ok) throw new Error(`devicecheck ${res.status}`)
  return res.text()
}

/** Whether Apple already holds "trial granted" for the device behind [deviceToken]. Throws when Apple can't say. */
export async function deviceCheckUsed(env, deviceToken, fetcher) {
  const text = await deviceCheck(env, 'query_two_bits', deviceToken, fetcher)
  // A device never written answers 200 "Bit State Not Found", not JSON.
  let bits
  try {
    bits = JSON.parse(text)
  } catch {
    return false
  }
  return bits?.bit0 === true
}

export const markDeviceCheck = (env, deviceToken, fetcher) => deviceCheck(env, 'update_two_bits', deviceToken, fetcher, { bit0: true })
