/** Local transport limits are a second boundary; the server independently enforces them. */
export class FrameBudget {
  constructor(startedAt, { maxFrames = 80, maxBytes = 12 * 1024 * 1024, maxFrameBytes = 200 * 1024, durationMs = 12000 } = {}) {
    this.startedAt = startedAt; this.maxFrames = maxFrames; this.maxBytes = maxBytes; this.maxFrameBytes = maxFrameBytes; this.durationMs = durationMs; this.frames = 0; this.bytes = 0
  }
  accept(size, now) {
    if (!Number.isInteger(size) || size <= 0 || size > this.maxFrameBytes) return false
    if (now < this.startedAt || now - this.startedAt >= this.durationMs) return false
    if (this.frames >= this.maxFrames || this.bytes + size > this.maxBytes) return false
    this.frames++; this.bytes += size; return true
  }
}
export function maskedIdentity(name, employeeNo) {
  const text = String(name || ''), number = String(employeeNo || '')
  return { employeeName: text ? text.includes('*') ? text : Array.from(text)[0] + '*'.repeat(Math.max(1, Array.from(text).length - 1)) : '', employeeNo: number ? number.includes('*') ? number : '***' + (number.length > 4 ? number.slice(-4) : number.slice(-1)) : '' }
}
/** Center-crop instead of stretching a widescreen/mobile camera into a 4:3 frame. */
export function captureRect(width, height, targetWidth = 640, targetHeight = 480) {
  if (![width, height, targetWidth, targetHeight].every(v => Number.isFinite(v) && v > 0)) return null
  const ratio = targetWidth / targetHeight
  const sourceWidth = Math.min(width, height * ratio)
  const sourceHeight = Math.min(height, width / ratio)
  return { x: (width - sourceWidth) / 2, y: (height - sourceHeight) / 2, width: sourceWidth, height: sourceHeight }
}
