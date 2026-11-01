export type AttendanceMode = 'FACE' | 'LOCATION' | 'FACE_OR_LOCATION'

export interface AttendancePolicy {
  mode: AttendanceMode
  faceEnabled: boolean
  locationEnabled: boolean
  maxAccuracyMeters: number
  maxPositionAgeSeconds: number
}

export interface AttendanceLocation {
  id: number
  name: string
  address: string
  latitude: number
  longitude: number
  radiusMeters: number
  active: boolean
}

export const modeLabels: Record<AttendanceMode, string> = {
  FACE: '人脸打卡',
  LOCATION: '定位打卡',
  FACE_OR_LOCATION: '人脸或定位',
}
