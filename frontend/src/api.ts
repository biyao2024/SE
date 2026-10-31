export interface Page<T> { items: T[]; total: number; page: number; size: number }
export interface User { id: number; username: string; name: string; role: 'ADMIN' | 'EMPLOYEE'; employeeId: number | null; mustChangePassword: boolean }
export class ApiError extends Error { constructor(message: string, public status: number, public code = '', public requestId = '') { super(message); this.name = 'ApiError' } }
let csrf: { token: string; headerName: string } | null = null
let csrfRequest: Promise<void> | null = null
export async function refreshCsrf() {
  if (!csrfRequest) csrfRequest = fetch('/api/v1/auth/csrf', { credentials: 'same-origin', cache: 'no-store' }).then(async r => {
    if (!r.ok) throw new ApiError('无法建立安全会话，请检查服务连接', r.status)
    const body = await r.json(); csrf = body.data
  }).finally(() => { csrfRequest = null })
  return csrfRequest
}
export function query(params: Record<string, unknown>) { const p = new URLSearchParams(); Object.entries(params).forEach(([k, v]) => { if (v !== '' && v !== null && v !== undefined) p.set(k, String(v)) }); return p.toString() }
export async function api<T = any>(path: string, options: RequestInit = {}): Promise<T> {
  const method = (options.method || 'GET').toUpperCase()
  const headers = new Headers(options.headers)
  if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) { if (!csrf) await refreshCsrf(); if (csrf) headers.set(csrf.headerName, csrf.token) }
  let response: Response
  try { response = await fetch(`/api/v1${path}`, { ...options, headers, credentials: 'same-origin', cache: 'no-store' }) }
  catch { throw new ApiError('连接服务失败，请检查网络后重试', 0) }
  const body = await response.json().catch(() => null)
  if (!response.ok || (body?.code && body.code !== 'OK')) {
    if (response.status === 401) csrf = null
    if (response.status === 401 && path !== '/auth/login' && path !== '/auth/me') window.dispatchEvent(new Event('session-expired'))
    throw new ApiError(body?.message || `请求失败（${response.status}）`, response.status, body?.code, body?.requestId)
  }
  return body?.data as T
}
export const post = <T = any>(path: string, data: unknown = {}) => api<T>(path, { method: 'POST', body: JSON.stringify(data) })
export const put = <T = any>(path: string, data: unknown) => api<T>(path, { method: 'PUT', body: JSON.stringify(data) })
export const remove = (path: string) => api(path, { method: 'DELETE' })
export const items = <T>(data: Page<T> | T[]): T[] => Array.isArray(data) ? data : (data?.items || [])
export function errorText(e: unknown) { return e instanceof ApiError && e.requestId ? `${e.message} · 请求编号 ${e.requestId}` : e instanceof Error ? e.message : '操作失败，请稍后重试' }
export async function download(path: string, filename: string) {
  const response = await fetch(`/api/v1${path}`, { credentials: 'same-origin' })
  if (!response.ok) { const body = await response.json().catch(() => null); throw new Error(body?.message || '导出失败，请稍后重试') }
  const url = URL.createObjectURL(await response.blob()); const link = document.createElement('a'); link.href = url; link.download = filename; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
}
