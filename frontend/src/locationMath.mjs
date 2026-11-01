const EARTH_RADIUS_METERS = 6_371_008.8

export function validCoordinates(latitude, longitude) {
  return typeof latitude === 'number' && typeof longitude === 'number'
    && Number.isFinite(latitude) && Number.isFinite(longitude)
    && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180
}

export function distanceMeters(fromLatitude, fromLongitude, toLatitude, toLongitude) {
  if (!validCoordinates(fromLatitude, fromLongitude) || !validCoordinates(toLatitude, toLongitude)) {
    throw new RangeError('坐标超出有效范围')
  }
  const radians = degrees => degrees * Math.PI / 180
  const latitudeDelta = radians(toLatitude - fromLatitude)
  const longitudeDelta = radians(toLongitude - fromLongitude)
  const a = Math.sin(latitudeDelta / 2) ** 2
    + Math.cos(radians(fromLatitude)) * Math.cos(radians(toLatitude)) * Math.sin(longitudeDelta / 2) ** 2
  return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(Math.min(1, Math.max(0, a))))
}

export function locationFixIssue(fix, now, maxAgeSeconds = 60, maxAccuracyMeters = 100) {
  if (!fix || !validCoordinates(fix.latitude, fix.longitude)
    || !Number.isFinite(fix.accuracyMeters) || fix.accuracyMeters <= 0
    || !Number.isFinite(fix.capturedAt) || !Number.isFinite(now)) return 'INVALID'
  if (fix.capturedAt > now + 5000 || now - fix.capturedAt > maxAgeSeconds * 1000) return 'STALE'
  if (fix.accuracyMeters > maxAccuracyMeters) return 'INACCURATE'
  return null
}

export function evaluateLocationRange(fix, locations, maxAccuracyMeters = 100) {
  if (!fix || !validCoordinates(fix.latitude, fix.longitude) || !Number.isFinite(fix.accuracyMeters) || fix.accuracyMeters <= 0) {
    return { reason: 'INVALID', matchedLocationId: null, accuracyLimit: maxAccuracyMeters }
  }
  const inside = locations.filter(place => place.active !== false && validCoordinates(place.latitude, place.longitude)
    && Number.isFinite(place.radiusMeters) && place.radiusMeters > 0)
    .map(place => ({ place, distance: distanceMeters(fix.latitude, fix.longitude, place.latitude, place.longitude) }))
    .filter(({ place, distance }) => distance <= place.radiusMeters)
    .sort((a, b) => a.distance - b.distance)
  if (!inside.length) return { reason: 'OUTSIDE', matchedLocationId: null, accuracyLimit: maxAccuracyMeters }
  const limit = place => Math.min(maxAccuracyMeters, place.maxAccuracyMeters ?? 100, place.radiusMeters / 2)
  const matched = inside.find(({ place }) => fix.accuracyMeters <= limit(place))
  if (matched) return { reason: null, matchedLocationId: matched.place.id, accuracyLimit: limit(matched.place) }
  return { reason: 'INACCURATE', matchedLocationId: null, accuracyLimit: Math.max(...inside.map(({ place }) => limit(place))) }
}

export function formatDistance(meters) {
  if (!Number.isFinite(meters) || meters < 0) return '—'
  return meters < 1000 ? `${Math.round(meters)} 米` : `${(meters / 1000).toFixed(1)} 公里`
}

export function shiftDate(date, days) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !Number.isInteger(days)) return ''
  const value = new Date(`${date}T00:00:00Z`)
  if (!Number.isFinite(value.getTime()) || value.toISOString().slice(0, 10) !== date) return ''
  value.setUTCDate(value.getUTCDate() + days)
  return value.toISOString().slice(0, 10)
}

export function correctionDateEligible(date, currentDate) {
  const earliest = shiftDate(currentDate, -7)
  return Boolean(earliest && shiftDate(date, 0) && date >= earliest && date < currentDate)
}

export function timeInWindow(time, start, end) {
  const seconds = value => {
    if (typeof value !== 'string' || !/^\d{2}:\d{2}(:\d{2})?$/.test(value)) return NaN
    const [hour, minute, second = 0] = value.split(':').map(Number)
    return hour <= 23 && minute <= 59 && second <= 59 ? hour * 3600 + minute * 60 + second : NaN
  }
  const current = seconds(time), lower = seconds(start), upper = seconds(end)
  return Number.isFinite(current) && Number.isFinite(lower) && Number.isFinite(upper) && lower <= upper && current >= lower && current <= upper
}
