import test from 'node:test'
import assert from 'node:assert/strict'
import { FrameBudget, maskedIdentity, captureRect } from '../src/cameraProtocol.mjs'

test('terminal camera transport rejects oversized, invalid and expired frames without consuming budget', () => {
  const budget = new FrameBudget(1000)
  assert.equal(budget.accept(200 * 1024 + 1, 1001), false)
  assert.equal(budget.accept(0, 1001), false)
  assert.equal(budget.accept(NaN, 1001), false)
  assert.equal(budget.accept(1, 999), false)
  assert.equal(budget.accept(1, 13000), false)
  assert.equal(budget.frames, 0)
  assert.equal(budget.accept(200 * 1024, 12999), true)
})
test('a session cannot exceed 80 frames, including on a stalled or fast client clock', () => {
  const budget = new FrameBudget(0)
  for (let i=0;i<80;i++) assert.equal(budget.accept(1, 1), true)
  assert.equal(budget.accept(1, 1), false)
  assert.equal(budget.frames, 80)
})
test('aggregate byte limit is enforced separately from per-frame and count limits', () => {
  const budget = new FrameBudget(0)
  for (let i=0;i<61;i++) assert.equal(budget.accept(200 * 1024, 1), true)
  assert.equal(budget.accept(88 * 1024, 1), true)
  assert.equal(budget.bytes, 12 * 1024 * 1024)
  assert.equal(budget.accept(1, 1), false)
  assert.equal(budget.frames, 62)
})
test('public results redact identity even if a server accidentally returns full values', () => {
  assert.deepEqual(maskedIdentity('张小明', 'EMP00123'), { employeeName: '张**', employeeNo: '***0123' })
  assert.deepEqual(maskedIdentity('李', '123'), { employeeName: '李*', employeeNo: '***3' })
  assert.deepEqual(maskedIdentity('王*', '***32'), { employeeName: '王*', employeeNo: '***32' })
  assert.deepEqual(maskedIdentity(null, null), { employeeName: '', employeeNo: '' })
})
test('capture preserves face proportions for 4:3, widescreen and portrait cameras', () => {
  assert.deepEqual(captureRect(640, 480), { x: 0, y: 0, width: 640, height: 480 })
  assert.deepEqual(captureRect(1920, 1080), { x: 240, y: 0, width: 1440, height: 1080 })
  assert.deepEqual(captureRect(480, 640), { x: 0, y: 140, width: 480, height: 360 })
  assert.equal(captureRect(0, 480), null)
  assert.equal(captureRect(640, NaN), null)
})
