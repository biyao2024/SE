import test from 'node:test'
import assert from 'node:assert/strict'
import { validCoordinates, distanceMeters, locationFixIssue, evaluateLocationRange, formatDistance, shiftDate, correctionDateEligible, timeInWindow } from '../src/locationMath.mjs'

test('distance is zero at the same point and symmetric for separate points', () => {
  assert.equal(distanceMeters(31.2, 121.5, 31.2, 121.5), 0)
  const forward = distanceMeters(31.2, 121.5, 31.201, 121.501)
  assert.ok(forward > 140 && forward < 150)
  assert.ok(Math.abs(forward - distanceMeters(31.201, 121.501, 31.2, 121.5)) < 1e-8)
})

test('distance handles the date line, poles and opposite points', () => {
  assert.ok(Math.abs(distanceMeters(0, 0, 0, 1) - 111_195.08) < 1)
  assert.ok(distanceMeters(0, 179.999, 0, -179.999) < 223)
  assert.ok(distanceMeters(90, 0, 90, 180) < .001)
  assert.ok(Number.isFinite(distanceMeters(0, 0, 0, 180)))
})

test('invalid coordinates cannot become a plausible distance', () => {
  for (const pair of [[91, 0], [0, 181], [NaN, 10], [0, Infinity], ['31', 121], [null, 0]]) {
    assert.equal(validCoordinates(...pair), false)
    assert.throws(() => distanceMeters(...pair, 0, 0), RangeError)
  }
  assert.equal(validCoordinates(-90, -180), true)
})

test('position quality separates stale, inaccurate and malformed fixes', () => {
  const now = 1_800_000_000_000
  const fix = { latitude: 31.2, longitude: 121.5, accuracyMeters: 20, capturedAt: now - 5000 }
  assert.equal(locationFixIssue(fix, now), null)
  assert.equal(locationFixIssue({ ...fix, capturedAt: now - 60_001 }, now), 'STALE')
  assert.equal(locationFixIssue({ ...fix, capturedAt: now + 5001 }, now), 'STALE')
  assert.equal(locationFixIssue({ ...fix, capturedAt: now + 5000 }, now), null)
  assert.equal(locationFixIssue({ ...fix, accuracyMeters: 101 }, now), 'INACCURATE')
  assert.equal(locationFixIssue({ ...fix, accuracyMeters: -1 }, now), 'INVALID')
  assert.equal(locationFixIssue({ ...fix, accuracyMeters: 0 }, now), 'INVALID')
  assert.equal(locationFixIssue({ ...fix, accuracyMeters: NaN }, now), 'INVALID')
  assert.equal(locationFixIssue(null, now), 'INVALID')
})

test('a small fence requires accuracy within half its radius', () => {
  const fix = { latitude: 31.2, longitude: 121.5, accuracyMeters: 26, capturedAt: 1 }
  const places = [{ id: 1, latitude: 31.2, longitude: 121.5, radiusMeters: 50, active: true }]
  assert.equal(evaluateLocationRange(fix, places).reason, 'INACCURATE')
  assert.equal(evaluateLocationRange(fix, places).accuracyLimit, 25)
  assert.equal(evaluateLocationRange({ ...fix, accuracyMeters: 25 }, places).matchedLocationId, 1)
  assert.equal(evaluateLocationRange({ ...fix, accuracyMeters: 0 }, places).reason, 'INVALID')
})

test('overlapping fences use an eligible place, not only the nearest one', () => {
  const fix = { latitude: 31.2, longitude: 121.5, accuracyMeters: 60, capturedAt: 1 }
  const places = [
    { id: 1, latitude: 31.2, longitude: 121.5, radiusMeters: 50, active: true },
    { id: 2, latitude: 31.2001, longitude: 121.5, radiusMeters: 200, active: true },
  ]
  assert.equal(evaluateLocationRange(fix, places).matchedLocationId, 2)
  assert.equal(evaluateLocationRange(fix, places.map(place => ({ ...place, active: false }))).reason, 'OUTSIDE')
})

test('poor accuracy never expands a fence', () => {
  const fix = { latitude: 0, longitude: 0.0005, accuracyMeters: 10, capturedAt: 1 }
  const places = [{ id: 1, latitude: 0, longitude: 0, radiusMeters: 50 }]
  assert.ok(distanceMeters(0, 0, fix.latitude, fix.longitude) > 50)
  assert.equal(evaluateLocationRange(fix, places).reason, 'OUTSIDE')
})

test('distance labels preserve units and do not render invalid results', () => {
  assert.equal(formatDistance(15.3), '15 米')
  assert.equal(formatDistance(1500), '1.5 公里')
  assert.equal(formatDistance(NaN), '—')
  assert.equal(formatDistance(-1), '—')
})

test('indoor accuracy is accepted only by an explicitly configured site and remains bounded by radius', () => {
  const fix = { latitude: 31.2, longitude: 121.5, accuracyMeters: 137, capturedAt: 1 }
  const place = { id: 1, latitude: 31.2, longitude: 121.5, radiusMeters: 300, active: true }
  assert.equal(evaluateLocationRange(fix, [place], 500).reason, 'INACCURATE')
  assert.equal(evaluateLocationRange(fix, [{ ...place, maxAccuracyMeters: 150 }], 500).matchedLocationId, 1)
  assert.equal(evaluateLocationRange(fix, [{ ...place, radiusMeters: 200, maxAccuracyMeters: 150 }], 500).reason, 'INACCURATE')
  assert.equal(evaluateLocationRange({ ...fix, latitude: 31.21 }, [{ ...place, maxAccuracyMeters: 150 }], 500).reason, 'OUTSIDE')
})

test('correction dates span seven ended calendar days across month boundaries', () => {
  assert.equal(shiftDate('2026-11-01', -7), '2026-10-25')
  assert.equal(shiftDate('2024-03-01', -1), '2024-02-29')
  assert.equal(shiftDate('2026-02-30', -1), '')
  assert.equal(correctionDateEligible('2026-10-25', '2026-11-01'), true)
  assert.equal(correctionDateEligible('2026-10-31', '2026-11-01'), true)
  assert.equal(correctionDateEligible('2026-10-24', '2026-11-01'), false)
  assert.equal(correctionDateEligible('2026-11-01', '2026-11-01'), false)
})

test('punch windows update correctly at start and end boundaries', () => {
  assert.equal(timeInWindow('11:59:59', '12:00:00', '23:59:59'), false)
  assert.equal(timeInWindow('12:00:00', '12:00:00', '23:59:59'), true)
  assert.equal(timeInWindow('12:00:00', '06:00:00', '11:59:59'), false)
  assert.equal(timeInWindow('23:59:59', '12:00:00', '23:59:59'), true)
  assert.equal(timeInWindow('06:00', '06:00:00', '11:59:59'), true)
  assert.equal(timeInWindow('24:00:00', '06:00:00', '11:59:59'), false)
  assert.equal(timeInWindow('no-time', '06:00:00', '11:59:59'), false)
})
