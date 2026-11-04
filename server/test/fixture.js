// A real SQL database behind the D1 API (node:sqlite), real RSA keys, and Google's endpoints faked at the fetch boundary.
import { DatabaseSync } from 'node:sqlite'
import { generateKeyPairSync, createSign, createVerify, createHash } from 'node:crypto'
import { readFileSync, readdirSync } from 'node:fs'
import worker from '../src/worker.js'

const dir = new URL('../migrations/', import.meta.url)
const MIGRATIONS = readdirSync(dir).filter((f) => f.endsWith('.sql')).sort().map((f) => readFileSync(new URL(f, dir), 'utf8'))
const tick = () => new Promise((r) => setImmediate(r))

export class FakeD1 {
  constructor() {
    this.db = new DatabaseSync(':memory:')
    this.db.exec('PRAGMA foreign_keys = ON')
    for (const m of MIGRATIONS) this.db.exec(m)
  }
  prepare(sql) {
    const db = this.db
    const statement = {
      args: [],
      bind(...args) {
        const next = Object.create(statement)
        next.args = args
        return next
      },
      runNow() {
        for (const a of this.args) if (a === undefined) throw new Error('D1_TYPE_ERROR: undefined')
        const s = db.prepare(sql)
        if (s.columns().length > 0) return { results: s.all(...this.args), meta: { changes: 0 } }
        const r = s.run(...this.args)
        return { results: [], meta: { changes: Number(r.changes) } }
      },
      // Every call yields, so requests really interleave, the way separate D1 round trips do.
      async first() {
        await tick()
        return this.runNow().results[0] ?? null
      },
      async all() {
        await tick()
        return this.runNow()
      },
      async run() {
        await tick()
        return this.runNow()
      },
    }
    return statement
  }
  async batch(statements) {
    await tick()
    this.db.exec('BEGIN')
    try {
      const out = statements.map((s) => s.runNow())
      this.db.exec('COMMIT')
      return out
    } catch (e) {
      this.db.exec('ROLLBACK')
      throw e
    }
  }
  rows(sql, ...args) {
    return this.db.prepare(sql).all(...args)
  }
}

const pem = (k) => k.export({ type: 'pkcs8', format: 'pem' })
const der = (k) => k.export({ type: 'pkcs8', format: 'der' }).toString('base64')
const service = generateKeyPairSync('rsa', { modulusLength: 2048 })
export const firebaseKey = generateKeyPairSync('rsa', { modulusLength: 2048 })
export const otherKey = generateKeyPairSync('rsa', { modulusLength: 2048 })
export const signing = generateKeyPairSync('rsa', { modulusLength: 2048 })
export const IDS = ['aurora_plus_monthly', 'aurora_plus_annual']
export const PROJECT = 'aurora-test'
const KID = 'key-1'

export const b64u = (v) => Buffer.from(v).toString('base64url')

export function idToken(uid, over = {}, { key = firebaseKey.privateKey, kid = KID, alg = 'RS256' } = {}) {
  const now = Math.floor(Date.now() / 1000)
  const head = b64u(JSON.stringify({ alg, kid }))
  const body = b64u(JSON.stringify({
    iss: `https://securetoken.google.com/${PROJECT}`, aud: PROJECT, sub: uid, iat: now, exp: now + 3600, auth_time: now,
    email: `${uid}@example.com`, email_verified: true, firebase: { sign_in_provider: 'google.com' }, ...over,
  }))
  const sig = alg === 'none' ? '' : createSign('RSA-SHA256').update(`${head}.${body}`).sign(key).toString('base64url')
  return `${head}.${body}.${sig}`
}

export const verifySignature = ({ payload, signature }) => createVerify('RSA-SHA256').update(payload).verify(signing.publicKey, signature, 'base64')

export function world() {
  const state = { subs: new Map(), users: new Map(), playDown: false, deleted: [], lookups: 0, verdict: null, integrityDown: false, decoded: [] }
  const fetcher = async (url, init) => {
    const u = String(url)
    if (u.includes('/jwk/securetoken')) {
      const jwk = firebaseKey.publicKey.export({ format: 'jwk' })
      return new Response(JSON.stringify({ keys: [{ ...jwk, kid: KID, alg: 'RS256', use: 'sig' }] }), { headers: { 'cache-control': 'public, max-age=3600' } })
    }
    if (u.includes('oauth2')) return new Response(JSON.stringify({ access_token: 't' }))
    if (u.endsWith('accounts:lookup')) {
      state.lookups++
      const uid = JSON.parse(init.body).localId[0]
      const user = state.users.get(uid) ?? { validSince: '0' }
      return new Response(JSON.stringify(user === 'gone' ? {} : { users: [user] }))
    }
    if (u.endsWith('accounts:delete')) {
      state.deleted.push(JSON.parse(init.body).localId)
      return new Response('{}')
    }
    if (u.includes('playintegrity.googleapis.com')) {
      if (state.integrityDown) return new Response('{}', { status: 500 })
      const token = JSON.parse(init.body).integrity_token
      state.decoded.push(token)
      return new Response(JSON.stringify({ tokenPayloadExternal: state.verdict?.(token) ?? {} }))
    }
    if (u.includes('subscriptionsv2')) {
      if (state.playDown) return new Response('{}', { status: 500 })
      const token = decodeURIComponent(u.split('/tokens/')[1])
      const sub = state.subs.get(token)
      return sub ? new Response(JSON.stringify(sub)) : new Response('{}', { status: 404 })
    }
    throw new Error(`unexpected fetch ${u}`)
  }
  const DB = new FakeD1()
  const account = JSON.stringify({ client_email: 'a@b.c', private_key: pem(service.privateKey) })
  const env = {
    DB, PACKAGE_NAME: 'app.aurora', PRODUCT_IDS: IDS.join(','), FIREBASE_PROJECT_ID: PROJECT, ENVIRONMENT: 'production',
    GOOGLE_SERVICE_ACCOUNT: account, FIREBASE_SERVICE_ACCOUNT: account, SIGNING_KEY: der(signing.privateKey), RTDN_TOKEN: 'secret',
  }
  const call = async (path, token, body = {}, headers = {}) => {
    const res = await worker.fetch(new Request(`https://x${path}`, {
      method: 'POST',
      headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), ...headers },
      body: typeof body === 'string' ? body : JSON.stringify(body),
    }), env, null, fetcher)
    const text = await res.text()
    return { status: res.status, body: text ? JSON.parse(text) : null }
  }
  return { env, DB, fetcher, state, call }
}

export const FUTURE = () => new Date(Date.now() + 30 * 86_400_000).toISOString()
export const PAST = () => new Date(Date.now() - 86_400_000).toISOString()
export const activeSub = (over = {}) => ({ subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE', lineItems: [{ productId: IDS[0], expiryTime: FUTURE(), autoRenewingPlan: { autoRenewEnabled: true } }], ...over })
export const sha256Hex = (v) => createHash('sha256').update(v).digest('hex')

/** A phone's Keystore key, as the app makes it: ECDSA P-256, signatures in DER. */
export function deviceKey() {
  const pair = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
  const publicKey = pair.publicKey.export({ type: 'spki', format: 'der' }).toString('base64')
  const proof = (path, token, raw) => {
    const message = `aurora-device-v1\n${path}\n${sha256Hex(token)}\n${sha256Hex(raw)}`
    return { 'x-aurora-device-key': publicKey, 'x-aurora-device-signature': createSign('SHA256').update(message).sign(pair.privateKey).toString('base64') }
  }
  return { publicKey, proof }
}

/** [call], signed by [key] the way the app signs. */
export const signedCall = (w, key, path, token, body = {}) => {
  const raw = JSON.stringify(body)
  return w.call(path, token, raw, key.proof(path, token, raw))
}
