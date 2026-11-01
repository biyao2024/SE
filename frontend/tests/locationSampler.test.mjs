import test from 'node:test'
import assert from 'node:assert/strict'
import { startLocationSampling } from '../src/locationSampler.mjs'

function provider() {
  let success, failure, deadline
  const cleared = [], progress = []
  const now = 1_800_000_000_000
  const geo = { watchPosition(ok, error) { success = ok; failure = error; return 0 }, clearWatch(id) { cleared.push(id) } }
  const sample = startLocationSampling(geo, { now: () => now, onProgress: fix => progress.push(fix),
    setTimer: fn => { deadline = fn; return 1 }, clearTimer: () => {} })
  return { sample, cleared, progress, failure: code => failure({ code }), deadline: () => deadline(),
    fix: (accuracy, age = 0, latitude = 31.2) => success({ coords: { latitude, longitude: 121.5, accuracy }, timestamp: now - age }) }
}

test('a coarse first position does not finish sampling; a better position is used without altering its accuracy', async () => {
  const p = provider()
  p.fix(137); p.fix(190)
  assert.deepEqual(p.progress.map(fix => fix.accuracyMeters), [137])
  assert.deepEqual(p.cleared, [])
  p.fix(25)
  const { fix, error } = await p.sample.result
  assert.equal(fix.accuracyMeters, 25); assert.equal(error, '')
  assert.deepEqual(p.cleared, [0])
})
test('deadline returns the best actual estimate instead of pretending indoor precision improved', async () => {
  const p = provider(); p.fix(190); p.fix(137); p.fix(150); p.deadline()
  assert.equal((await p.sample.result).fix.accuracyMeters, 137)
  assert.deepEqual(p.cleared, [0])
})
test('cancelling stops the watch and late position callbacks cannot revive the request', async () => {
  const p = provider(); p.fix(137); p.sample.cancel(); p.fix(5); p.deadline()
  assert.deepEqual(await p.sample.result, { fix: null, error: '' })
  assert.deepEqual(p.progress.map(fix => fix.accuracyMeters), [137])
  assert.deepEqual(p.cleared, [0])
})
test('invalid, stale and future-dated positions cannot become the best sample', async () => {
  const p = provider(); p.fix(NaN); p.fix(1, 60_001); p.fix(1, -5001); p.fix(1, 0, 91); p.deadline()
  assert.equal((await p.sample.result).fix, null); assert.equal(p.progress.length, 0)
})
test('permission denial rejects earlier samples and stops watching', async () => {
  const p = provider(); p.fix(137); p.failure(1)
  const result = await p.sample.result
  assert.equal(result.fix, null); assert.match(result.error, /权限/)
  assert.deepEqual(p.cleared, [0])
})
test('a transient provider timeout still allows a later, better position', async () => {
  const p = provider(); p.failure(3); p.fix(20)
  assert.equal((await p.sample.result).fix.accuracyMeters, 20)
})
test('synchronous providers and watch registration failures settle without leaking the watch', async () => {
  const cleared = []
  const position = { coords: { latitude: 31.2, longitude: 121.5, accuracy: 10 }, timestamp: Date.now() }
  const sample = startLocationSampling({ watchPosition(ok) { ok(position); return 3 }, clearWatch(id) { cleared.push(id) } })
  assert.equal((await sample.result).fix.accuracyMeters, 10); assert.deepEqual(cleared, [3])
  const broken = startLocationSampling({ watchPosition() { throw new Error('unavailable') }, clearWatch() {} })
  assert.equal((await broken.result).fix, null)
})
