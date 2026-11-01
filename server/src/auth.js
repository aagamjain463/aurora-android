// Verifies Firebase ID tokens with Web Crypto, the same checks as the Firebase Admin SDK, and no dependencies.

const JWKS_URL = 'https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com'
const SKEW_S = 60

const decoder = new TextDecoder()
const fromB64url = (text) => Uint8Array.from(atob(text.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0))

export class AuthError extends Error {
  constructor(status, code) {
    super(code)
    this.status = status
    this.code = code
  }
}

// Keys rotate rarely. An unknown key id refetches, so a rotation can't lock anyone out.
let jwks = { keys: new Map(), until: 0 }

async function signingKey(kid, fetcher) {
  if (jwks.keys.has(kid) && Date.now() < jwks.until) return jwks.keys.get(kid)
  const res = await fetcher(JWKS_URL)
  if (!res.ok) throw new AuthError(503, 'try_again')
  const maxAge = Number(/max-age=(\d+)/.exec(res.headers.get('cache-control') ?? '')?.[1] ?? 3600)
  const body = await res.json()
  const keys = new Map()
  for (const jwk of body.keys ?? []) keys.set(jwk.kid, await crypto.subtle.importKey('jwk', jwk, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']))
  jwks = { keys, until: Date.now() + maxAge * 1000 }
  return keys.get(kid)
}

/** The token's claims if it is genuine, current and for this project. Throws AuthError otherwise. */
export async function verifyIdToken(token, env, fetcher) {
  const bad = new AuthError(401, 'unauthenticated')
  const parts = typeof token === 'string' ? token.split('.') : []
  if (parts.length !== 3) throw bad
  let header, claims
  try {
    header = JSON.parse(decoder.decode(fromB64url(parts[0])))
    claims = JSON.parse(decoder.decode(fromB64url(parts[1])))
  } catch {
    throw bad
  }
  // The Auth Emulator signs nothing. Only a local .dev.vars can turn this on, and production refuses it (wrangler.toml).
  const emulated = env.FIREBASE_AUTH_EMULATOR === '1' && env.ENVIRONMENT !== 'production' && header.alg === 'none'
  if (!emulated) {
    if (header.alg !== 'RS256' || typeof header.kid !== 'string') throw bad
    const key = await signingKey(header.kid, fetcher)
    if (!key) throw bad
    const ok = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, fromB64url(parts[2]), new TextEncoder().encode(`${parts[0]}.${parts[1]}`))
    if (!ok) throw bad
  }
  const now = Math.floor(Date.now() / 1000)
  const project = env.FIREBASE_PROJECT_ID
  if (!project || claims.aud !== project || claims.iss !== `https://securetoken.google.com/${project}`) throw bad
  if (typeof claims.sub !== 'string' || claims.sub.length === 0 || claims.sub.length > 128) throw bad
  if (!(claims.exp > now) || !(claims.iat <= now + SKEW_S) || !(claims.auth_time <= now + SKEW_S)) throw bad
  return claims
}

export const bearer = (request) => /^Bearer (.+)$/.exec(request.headers.get('authorization') ?? '')?.[1]
