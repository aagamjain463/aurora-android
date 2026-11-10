// Apple App Attest: proof that a request comes from the genuine Aurora app on a genuine Apple device
// (developer.apple.com/documentation/devicecheck/validating-apps-that-connect-to-your-server, read 2026-11-10).
//
// Used where a one-time grant is at stake: the trial claim. App Attest is not a device identity and says nothing about
// whether this device had a trial before; DeviceCheck answers that. The app makes a fresh key and attests it over the
// server's one-use nonce, so each claim carries its own attestation and no key or counter is stored here.
// ponytail: one attestation per claim, no assertions; add stored keys + assertions if App Attest guards frequent calls.

import { AppleInvalid, assertIssued, children, content, isCa, parseCertificate, tlv } from './apple.js'

// Apple App Attestation Root CA, from apple.com/certificateauthority (fetched 2026-11-10). DER SHA-256
// 1cb9823b a28ba6ad 2d33a006 941de2ae 4f513ef1 d4e831b9 f7e0fa7b 6242c932, pinned by test/appattest.test.js.
const ROOT = 'MIICITCCAaegAwIBAgIQC/O+DvHN0uD7jG5yH2IXmDAKBggqhkjOPQQDAzBSMSYwJAYDVQQDDB1BcHBsZSBBcHAgQXR0ZXN0YXRpb24gUm9vdCBDQTETMBEGA1UECgwKQXBwbGUgSW5jLjETMBEGA1UECAwKQ2FsaWZvcm5pYTAeFw0yMDAzMTgxODMyNTNaFw00NTAzMTUwMDAwMDBaMFIxJjAkBgNVBAMMHUFwcGxlIEFwcCBBdHRlc3RhdGlvbiBSb290IENBMRMwEQYDVQQKDApBcHBsZSBJbmMuMRMwEQYDVQQIDApDYWxpZm9ybmlhMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAERTHhmLW07ATaFQIEVwTtT4dyctdhNbJhFs/Ii2FdCgAHGbpphY3+d8qjuDngIN3WVhQUBHAoMeQ/cLiP1sOUtgjqK9auYen1mMEvRq9Sk3Jm5X8U62H+xTD3FE9TgS41o0IwQDAPBgNVHRMBAf8EBTADAQH/MB0GA1UdDgQWBBSskRBTM72+aEH/pwyp5frq5eWKoTAOBgNVHQ8BAf8EBAMCAQYwCgYIKoZIzj0EAwMDaAAwZQIwQgFGnByvsiVbpTKwSga0kP0e8EeDS4+sQmTvb7vn53O5+FRXgeLhpJ06ysC5PrOyAjEAp5U4xDgEgllF7En3VcE3iexZZtKeYnpqtijVoyFraWVIyd/dganmrduC1bmTBGwD'

const unb64 = (text) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0))
const utf8 = (text) => new TextEncoder().encode(text)
const sha256 = async (bytes) => new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))
const same = (a, b) => a.length === b.length && a.every((v, i) => v === b[i])
const NONCE_OID = '2a864886f763640802' // 1.2.840.113635.100.8.2
const SKEW_MS = 60_000

// Not a Worker setting on purpose: no configuration or request can change what we trust. Only tests replace it.
export const appAttestTrust = { root: unb64(ROOT) }

// 'appattest' padded with zeros (production) or 'appattestdevelop' (development-signed builds).
const AAGUID = {
  production: Uint8Array.from([...utf8('appattest'), 0, 0, 0, 0, 0, 0, 0]),
  development: utf8('appattestdevelop'),
}

/** Just enough CBOR (RFC 8949) for an attestation object: unsigned ints, byte and text strings, arrays, maps. */
export function cbor(bytes) {
  let at = 0
  const take = (n) => {
    if (at + n > bytes.length) throw new AppleInvalid()
    const out = bytes.subarray(at, at + n)
    at += n
    return out
  }
  const item = (depth) => {
    if (depth > 8) throw new AppleInvalid()
    const head = take(1)[0]
    const major = head >> 5
    const info = head & 31
    let n = info
    if (info === 24) n = take(1)[0]
    else if (info === 25) n = new DataView(take(2).slice().buffer).getUint16(0)
    else if (info === 26) n = new DataView(take(4).slice().buffer).getUint32(0)
    else if (info > 26) throw new AppleInvalid()
    if (major === 0) return n
    if (major === 2) return take(n)
    if (major === 3) return new TextDecoder().decode(take(n))
    if (major === 4) return Array.from({ length: n }, () => item(depth + 1))
    if (major === 5) {
      const map = new Map()
      for (let i = 0; i < n; i++) map.set(item(depth + 1), item(depth + 1))
      return map
    }
    throw new AppleInvalid()
  }
  const value = item(0)
  if (at !== bytes.length) throw new AppleInvalid()
  return value
}

/** The nonce Apple wrote into the credential certificate: SEQUENCE { [1] { OCTET STRING } }. */
function certificateNonce(value) {
  const [tagged] = children(value, tlv(value, 0))
  if (tagged?.tag !== 0xa1) throw new AppleInvalid()
  const [octets] = children(value, tagged)
  if (octets?.tag !== 0x04) throw new AppleInvalid()
  return content(value, octets)
}

/**
 * Verifies an App Attest attestation of key [keyId] (base64, as DCAppAttestService returns it) over [challenge] (the
 * server's nonce; the app's clientDataHash is SHA-256 of its UTF-8 bytes). Returns { keyId, receipt } or throws
 * AppleInvalid. [environment] is 'production' or 'development'; [appId] is "<Team ID>.<bundle id>".
 */
export async function verifyAttestation({ attestation, keyId, challenge, appId, environment = 'production', now = Date.now() }) {
  try {
    const object = cbor(unb64(attestation))
    const statement = object.get('attStmt')
    const authData = object.get('authData')
    if (object.get('fmt') !== 'apple-appattest' || !(statement instanceof Map) || !(authData instanceof Uint8Array)) throw new AppleInvalid()
    const x5c = statement.get('x5c')
    if (!Array.isArray(x5c) || x5c.length !== 2 || !x5c.every((c) => c instanceof Uint8Array)) throw new AppleInvalid()

    // 1. The chain runs to Apple's App Attestation root; x5c never brings its own root.
    const root = parseCertificate(appAttestTrust.root)
    const intermediate = parseCertificate(x5c[1])
    const credential = parseCertificate(x5c[0])
    await assertIssued(intermediate, root)
    await assertIssued(credential, intermediate)
    for (const c of [root, intermediate, credential]) if (now < c.notBefore - SKEW_MS || now > c.notAfter + SKEW_MS) throw new AppleInvalid()
    if (!isCa(intermediate) || isCa(credential) || credential.curve.namedCurve !== 'P-256') throw new AppleInvalid()

    // 2-4. nonce = SHA-256(authData || SHA-256(challenge)) is what Apple certified.
    const clientDataHash = await sha256(utf8(challenge))
    const nonce = await sha256(Uint8Array.from([...authData, ...clientDataHash]))
    const extension = credential.extensions.get(NONCE_OID)
    if (!extension || !same(certificateNonce(extension.value), nonce)) throw new AppleInvalid()

    // 5. The key id is SHA-256 of the credential's public key (the uncompressed point).
    const key = await crypto.subtle.importKey('spki', credential.spki, { name: 'ECDSA', namedCurve: 'P-256' }, true, ['verify'])
    const point = new Uint8Array(await crypto.subtle.exportKey('raw', key))
    const id = unb64(keyId)
    if (!same(await sha256(point), id)) throw new AppleInvalid()

    // 6-9. authenticator data: rpIdHash(32) flags(1) counter(4) aaguid(16) credIdLen(2) credId.
    if (authData.length < 55) throw new AppleInvalid()
    if (!same(authData.subarray(0, 32), await sha256(utf8(appId)))) throw new AppleInvalid()
    const counter = new DataView(authData.slice(33, 37).buffer).getUint32(0)
    if (counter !== 0) throw new AppleInvalid()
    const aaguid = AAGUID[environment === 'development' ? 'development' : 'production']
    if (!same(authData.subarray(37, 53), aaguid)) throw new AppleInvalid()
    const length = (authData[53] << 8) | authData[54]
    if (!same(authData.subarray(55, 55 + length), id)) throw new AppleInvalid()

    return { keyId, receipt: statement.get('receipt') ?? null }
  } catch {
    throw new AppleInvalid()
  }
}
