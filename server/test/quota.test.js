import test from 'node:test'
import assert from 'node:assert/strict'
import { LIMIT_MS, dayWindow, fits, quotaZone, statusOf, unionLength, usedIn, validZone } from '../src/quota.js'

const MIN = 60_000
const noon = Date.UTC(2026, 10, 1, 12)
const entry = (start, minutes, install = 'a', zone = 'UTC') => ({ installation_id: install, start_at: start, end_at: start + minutes * MIN, zone })

test('union counts overlaps once', () => {
  assert.equal(unionLength([[0, 10], [5, 20], [30, 40], [35, 36]]), 30)
  assert.equal(unionLength([[5, 5], [9, 2]]), 0)
})

test('exactly 120 minutes fits and 121 does not', () => {
  assert.equal(fits([], 'a', noon, noon + 120 * MIN, 'UTC'), true)
  assert.equal(fits([], 'a', noon, noon + 121 * MIN, 'UTC'), false)
})

test('overlap on one installation counts once', () => {
  const ledger = [entry(noon, 120)]
  assert.equal(fits(ledger, 'a', noon + 30 * MIN, noon + 60 * MIN, 'UTC'), true, 'adds nothing, so it costs nothing')
  assert.equal(fits(ledger, 'a', noon + 100 * MIN, noon + 121 * MIN, 'UTC'), false, 'one minute of new coverage')
})

test('overlap between installations counts separately', () => {
  const ledger = [entry(noon, 100, 'a')]
  assert.equal(fits(ledger, 'b', noon, noon + 20 * MIN, 'UTC'), true)
  assert.equal(fits(ledger, 'b', noon, noon + 21 * MIN, 'UTC'), false)
  assert.equal(usedIn([entry(noon, 60, 'a'), entry(noon, 60, 'b')], noon, noon + 60 * MIN), 120 * MIN)
})

test('a session past midnight charges each day its own part', () => {
  const late = Date.UTC(2026, 10, 1, 23)
  assert.equal(fits([entry(noon, 60)], 'a', late, late + 120 * MIN, 'UTC'), true)
  assert.equal(fits([entry(noon, 90)], 'a', late + 30 * MIN, late + 120 * MIN, 'UTC'), true, '30 before midnight, 60 after')
  assert.equal(fits([entry(noon, 100)], 'a', late - 21 * MIN, late + 60 * MIN, 'UTC'), false, '81 before midnight on a day with 100 used')
})

test('day windows follow the zone, including 23 and 25 hour days and a midnight gap', () => {
  const [from, to] = dayWindow(Date.UTC(2026, 10, 1, 12), 'Asia/Kolkata')
  assert.equal(new Date(from).toISOString(), '2026-10-31T18:30:00.000Z')
  assert.equal(to - from, 24 * 3_600_000)
  const [a, b] = dayWindow(Date.UTC(2026, 2, 8, 12), 'America/New_York')
  assert.equal(b - a, 23 * 3_600_000)
  const [c, d] = dayWindow(Date.UTC(2026, 10, 1, 12), 'America/New_York')
  assert.equal(d - c, 25 * 3_600_000)
  // Havana springs forward at midnight: the day starts at 01:00.
  const [e] = dayWindow(Date.UTC(2026, 2, 8, 16), 'America/Havana')
  assert.equal(new Date(e).toISOString(), '2026-03-08T05:00:00.000Z')
})

test('changing the zone waits for the end of the newest reservation\'s local day', () => {
  const ledger = [entry(noon, 120, 'a', 'UTC')]
  assert.equal(quotaZone(ledger, noon + 3 * 3_600_000, 'Pacific/Kiritimati'), 'UTC')
  assert.equal(quotaZone(ledger, Date.UTC(2026, 10, 2, 0, 1), 'Pacific/Kiritimati'), 'Pacific/Kiritimati')
  assert.equal(quotaZone([], noon, 'Asia/Kolkata'), 'Asia/Kolkata')
})

test('hopping zones at each day boundary never yields a fresh allowance inside what was just used', () => {
  // Spend everything just before UTC midnight, then move to the zone whose day began two hours before it.
  const spent = [entry(Date.UTC(2026, 10, 1, 22), 120, 'a', 'UTC')]
  const next = Date.UTC(2026, 10, 2, 0, 1)
  const zone = quotaZone(spent, next, 'Pacific/Kiritimati') // UTC+14: its day began at 10:00 UTC on the 1st.
  assert.equal(zone, 'Pacific/Kiritimati')
  assert.equal(statusOf(spent, next, zone).remainingMs, 0)
})

test('status reports used, remaining and the reset', () => {
  const s = statusOf([entry(noon, 30)], noon, 'UTC')
  assert.deepEqual(s, { usedMs: 30 * MIN, limitMs: LIMIT_MS, remainingMs: 90 * MIN, resetAt: Date.UTC(2026, 10, 2) })
})

test('zones are validated', () => {
  assert.equal(validZone('Asia/Kolkata'), true)
  assert.equal(validZone('Not/AZone'), false)
  assert.equal(validZone(5), false)
})
