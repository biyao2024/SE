import { locationFixIssue } from './locationMath.mjs'

// Watch only during a user request. Preserve the device's accuracy: consecutive
// estimates are correlated and averaging them cannot prove better accuracy.
export function startLocationSampling(geolocation, options = {}) {
  const { targetAccuracyMeters = 30, maxDurationMs = 20_000, onProgress = () => {},
    now = Date.now, setTimer = setTimeout, clearTimer = clearTimeout } = options
  let best = null, watchId, timer, finished = false, resolveResult
  let lastError = '暂时无法获取位置，请检查系统定位服务和网络后重试。'
  const result = new Promise(resolve => { resolveResult = resolve })
  function finish(fix, error = '') {
    if (finished) return
    finished = true
    if (timer !== undefined) clearTimer(timer)
    if (watchId !== undefined) geolocation.clearWatch(watchId)
    resolveResult({ fix, error })
  }
  timer = setTimer(() => {
    const fix = best && !locationFixIssue(best, now(), 60, Infinity) ? best : null
    finish(fix, fix ? '' : lastError)
  }, maxDurationMs)
  try {
    watchId = geolocation.watchPosition(position => {
      if (finished) return
      const fix = { latitude: position.coords.latitude, longitude: position.coords.longitude,
        accuracyMeters: position.coords.accuracy, capturedAt: position.timestamp }
      if (locationFixIssue(fix, now(), 60, Infinity)) return
      if (!best || locationFixIssue(best, now(), 60, Infinity)
        || fix.accuracyMeters < best.accuracyMeters
        || (fix.accuracyMeters === best.accuracyMeters && fix.capturedAt > best.capturedAt)) {
        best = fix
        onProgress(best)
      }
      if (best.accuracyMeters <= targetAccuracyMeters) finish(best)
    }, error => {
      if (finished) return
      if (error.code === 1) finish(null, '定位权限未开启。请在浏览器的网站权限中允许定位后重试。')
      else lastError = error.code === 3 ? '定位超时，请检查系统定位服务和网络后重试。'
        : '暂时无法获取位置，请检查系统定位服务和网络后重试。'
    }, { enableHighAccuracy: true, timeout: 10_000, maximumAge: 0 })
    if (finished) geolocation.clearWatch(watchId)
  } catch { finish(null, '无法启动定位，请检查系统和浏览器的定位权限。') }
  return { result, cancel: () => finish(null) }
}
