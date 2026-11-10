// App Attest attestation checks (src/appattest.js), each step of Apple's validation list, against a stand-in chain.
import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { AppleInvalid } from '../src/apple.js'
import { cbor, verifyAttestation } from '../src/appattest.js'
import { AAGUID_DEV, attestation, strangerSigner } from './appattest-fixture.js'

const APP = 'TEAM123456.com.aagamjain.Aurora'
const check = (a, over = {}) => verifyAttestation({ ...a, challenge: 'nonce-1', appId: APP, ...over })
const refused = (promise) => assert.rejects(promise, AppleInvalid)

test('the pinned root is Apple App Attestation Root CA as published (DER SHA-256)', () => {
  const source = readFileSync(new URL('../src/appattest.js', import.meta.url), 'utf8')
  const b64 = /const ROOT = '([^']+)'/.exec(source)[1]
  assert.equal(createHash('sha256').update(Buffer.from(b64, 'base64')).digest('hex'), '1cb9823ba28ba6ad2d33a006941de2ae4f513ef1d4e831b9f7e0fa7b6242c932')
})

test('a genuine attestation over the server nonce passes', async () => {
  const a = attestation('nonce-1', APP)
  const r = await check(a)
  assert.equal(r.keyId, a.keyId)
  assert.equal(Buffer.from(r.receipt).toString(), 'receipt')
})

test('every link is checked: nonce, app id, counter, environment, key id, credential id, chain', async () => {
  await refused(check(attestation('nonce-1', APP), { challenge: 'another-nonce' }))
  await refused(check(attestation('nonce-1', APP, { appId: 'OTHERTEAM.com.aagamjain.Aurora' })))
  await refused(check(attestation('nonce-1', APP, { counter: 1 })))
  await refused(check(attestation('nonce-1', APP, { aaguid: AAGUID_DEV })), 'a development key in production')
  assert.ok(await check(attestation('nonce-1', APP, { aaguid: AAGUID_DEV }), { environment: 'development' }))
  await refused(check(attestation('nonce-1', APP), { environment: 'development' }), 'a production key in development')
  await refused(check(attestation('nonce-1', APP, { keyId: Buffer.alloc(32, 7) })))
  await refused(check(attestation('nonce-1', APP, { credentialId: Buffer.alloc(32, 7) })))
  await refused(check(attestation('nonce-1', APP, { nonce: Buffer.alloc(32, 1) })))
  await refused(check(attestation('nonce-1', APP, { signer: strangerSigner() })))
  // Another valid attestation's key id on this one: the key is bound to its certificate.
  await refused(check({ ...attestation('nonce-1', APP), keyId: attestation('nonce-1', APP).keyId }))
  // Expired credential: judged at the moment of the claim.
  await refused(check(attestation('nonce-1', APP), { now: Date.now() + 3 * 365 * 86_400_000 }))
})

test('malformed input never throws anything but AppleInvalid', async () => {
  for (const attestationText of ['', 'AAAA', Buffer.from([0xbf]).toString('base64'), Buffer.from([0xa1, 0x63, 0x66, 0x6d, 0x74, 0x01]).toString('base64')]) {
    await refused(check({ attestation: attestationText, keyId: 'AAAA' }))
  }
  assert.throws(() => cbor(Uint8Array.from([0x5a, 0xff, 0xff, 0xff, 0xff])), AppleInvalid)
})
