// Fires six signed Free reservations at once at a STAGING worker, to show real D1 lets exactly two through
// (docs/ANTI_ABUSE_STAGING_TESTS.md, T4). Each asks for 50 minutes in its own window, so any two fit in 120
// minutes and no three do.
//
//   BASE=https://aurora-entitlements-staging.<you>.workers.dev TOKENS=<idToken>[,<idToken>...] node scripts/race.mjs [accounts|phones]
//
// accounts (default): one new installation, the tokens' accounts taking turns. Needs DEVICE_QUOTA=enforce.
// phones: the first token's account on six new installations. Tests the account ledger.
// Tokens are never printed. Refuses any URL that doesn't say "staging" or isn't local.
import { createHash, createSign, generateKeyPairSync, randomUUID } from 'node:crypto'

const base = (process.env.BASE ?? '').replace(/\/$/, '')
const tokens = (process.env.TOKENS ?? '').split(',').map((t) => t.trim()).filter(Boolean)
const mode = process.argv[2] ?? 'accounts'
if (!/staging|localhost|127\.0\.0\.1/.test(base)) throw new Error('BASE must be a staging or local worker URL')
if (!tokens.length || !['accounts', 'phones'].includes(mode)) throw new Error('usage: BASE=… TOKENS=a,b node scripts/race.mjs [accounts|phones]')

const sha = (v) => createHash('sha256').update(v).digest('hex')
const phone = () => {
  const pair = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
  return { id: randomUUID(), key: pair.publicKey.export({ type: 'spki', format: 'der' }).toString('base64'), privateKey: pair.privateKey }
}
async function call(p, path, token, body) {
  const raw = JSON.stringify(body)
  const signature = createSign('SHA256').update(`aurora-device-v1\n${path}\n${sha(token)}\n${sha(raw)}`).sign(p.privateKey).toString('base64')
  const res = await fetch(base + path, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${token}`, 'x-aurora-device-key': p.key, 'x-aurora-device-signature': signature },
    body: raw,
  })
  return { status: res.status, body: await res.json().catch(() => null) }
}

// A zone where it is about 6 in the morning, so the six windows (5 hours) never cross midnight.
let offset = 6 - new Date().getUTCHours()
if (offset < -12) offset += 24
const zone = offset === 0 ? 'Etc/GMT' : offset > 0 ? `Etc/GMT-${offset}` : `Etc/GMT+${-offset}`

const shared = phone()
const phones = Array.from({ length: 6 }, (_, i) => (mode === 'phones' ? phone() : shared))
const tokenFor = (i) => (mode === 'phones' ? tokens[0] : tokens[i % tokens.length])
// Register each installation (and bind its key) before the race, so only the reservations race.
for (let i = 0; i < 6; i++) {
  const r = await call(phones[i], '/v1/account/sync', tokenFor(i), { installId: phones[i].id, zone })
  if (r.status !== 200) throw new Error(`sync ${r.status} ${r.body?.error}`)
}
const start = Date.now() + 2 * 60_000
const results = await Promise.all(phones.map((p, i) => call(p, '/v1/quota/reserve', tokenFor(i), {
  installId: p.id, idempotencyKey: randomUUID(), commitmentId: randomUUID(), mode: 'always_block', minutes: 50, zone, startAt: start + i * 50 * 60_000,
})))
for (const [i, r] of results.entries()) console.log(i, r.status, r.body?.allowed ?? r.body?.error, r.body?.limitedBy ?? '')
const allowed = results.filter((r) => r.body?.allowed).length
console.log(`allowed ${allowed} of 6 (expected 2). Installation(s): ${[...new Set(phones.map((p) => p.id))].join(', ')}`)
process.exit(allowed === 2 ? 0 : 1)
