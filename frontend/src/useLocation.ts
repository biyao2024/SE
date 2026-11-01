import { onBeforeUnmount, ref } from 'vue'
import { validCoordinates, type LocationFix } from './locationMath.mjs'

export type { LocationFix } from './locationMath.mjs'

export function useLocation() {
  const location = ref<LocationFix | null>(null)
  const locating = ref(false)
  const locationError = ref('')
  let generation = 0
  let settle: ((value: LocationFix | null) => void) | undefined
  let timeout: ReturnType<typeof setTimeout> | undefined

  function clearLocation() {
    generation++
    if (timeout) clearTimeout(timeout)
    timeout = undefined
    settle?.(null)
    settle = undefined
    location.value = null
    locating.value = false
    locationError.value = ''
  }

  async function locate(): Promise<LocationFix | null> {
    if (locating.value) return null
    clearLocation()
    if (!window.isSecureContext) {
      locationError.value = '定位需要安全连接。请使用已信任的 HTTPS 地址，或在本机使用 localhost。'
      return null
    }
    if (!navigator.geolocation) {
      locationError.value = '当前浏览器不支持定位，请换用支持定位的浏览器。'
      return null
    }
    const current = generation
    locating.value = true
    return new Promise(resolve => {
      settle = resolve
      const finish = (value: LocationFix | null, message = '') => {
        if (current !== generation) return
        generation++
        if (timeout) clearTimeout(timeout)
        timeout = undefined
        settle = undefined
        location.value = value
        locationError.value = message
        locating.value = false
        resolve(value)
      }
      // Also bound time spent waiting for the browser permission prompt.
      timeout = setTimeout(() => finish(null, '定位等待超时，请确认定位权限后重试。'), 25_000)
      try {
        navigator.geolocation.getCurrentPosition(position => {
          const { latitude, longitude, accuracy } = position.coords
          if (!validCoordinates(latitude, longitude) || !Number.isFinite(accuracy) || accuracy <= 0 || !Number.isFinite(position.timestamp)) {
            finish(null, '本次定位信息不完整，请重新获取位置。')
            return
          }
          finish({ latitude, longitude, accuracyMeters: accuracy, capturedAt: position.timestamp })
        }, error => {
          const message = error.code === 1
            ? '定位权限未开启。请在浏览器的网站权限中允许定位后重试。'
            : error.code === 3 ? '定位超时，请移至信号较好的位置后重试。'
              : '暂时无法获取位置，请检查系统定位服务和网络后重试。'
          finish(null, message)
        }, { enableHighAccuracy: true, timeout: 15_000, maximumAge: 0 })
      } catch {
        finish(null, '无法启动定位，请检查系统和浏览器的定位权限。')
      }
    })
  }

  onBeforeUnmount(clearLocation)
  return { location, locating, locationError, locate, clearLocation }
}
