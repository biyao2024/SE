export interface LocationFix {
  latitude: number
  longitude: number
  accuracyMeters: number
  capturedAt: number
}
export function validCoordinates(latitude: unknown, longitude: unknown): boolean
export function distanceMeters(fromLatitude: number, fromLongitude: number, toLatitude: number, toLongitude: number): number
export function locationFixIssue(fix: LocationFix | null, now: number, maxAgeSeconds?: number, maxAccuracyMeters?: number): 'INVALID' | 'STALE' | 'INACCURATE' | null
export function evaluateLocationRange(fix: LocationFix | null, locations: { id: number; latitude: number; longitude: number; radiusMeters: number; maxAccuracyMeters?: number; active?: boolean }[], maxAccuracyMeters?: number): { reason: 'INVALID' | 'OUTSIDE' | 'INACCURATE' | null; matchedLocationId: number | null; accuracyLimit: number }
export function formatDistance(meters: number): string
export function shiftDate(date: string, days: number): string
export function correctionDateEligible(date: string, currentDate: string): boolean
export function timeInWindow(time: string, start: string, end: string): boolean
