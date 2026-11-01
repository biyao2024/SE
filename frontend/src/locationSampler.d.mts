import type { LocationFix } from './locationMath.mjs'
export function startLocationSampling(geolocation: Pick<Geolocation, 'watchPosition' | 'clearWatch'>, options?: {
  targetAccuracyMeters?: number
  maxDurationMs?: number
  onProgress?: (fix: LocationFix) => void
  now?: () => number
  setTimer?: typeof setTimeout
  clearTimer?: typeof clearTimeout
}): { result: Promise<{ fix: LocationFix | null; error: string }>; cancel: () => void }
