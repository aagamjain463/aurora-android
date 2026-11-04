// Signature and certificate rules. The first block needs the real pinned root, so it runs before anything replaces it.
import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash, createHmac } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { AppleInvalid, appAccountToken, appleTrust, verifyChain, verifySigned } from '../src/apple.js'

const pinned = appleTrust.root
const { OID, certificate, jwsOf, makePki, transaction } = await import('./apple-fixture.js')
const testRoot = appleTrust.root
const fixture = (name) => readFileSync(new URL(`./apple-fixtures/${name}`, import.meta.url))
const real = JSON.parse(fixture('real-chain.json'))
const withRoot = async (root, run) => {
  const before = appleTrust.root
  appleTrust.root = root
  try {
    return await run()
  } finally {
    appleTrust.root = before
  }
}
const invalid = (promise, label) => assert.rejects(promise, AppleInvalid, label)
const b64 = (der) => der.toString('base64')

// ---- Apple's own certificates and signed data (Apple's app-store-server-library-node test resources, MIT) ------

test('the pinned root is Apple Root CA - G3, by its published SHA-256 fingerprint', () => {
  assert.equal(createHash('sha256').update(pinned).digest('hex'), '63343abfb89a6a03ebb57e9b3f5fa7be7c4f5c756f3017b3a8c488c3653e9179')
  assert.equal(Buffer.from(pinned).toString('base64'), real.root)
})

test('Apple\'s real production chain verifies to the pinned root, and only in its validity window', async () => {
  await withRoot(pinned, async () => {
    const x5c = [real.leaf, real.intermediate, real.root]
    const key = await verifyChain(x5c, real.effectiveDate)
    assert.deepEqual(key.algorithm, { name: 'ECDSA', namedCurve: 'P-256' })
    await invalid(verifyChain(x5c, Date.UTC(2020, 0, 1)), 'before the certificates')
    await invalid(verifyChain(x5c, Date.UTC(2040, 0, 1)), 'after the certificates')
    await invalid(verifyChain([real.intermediate, real.leaf, real.root], real.effectiveDate), 'swapped')
    await invalid(verifyChain([real.leaf, real.intermediate], real.effectiveDate), 'too short')
    const tampered = Buffer.from(real.leaf, 'base64')
    tampered[200] ^= 1
    await invalid(verifyChain([tampered.toString('base64'), real.intermediate, real.root], real.effectiveDate), 'one bit changed')
  })
})

test('Apple\'s real chain means nothing under another root, and x5c[2] can\'t choose the root', async () => {
  await invalid(verifyChain([real.leaf, real.intermediate, real.root], real.effectiveDate))
  const rogue = makePki()
  await withRoot(pinned, () => invalid(verifySigned(jwsOf(transaction({ signedDate: real.effectiveDate }), rogue))))
})

test('Apple\'s own signed fixtures verify under Apple\'s test CA, and not under the production root', async () => {
  const testCA = fixture('testCA.der')
  for (const name of ['transactionInfo', 'renewalInfo', 'testNotification']) {
    const jws = fixture(name).toString().trim()
    const payload = await withRoot(testCA, () => verifySigned(jws))
    assert.ok(payload.signedDate > 0, name)
    await withRoot(pinned, () => invalid(verifySigned(jws), `${name} under the production root`))
    const [head, body, signature] = jws.split('.')
    const changed = Buffer.from(JSON.stringify({ ...JSON.parse(Buffer.from(body, 'base64url')), bundleId: 'other' })).toString('base64url')
    await withRoot(testCA, () => invalid(verifySigned(`${head}.${changed}.${signature}`), `${name} tampered`))
  }
})

// ---- Attacks and malformed input, against a chain built for the purpose ----------------------------------------

test('decoding is not verifying: unsigned, re-signed and spliced tokens are refused', async () => {
  const pki = makePki()
  appleTrust.root = pki.rootDer
  try {
    const payload = transaction()
    const good = jwsOf(payload, pki)
    assert.equal((await verifySigned(good)).originalTransactionId, '2000')
    const [head, body, signature] = good.split('.')
    const encode = (o) => Buffer.from(JSON.stringify(o)).toString('base64url')
    const x5c = pki.x5c
    const hmac = createHmac('sha256', Buffer.from(x5c[0], 'base64')).update(`${encode({ alg: 'HS256', x5c })}.${body}`).digest('base64url')
    const cases = {
      'alg none': `${encode({ alg: 'none', x5c })}.${body}.`,
      'HS256 keyed with the public certificate': `${encode({ alg: 'HS256', x5c })}.${body}.${hmac}`,
      'no signature': `${head}.${body}.`,
      'another payload under this signature': `${head}.${encode({ ...payload, productId: 'x' })}.${signature}`,
      'a truncated signature': `${head}.${body}.${signature.slice(0, 40)}`,
      'no chain': jwsOf(payload, pki, { x5c: undefined }),
      'two certificates': jwsOf(payload, pki, { x5c: x5c.slice(0, 2) }),
      'four certificates': jwsOf(payload, pki, { x5c: [...x5c, x5c[2]] }),
      'junk certificates': jwsOf(payload, pki, { x5c: ['abc', 'def', 'ghi'] }),
      'not base64': jwsOf(payload, pki, { x5c: ['***', '***', '***'] }),
      'a future signing date': jwsOf(transaction({ signedDate: Date.now() + 3_600_000 }), pki),
      'no signing date': jwsOf(transaction({ signedDate: undefined }), pki),
      'a payload that is not an object': `${head}.${encode(5)}.${signature}`,
      'two parts': `${head}.${body}`,
    }
    for (const [label, jws] of Object.entries(cases)) await invalid(verifySigned(jws), label)
    for (const jws of [undefined, null, 5, {}, '', '..']) await invalid(verifySigned(jws), String(jws))
    // Our own valid chain, and a signature from a key that isn't its leaf.
    const other = makePki()
    other.x5c = x5c
    await invalid(verifySigned(jwsOf(payload, other)), 'signed by another key')
  } finally {
    appleTrust.root = testRoot
  }
})

test('every chain rule refuses on its own', async () => {
  const YEAR = 365 * 86_400_000
  const bad = {
    'intermediate without Apple\'s marker': { intermediate: { oids: [] } },
    'intermediate marked as a leaf': { intermediate: { oids: [OID.leaf] } },
    'intermediate that is not a CA': { intermediate: { ca: false } },
    'expired intermediate': { intermediate: { notAfter: Date.now() - YEAR / 2 } },
    'intermediate not yet valid': { intermediate: { notBefore: Date.now() + YEAR / 2 } },
    'intermediate with an unknown critical extension': { intermediate: { critical: ['551d1e'] } },
    'leaf without Apple\'s marker': { leaf: { oids: [] } },
    'leaf marked as an intermediate': { leaf: { oids: [OID.intermediate] } },
    'leaf that is a CA': { leaf: { ca: true } },
    'expired leaf': { leaf: { notAfter: Date.now() - YEAR / 2 } },
    'leaf with an unknown critical extension': { leaf: { critical: ['551d1e'] } },
    'leaf naming a different issuer': { leaf: { issuer: 'Someone Else' } },
    'intermediate naming a different issuer': { intermediate: { issuer: 'Someone Else' } },
  }
  for (const [label, over] of Object.entries(bad)) {
    const pki = makePki(over)
    appleTrust.root = pki.rootDer
    await invalid(verifySigned(jwsOf(transaction(), pki)), label)
  }
  const sane = makePki()
  appleTrust.root = sane.rootDer
  assert.ok(await verifySigned(jwsOf(transaction(), sane)), 'the same builder with nothing bent passes')

  // A leaf signed directly by a key other than the intermediate's, carrying the right names.
  const forged = certificate({ subject: 'Test Leaf', issuer: 'Test Intermediate', publicKey: sane.leaf.publicKey, signingKey: makePki().intermediate.privateKey, hash: 'sha384', oids: [OID.leaf] })
  await invalid(verifySigned(jwsOf(transaction(), { ...sane, x5c: [b64(forged), sane.x5c[1], sane.x5c[2]] })), 'forged leaf')
  // A certificate is judged when Apple signed: an old transaction outlives its leaf, but one signed after expiry doesn't.
  const old = makePki({ leaf: { notBefore: Date.now() - YEAR * 0.9, notAfter: Date.now() - YEAR / 2 } })
  appleTrust.root = old.rootDer
  assert.ok(await verifySigned(jwsOf(transaction({ signedDate: Date.now() - YEAR * 0.7 }), old)), 'signed while valid')
  await invalid(verifySigned(jwsOf(transaction({ signedDate: Date.now() - YEAR * 0.2 }), old)), 'signed after expiry')
  appleTrust.root = testRoot
})

test('hostile DER only ever fails closed', async () => {
  const pki = makePki()
  appleTrust.root = pki.rootDer
  const at = Date.now()
  const mutate = (der, seed) => {
    const out = Buffer.from(der)
    out[seed % out.length] = (seed * 2654435761) & 0xff
    return out.toString('base64')
  }
  for (let seed = 0; seed < 400; seed++) {
    const leaf = mutate(pki.leafDer, seed * 7 + 1)
    const intermediate = mutate(pki.midDer, seed * 13 + 1)
    await verifyChain([leaf, pki.x5c[1], pki.x5c[2]], at).then(
      () => assert.equal(leaf, pki.x5c[0], `a changed leaf passed, seed ${seed}`),
      (e) => assert.ok(e instanceof Error, seed),
    )
    await verifyChain([pki.x5c[0], intermediate, pki.x5c[2]], at).then(
      () => assert.equal(intermediate, pki.x5c[1], `a changed intermediate passed, seed ${seed}`),
      (e) => assert.ok(e instanceof Error, seed),
    )
  }
  for (const der of [Buffer.from([0x30, 0x84, 0xff, 0xff, 0xff, 0xff]), Buffer.from([0x30, 0x80, 0, 0]), Buffer.from([0x30]), Buffer.alloc(0), Buffer.from([0x30, 0x7f, 1])]) {
    await assert.rejects(verifyChain([der.toString('base64'), pki.x5c[1], pki.x5c[2]], at))
  }
  appleTrust.root = testRoot
})

// ---- appAccountToken ----------------------------------------------------------------------------------------------

test('appAccountToken matches what Swift\'s Billing computes, byte for byte', async () => {
  // Generated by running the app's own derivation under swift (docs/APP_STORE_BILLING.md).
  const vectors = JSON.parse(fixture('account-token-vectors.json'))
  for (const [uid, token] of Object.entries(vectors)) assert.equal(await appAccountToken(uid), token.toLowerCase(), uid)
  assert.match(await appAccountToken('x'), /^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  assert.notEqual(await appAccountToken('a'), await appAccountToken('b'))
})
