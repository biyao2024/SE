import { onBeforeUnmount, onMounted, ref } from 'vue'
import { errorText } from './api'
import { captureRect } from './cameraProtocol.mjs'
export function useLoad(fn: () => Promise<void>) {
  const loading = ref(false), error = ref('')
  async function load() { loading.value = true; error.value = ''; try { await fn() } catch (e) { error.value = errorText(e) } finally { loading.value = false } }
  onMounted(load); return { loading, error, load }
}
export function today() { return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date()) }
export function monthRange(): [string, string] { const end = today(); return [end.slice(0, 8) + '01', end] }
export function timeText(value?: string | null) { if (!value) return '—'; const match = value.match(/(?:T|\s)(\d{2}:\d{2})/); return match ? match[1] : value.slice(0, 5) }
export const statusNames: Record<string, string> = { PENDING: '待完成', NORMAL: '正常', ABSENT: '缺勤', MISSING_IN: '缺签到', MISSING_OUT: '缺签退', LEAVE: '请假', REST: '休息日', APPROVED: '已批准', REJECTED: '已驳回', CANCELLED: '已撤销' }
export function statusType(s: string): 'success' | 'warning' | 'danger' | 'info' { return ['NORMAL', 'APPROVED'].includes(s) ? 'success' : ['ABSENT', 'REJECTED'].includes(s) ? 'danger' : ['PENDING', 'MISSING_IN', 'MISSING_OUT'].includes(s) ? 'warning' : 'info' }
export function useCamera() {
  const video = ref<HTMLVideoElement | null>(null), cameraError = ref(''), cameraReady = ref(false)
  let stream: MediaStream | null = null, generation = 0
  function stop() { generation++; stream?.getTracks().forEach(track => track.stop()); stream = null; if (video.value) video.value.srcObject = null; cameraReady.value = false }
  async function start() {
    stop(); const current = generation; cameraError.value = ''
    if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) { cameraError.value = '摄像头需要 HTTPS 安全连接。请通过已信任证书的 HTTPS 地址访问；本机开发可使用 localhost。'; return false }
    try {
      const next = await navigator.mediaDevices.getUserMedia({ video: { width: { ideal: 640 }, height: { ideal: 480 }, facingMode: 'user' }, audio: false })
      if (current !== generation) { next.getTracks().forEach(t => t.stop()); return false }
      if (!video.value) { next.getTracks().forEach(t => t.stop()); return false }
      stream = next; video.value.srcObject = stream; await video.value.play()
      if (current !== generation) return false
      cameraReady.value = true; return true
    } catch (e: any) { if (current !== generation) return false; stop(); cameraError.value = e.name === 'NotAllowedError' ? '摄像头权限被拒绝，请在浏览器地址栏允许访问摄像头后重试。' : e.name === 'NotFoundError' ? '未找到摄像头，请连接摄像头后重试。' : e.name === 'NotReadableError' ? '摄像头可能被其他应用占用，请关闭占用程序后重试。' : '无法打开摄像头，请检查设备和浏览器权限。'; return false }
  }
  async function frame(): Promise<Blob | null> {
    if (!video.value || !cameraReady.value || video.value.readyState < 2) return null
    const canvas = document.createElement('canvas'); canvas.width = 640; canvas.height = 480
    const rect = captureRect(video.value.videoWidth, video.value.videoHeight)
    if (!rect) return null
    canvas.getContext('2d')!.drawImage(video.value, rect.x, rect.y, rect.width, rect.height, 0, 0, 640, 480)
    return new Promise(resolve => canvas.toBlob(blob => resolve(blob && blob.size <= 200 * 1024 ? blob : null), 'image/jpeg', 0.72))
  }
  onBeforeUnmount(stop)
  return { video, cameraError, cameraReady, start, stop, frame }
}
