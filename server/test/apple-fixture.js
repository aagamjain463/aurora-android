// A stand-in Apple: a real ECDSA certificate chain built here, real signatures, and the App Store Server API faked at the
// fetch boundary. Nothing that passes against this proves Apple accepts or sends these exact bytes: that takes real
// purchases (docs/APP_STORE_BILLING.md). test/apple-crypto.test.js also runs the verifier over Apple's own certificates.
import { createPublicKey, createSign, createVerify, generateKeyPairSync } from 'node:crypto'
import { appAccountToken, appleTrust } from '../src/apple.js'
import { idToken, world } from './fixture.js'

const length = (n) => (n < 128 ? [n] : n < 256 ? [0x81, n] : [0x82, n >> 8, n & 255])
const tlv = (tag, ...parts) => {
  const body = Buffer.concat(parts)
  return Buffer.concat([Buffer.from([tag, ...length(body.length)]), body])
}
const seq = (...parts) => tlv(0x30, ...parts)
const oid = (hexValue) => tlv(0x06, Buffer.from(hexValue, 'hex'))
const name = (cn) => seq(tlv(0x31, seq(oid('550403'), tlv(0x0c, Buffer.from(cn)))))
const time = (ms) => tlv(0x18, Buffer.from(new Date(ms).toISOString().replace(/[-:T]|\.\d+/g, '')))
const extension = (id, value, critical = false) => seq(oid(id), ...(critical ? [tlv(0x01, Buffer.from([0xff]))] : []), tlv(0x04, value))
const YEAR = 365 * 86_400_000
export const OID = { intermediate: '2a864886f76364060201', leaf: '2a864886f76364060b01' }

/** An X.509 v3 ECDSA certificate. Its issuer's key signs it, as a real CA would. */
export function certificate({ subject, issuer = subject, publicKey, signingKey, hash = 'sha256', ca = false, oids = [], notBefore = Date.now() - YEAR, notAfter = Date.now() + YEAR, critical = [] }) {
  const algorithm = seq(oid(hash === 'sha384' ? '2a8648ce3d040303' : '2a8648ce3d040302'))
  const extensions = [
    extension('551d13', ca ? seq(tlv(0x01, Buffer.from([0xff]))) : seq(), true),
    ...oids.map((id) => extension(id, tlv(0x05))),
    ...critical.map((id) => extension(id, tlv(0x05), true)),
  ]
  const tbs = seq(
    tlv(0xa0, tlv(0x02, Buffer.from([2]))), tlv(0x02, Buffer.from([1])), algorithm, name(issuer),
    seq(time(notBefore), time(notAfter)), name(subject), publicKey.export({ type: 'spki', format: 'der' }), tlv(0xa3, seq(...extensions)),
  )
  const signature = createSign(hash).update(tbs).sign(signingKey)
  return seq(tbs, algorithm, tlv(0x03, Buffer.from([0]), signature))
}

const ec = (namedCurve) => generateKeyPairSync('ec', { namedCurve })

/** A chain shaped like Apple's: a P-384 root, a P-384 intermediate, a P-256 leaf. [over] bends one link for a test. */
export function makePki({ intermediate: mid = {}, leaf: end = {} } = {}) {
  const root = ec('secp384r1')
  const intermediate = ec('secp384r1')
  const leaf = ec('prime256v1')
  const rootDer = certificate({ subject: 'Test Root', publicKey: root.publicKey, signingKey: root.privateKey, hash: 'sha384', ca: true })
  const midDer = certificate({ subject: 'Test Intermediate', issuer: 'Test Root', publicKey: intermediate.publicKey, signingKey: root.privateKey, hash: 'sha384', ca: true, oids: [OID.intermediate], ...mid })
  const leafDer = certificate({ subject: 'Test Leaf', issuer: 'Test Intermediate', publicKey: leaf.publicKey, signingKey: intermediate.privateKey, hash: 'sha384', oids: [OID.leaf], ...end })
  return { root, intermediate, leaf, rootDer, midDer, leafDer, x5c: [leafDer, midDer, rootDer].map((d) => d.toString('base64')) }
}

export const jwsOf = (payload, pki, header = {}) => {
  const unsigned = `${Buffer.from(JSON.stringify({ alg: 'ES256', x5c: pki.x5c, ...header })).toString('base64url')}.${Buffer.from(JSON.stringify(payload)).toString('base64url')}`
  const signature = createSign('sha256').update(unsigned).sign({ key: pki.leaf.privateKey, dsaEncoding: 'ieee-p1363' }).toString('base64url')
  return `${unsigned}.${signature}`
}

export const BUNDLE = 'com.aagamjain.Aurora'
export const APPLE_IDS = ['aurora_ios_plus_monthly', 'aurora_ios_plus_annual']
export const DAY = 86_400_000
const API_KEY = ec('prime256v1')
export const trustedPki = makePki()
appleTrust.root = trustedPki.rootDer

export const transaction = (over = {}) => ({
  transactionId: '1001', originalTransactionId: '2000', webOrderLineItemId: '3000', bundleId: BUNDLE, productId: APPLE_IDS[0], type: 'Auto-Renewable Subscription',
  purchaseDate: Date.now() - DAY, originalPurchaseDate: Date.now() - DAY, expiresDate: Date.now() + 30 * DAY, signedDate: Date.now(), inAppOwnershipType: 'PURCHASED',
  environment: 'Production', ...over,
})
export const renewal = (over = {}) => ({ originalTransactionId: '2000', productId: APPLE_IDS[0], autoRenewProductId: APPLE_IDS[0], autoRenewStatus: 1, environment: 'Production', signedDate: Date.now(), ...over })

export function appleWorld(options) {
  const w = world(options)
  Object.assign(w.env, {
    APPLE_BUNDLE_ID: BUNDLE, APPLE_PRODUCT_IDS: APPLE_IDS.join(', '), APPLE_ISSUER_ID: 'issuer', APPLE_KEY_ID: 'KEY123',
    APPLE_PRIVATE_KEY: API_KEY.privateKey.export({ type: 'pkcs8', format: 'pem' }),
  })
  const apple = { Production: new Map(), Sandbox: new Map(), calls: [], down: false, status: null, body: null, hold: null, bundleId: BUNDLE }
  w.apple = apple
  w.state.apple = async (url, init) => {
    const environment = url.startsWith('https://api.storekit.apple.com/') ? 'Production' : url.startsWith('https://api.storekit-sandbox.apple.com/') ? 'Sandbox' : null
    if (!environment) throw new Error(`unexpected Apple host ${url}`)
    apple.calls.push(url)
    const [head, claims, signature] = init.headers.authorization.replace('Bearer ', '').split('.')
    const ok = createVerify('sha256').update(`${head}.${claims}`).verify({ key: createPublicKey(API_KEY.privateKey), dsaEncoding: 'ieee-p1363' }, signature, 'base64url')
    const decoded = JSON.parse(Buffer.from(claims, 'base64url'))
    if (!ok || JSON.parse(Buffer.from(head, 'base64url')).kid !== 'KEY123' || decoded.aud !== 'appstoreconnect-v1' || decoded.bid !== BUNDLE || decoded.iss !== 'issuer') return new Response('{}', { status: 401 })
    if (apple.down) return new Response('{}', { status: apple.status ?? 500 })
    const response = apple.body ? { ...apple.body } : null
    const entry = apple[environment].get(decodeURIComponent(url.split('/subscriptions/')[1]))
    const reply = response ?? (entry ? { environment, bundleId: apple.bundleId, appAppleId: 1, data: entry } : null)
    if (apple.hold) {
      const { hold } = apple
      apple.hold = null
      await hold
    }
    return reply ? new Response(JSON.stringify(reply)) : new Response('{"errorCode":4040010}', { status: 404 })
  }
  return w
}

/** One lastTransactions item, signed. */
export const lastTransaction = (status, tx = transaction(), info = renewal(), pki = trustedPki) => ({
  originalTransactionId: tx.originalTransactionId, status, signedTransactionInfo: jwsOf(tx, pki), ...(info ? { signedRenewalInfo: jwsOf(info, pki) } : {}),
})
export const group = (...items) => [{ subscriptionGroupIdentifier: 'plus', lastTransactions: items }]

/** Apple's state for this subscription, and the signed transaction a phone would hold for it. */
export async function subscribed(w, uid, { status = 1, tx = {}, info = {}, environment = 'Production', id = '2000' } = {}) {
  const token = uid === null || tx.appAccountToken !== undefined ? {} : { appAccountToken: await appAccountToken(uid) }
  const signedTx = transaction({ originalTransactionId: id, environment, ...token, ...tx })
  w.apple[environment].set(id, group(lastTransaction(status, signedTx, renewal({ originalTransactionId: id, environment, ...info }))))
  return jwsOf(signedTx, trustedPki)
}

export const verifyApple = (w, uid, signedTransaction, over = {}) => w.call('/v1/verify/apple', idToken(uid), { signedTransaction, installId: 'ios-1', bundleId: BUNDLE, productId: APPLE_IDS[0], ...over })
export const moveApple = (w, uid, signedTransaction, over = {}, tokenOver = {}) => w.call('/v1/purchases/transfer/apple', idToken(uid, tokenOver), { signedTransaction, installId: 'ios-1', ...over })
export const notify = (w, payload, pki = trustedPki) => w.call('/v1/apple/notifications', null, { signedPayload: jwsOf(payload, pki) })
export const notification = (tx, over = {}) => ({
  notificationType: 'DID_RENEW', notificationUUID: crypto.randomUUID(), version: '2.0', signedDate: Date.now(),
  data: { bundleId: BUNDLE, environment: 'Production', signedTransactionInfo: jwsOf(tx ?? transaction(), trustedPki) },
  ...over,
})
export const snapshotOf = (res) => JSON.parse(res.body.entitlement?.payload ?? res.body.payload)
