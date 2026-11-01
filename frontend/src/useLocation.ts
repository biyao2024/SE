import { onBeforeUnmount, ref } from 'vue'
import type { LocationFix } from './locationMath.mjs'
import { startLocationSampling } from './locationSampler.mjs'

export type { LocationFix } from './locationMath.mjs'

export function useLocation() {
  const location = ref<LocationFix | null>(null)
  const locating = ref(false)
  const locationError = ref('')
  let generation = 0
  let cancelSample: (() => void) | undefined

  function clearLocation() {
    generation++
    cancelSample?.()
    cancelSample = undefined
    location.value = null
    locating.value = false
    locationError.value = ''
  }

  async function locate(targetAccuracyMeters = 30): Promise<LocationFix | null> {
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
    const sample = startLocationSampling(navigator.geolocation, {
      targetAccuracyMeters,
      onProgress: fix => { if (current === generation) location.value = fix },
    })
    cancelSample = sample.cancel
    const result = await sample.result
    if (current !== generation) return null
    cancelSample = undefined
    locating.value = false
    location.value = result.fix
    locationError.value = result.error
    return result.fix
  }

  onBeforeUnmount(clearLocation)
  return { location, locating, locationError, locate, clearLocation }
}
