// A stand-in for Apple's App Attest service: a real P-384 root and intermediate, a P-256 credential certificate carrying
// the nonce the way Apple's does, and the CBOR attestation object DCAppAttestService returns. Passing against this
// proves the checks, not that Apple sends these exact bytes: that takes a real device (MONETIZATION_PLAN.md, 11).
import { createHash, generateKeyPairSync } from 'node:crypto'
import { appAttestTrust } from '../src/appattest.js'
import { certificate } from './apple-fixture.js'

const sha = (b) => createHash('sha256').update(b).digest()
const der = (tag, body) => Buffer.concat([Buffer.from([tag, ...(body.length < 128 ? [body.length] : [0x81, body.length])]), body])

/** CBOR for the four kinds an attestation object uses. */
export function cborEncode(v) {
  const head = (major, n) => (n < 24 ? Buffer.from([(major << 5) | n]) : n < 256 ? Buffer.from([(major << 5) | 24, n]) : n < 65536 ? Buffer.from([(major << 5) | 25, n >> 8, n & 255]) : Buffer.from([(major << 5) | 26, n >>> 24, (n >> 16) & 255, (n >> 8) & 255, n & 255]))
  if (typeof v === 'number') return head(0, v)
  if (typeof v === 'string') return Buffer.concat([head(3, Buffer.byteLength(v)), Buffer.from(v)])
  if (Buffer.isBuffer(v)) return Buffer.concat([head(2, v.length), v])
  if (Array.isArray(v)) return Buffer.concat([head(4, v.length), ...v.map(cborEncode)])
  const entries = Object.entries(v)
  return Buffer.concat([head(5, entries.length), ...entries.flatMap(([k, x]) => [cborEncode(k), cborEncode(x)])])
}

const ec = (namedCurve) => generateKeyPairSync('ec', { namedCurve })
const root = ec('secp384r1')
const intermediate = ec('secp384r1')
export const attestRootDer = certificate({ subject: 'Test App Attestation Root', publicKey: root.publicKey, signingKey: root.privateKey, hash: 'sha384', ca: true })
const midDer = certificate({ subject: 'Test App Attestation CA', issuer: 'Test App Attestation Root', publicKey: intermediate.publicKey, signingKey: root.privateKey, hash: 'sha384', ca: true })
appAttestTrust.root = attestRootDer

export const AAGUID_PROD = Buffer.concat([Buffer.from('appattest'), Buffer.alloc(7)])
export const AAGUID_DEV = Buffer.from('appattestdevelop')

/**
 * What DCAppAttestService.attestKey(keyId, clientDataHash: SHA-256(challenge)) returns, base64, with its key id.
 * [over] bends one field: appId, counter, aaguid, keyId, nonce, signer (another intermediate), credentialId.
 */
export function attestation(challenge, appId, over = {}) {
  const key = ec('prime256v1')
  const jwk = key.publicKey.export({ format: 'jwk' })
  const point = Buffer.concat([Buffer.from([4]), Buffer.from(jwk.x, 'base64url'), Buffer.from(jwk.y, 'base64url')])
  const id = sha(point)
  const credentialId = over.credentialId ?? id
  const counter = Buffer.alloc(4)
  counter.writeUInt32BE(over.counter ?? 0)
  const length = Buffer.from([credentialId.length >> 8, credentialId.length & 255])
  const authData = Buffer.concat([sha(over.appId ?? appId), Buffer.from([0x40]), counter, over.aaguid ?? AAGUID_PROD, length, credentialId])
  const nonce = over.nonce ?? sha(Buffer.concat([authData, sha(Buffer.from(challenge))]))
  const signer = over.signer ?? { key: intermediate, der: midDer }
  const leafDer = certificate({
    subject: 'Test Credential', issuer: 'Test App Attestation CA', publicKey: key.publicKey, signingKey: signer.key.privateKey, hash: 'sha256',
    extra: [['2a864886f763640802', der(0x30, der(0xa1, der(0x04, nonce)))]],
  })
  const object = cborEncode({ fmt: 'apple-appattest', attStmt: { x5c: [leafDer, signer.der], receipt: Buffer.from('receipt') }, authData })
  return { attestation: object.toString('base64'), keyId: (over.keyId ?? id).toString('base64') }
}

/** An intermediate that chains to nothing we trust. */
export function strangerSigner() {
  const other = ec('secp384r1')
  return { key: other, der: certificate({ subject: 'Test App Attestation CA', issuer: 'Test App Attestation Root', publicKey: other.publicKey, signingKey: other.privateKey, hash: 'sha384', ca: true }) }
}
