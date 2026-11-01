// Free's committed-protection allowance, on the server's clock. Pure, unit tested (test/quota.test.js).
//
// Policy (docs/ACCOUNT_SYSTEM.md):
// - 120 minutes per account per local day, across every device.
// - Overlapping commitments on one installation count once, because one phone is protected once.
// - Overlaps between installations count separately: two phones protected at once are two uses of the allowance.
// - A zone change takes effect when the local day of the newest reservation ends, so it can't open a new day.
// - The whole reserved interval is charged. A server can't see whether a phone enforced it, and nothing started is refunded.

export const LIMIT_MS = 120 * 60_000
const HOUR = 3_600_000

const formatters = new Map()
function dateIn(at, zone) {
  let f = formatters.get(zone)
  if (!f) formatters.set(zone, (f = new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' })))
  return f.format(at)
}

export function validZone(zone) {
  if (typeof zone !== 'string' || zone.length > 64) return false
  try {
    dateIn(0, zone)
    return true
  } catch {
    return false
  }
}

// First instant in (lo, hi] whose local date passes the test.
function firstWhere(lo, hi, test) {
  while (hi - lo > 1) {
    const mid = Math.floor((lo + hi) / 2)
    if (test(mid)) hi = mid
    else lo = mid
  }
  return hi
}

/** The local calendar day containing [at]: [start, end). A day can be 23 or 25 hours long, and can start after midnight. */
export function dayWindow(at, zone) {
  const date = dateIn(at, zone)
  const start = firstWhere(at - 26 * HOUR, at, (t) => dateIn(t, zone) === date)
  const end = firstWhere(at, at + 26 * HOUR, (t) => dateIn(t, zone) !== date)
  return [start, end]
}

export function unionLength(intervals) {
  let total = 0
  let reach = -Infinity
  for (const [a, b] of intervals.filter(([x, y]) => y > x).sort((x, y) => x[0] - y[0])) {
    const from = Math.max(a, reach)
    if (b > from) total += b - from
    reach = Math.max(reach, b)
  }
  return total
}

/** Used time inside [from, to): each installation's union, added up. */
export function usedIn(entries, from, to) {
  const byInstall = new Map()
  for (const e of entries) {
    const list = byInstall.get(e.installation_id) ?? []
    list.push([Math.max(e.start_at, from), Math.min(e.end_at, to)])
    byInstall.set(e.installation_id, list)
  }
  let total = 0
  for (const list of byInstall.values()) total += unionLength(list)
  return total
}

/** The zone whose calendar applies at [now]: the newest reservation's until its local day is over, then [requested]. */
export function quotaZone(entries, now, requested) {
  const last = entries.reduce((a, b) => (!a || b.end_at > a.end_at ? b : a), null)
  if (!last || !validZone(last.zone)) return requested
  return now < dayWindow(last.end_at - 1, last.zone)[1] ? last.zone : requested
}

export function statusOf(entries, now, zone, limit = LIMIT_MS) {
  const [from, to] = dayWindow(now, zone)
  const usedMs = usedIn(entries, from, to)
  return { usedMs, limitMs: limit, remainingMs: Math.max(0, limit - usedMs), resetAt: to }
}

/** Whether [start, end) on [installationId] fits every day it touches. One that adds no new coverage always fits. */
export function fits(entries, installationId, start, end, zone, limit = LIMIT_MS) {
  const after = [...entries, { installation_id: installationId, start_at: start, end_at: end }]
  let cursor = start
  while (cursor < end) {
    const [from, to] = dayWindow(cursor, zone)
    const used = usedIn(after, from, to)
    if (used > limit && used > usedIn(entries, from, to)) return false
    cursor = to
  }
  return true
}
