<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ArrowRight, Calendar, Camera, CircleCheck, DocumentChecked, Location, Refresh, User } from '@element-plus/icons-vue'
import { api, ApiError, errorText, items, query, type Page } from '../api'
import { useAuth } from '../stores/auth'
import { timeText, useLoad } from '../composables'
import { useLocation, type LocationFix } from '../useLocation'
import { distanceMeters, evaluateLocationRange, formatDistance, locationFixIssue, shiftDate, timeInWindow } from '../locationMath.mjs'
import StatusTag from '../components/StatusTag.vue'

type PunchType = 'IN' | 'OUT'
interface DailyRecord { date: string; checkIn: string | null; checkOut: string | null; status: string; late: boolean; early: boolean; correctionCount: number }
interface Place { id: number; name: string; address: string; latitude: number; longitude: number; radiusMeters: number; active: boolean }
interface HomeData {
  date: string; serverTime: string
  employee: { id: number; name: string; employeeNo: string; departmentName: string; active: boolean; faceEnrolled: boolean }
  today: DailyRecord
  rule: { startTime: string; endTime: string; inWindowStart: string; inWindowEnd: string; outWindowStart: string; outWindowEnd: string; lateGraceMinutes: number; earlyGraceMinutes: number }
  policy: { mode: 'FACE' | 'LOCATION' | 'FACE_OR_LOCATION'; faceEnabled: boolean; locationEnabled: boolean; maxAccuracyMeters: number; maxPositionAgeSeconds: number }
  locations: Place[]
  monthSummary: { requiredDays: number; completedDays: number; lateCount: number; earlyCount: number; missingDays: number; absentDays: number; leaveDays: number }
  pendingRequestCount: number; pendingRequests: { leave: number; correction: number }
  workday: boolean; onLeave: boolean; inWindow: boolean; outWindow: boolean
  todayEvents: { id: number; type: PunchType; acceptedAt: string; source: string; locationName: string | null }[]
}
interface Correction { id: number; date: string; type: PunchType; status: string }
interface PunchPayload extends LocationFix { type: PunchType; requestId: string }
interface PunchResult { status: 'SUCCESS' | 'DUPLICATE'; message: string; acceptedAt: string; locationName: string; type: PunchType }

const auth = useAuth()
const home = ref<HomeData | null>(null)
const recentRows = ref<DailyRecord[]>([]), pendingCorrections = ref<Correction[]>([]), recentError = ref(''), recentLoading = ref(false)
const { location, locating, locationError, locate } = useLocation()
const submitting = ref(false), attemptType = ref<PunchType>('IN'), punchError = ref(''), punchResult = ref<PunchResult | null>(null)
const pendingPayload = ref<PunchPayload | null>(null)
const now = ref(Date.now())
const monotonicNow = ref(performance.now()), serverTimestamp = ref(0), serverReceivedAt = ref(0)
let timer: ReturnType<typeof setInterval> | undefined
let disposed = false
let refreshingHome = false, lastLightRefresh = 0
let homeRequestSequence = 0
let punchController: AbortController | undefined

async function fetchHome() {
  const request = ++homeRequestSequence
  const data = await api<HomeData>('/employee/home')
  if (!disposed && request === homeRequestSequence) {
    home.value = data
    serverTimestamp.value = Date.parse(data.serverTime)
    serverReceivedAt.value = performance.now()
    monotonicNow.value = serverReceivedAt.value
    lastLightRefresh = serverReceivedAt.value
  }
  return data
}
async function loadRecent() {
  if (!home.value || recentLoading.value) return
  recentLoading.value = true; recentError.value = ''
  try {
    const [records, corrections] = await Promise.all([
      api<Page<DailyRecord>>('/attendance?' + query({ from: shiftDate(home.value.date, -7), to: shiftDate(home.value.date, -1), page: 0, size: 10 })),
      api<Page<Correction>>('/correction-requests?' + query({ status: 'PENDING', page: 0, size: 100 })),
    ])
    if (disposed) return
    recentRows.value = items(records).filter(row => ['MISSING_IN', 'MISSING_OUT'].includes(row.status))
    pendingCorrections.value = items(corrections)
  } catch (e) { if (!disposed) recentError.value = errorText(e) }
  finally { if (!disposed) recentLoading.value = false }
}
const { loading, error, load } = useLoad(async () => {
  if (!auth.user?.employeeId) return
  await fetchHome()
  await loadRecent()
})

const modeLabel = computed(() => ({ FACE: '刷脸考勤', LOCATION: '定位考勤', FACE_OR_LOCATION: '刷脸或定位' }[home.value?.policy?.mode || 'FACE']))
const faceEnabled = computed(() => home.value?.policy?.faceEnabled ?? true)
const locationEnabled = computed(() => home.value?.policy?.locationEnabled ?? false)
const showLocationSection = computed(() => locationEnabled.value || !!pendingPayload.value || !!punchResult.value)
const places = computed(() => (home.value?.locations || []).filter(place => place.active))
const serverClock = computed(() => {
  if (!Number.isFinite(serverTimestamp.value) || !home.value) return null
  const timestamp = serverTimestamp.value + monotonicNow.value - serverReceivedAt.value
  const value = new Date(timestamp + 8 * 3600_000).toISOString()
  return { date: value.slice(0, 10), time: value.slice(11, 19) }
})
function windowOpen(type: PunchType) {
  if (!home.value) return false
  if (!serverClock.value) return type === 'IN' ? home.value.inWindow : home.value.outWindow
  if (serverClock.value.date !== home.value.date) return false
  const rule = home.value.rule
  return timeInWindow(serverClock.value.time, type === 'IN' ? rule.inWindowStart : rule.outWindowStart, type === 'IN' ? rule.inWindowEnd : rule.outWindowEnd)
}
const datedLabel = computed(() => home.value ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: 'long', day: 'numeric', weekday: 'long' }).format(new Date(home.value.date + 'T12:00:00+08:00')) : '今日考勤')
const monthLabel = computed(() => home.value ? home.value.date.slice(0, 7).replace('-', '年') + '月' : '本月')
const fixIssue = computed(() => locationFixIssue(location.value, now.value, home.value?.policy.maxPositionAgeSeconds ?? 60, home.value?.policy.maxAccuracyMeters ?? 100))
const nearestPlaces = computed(() => places.value.map(place => ({
  ...place,
  distance: location.value ? distanceMeters(location.value.latitude, location.value.longitude, place.latitude, place.longitude) : null,
})).sort((a, b) => (a.distance ?? Infinity) - (b.distance ?? Infinity)))
const locationCaption = computed(() => {
  if (locating.value) return '正在获取当前位置…'
  if (!location.value) return '点击定位打卡时获取一次位置'
  if (fixIssue.value === 'STALE') return '上次位置已过期，下次打卡会重新定位'
  if (fixIssue.value === 'INACCURATE') return `当前精度约 ±${Math.ceil(location.value.accuracyMeters)} 米，请到开阔处重试`
  const range = evaluateLocationRange(location.value, places.value, home.value?.policy.maxAccuracyMeters ?? 100)
  if (range.reason === 'INACCURATE') return `当前精度约 ±${Math.ceil(location.value.accuracyMeters)} 米，此地点需在 ${range.accuracyLimit} 米以内`
  return `已获取位置 · 精度约 ±${Math.ceil(location.value.accuracyMeters)} 米`
})
const unavailableReason = computed(() => {
  if (!home.value) return '正在加载打卡信息'
  if (serverClock.value && serverClock.value.date !== home.value.date) return '日期已更新，正在刷新今天的考勤。'
  if (!home.value.employee.active) return '员工档案已停用，请联系管理员。'
  if (!locationEnabled.value) return '当前考勤方式未开放定位打卡。'
  if (!places.value.length) return '暂未设置可用打卡地点，请联系管理员。'
  if (!home.value.workday) return '今天是休息日，定位打卡未开放。'
  if (home.value.onLeave) return '今天已批准整天请假，无需打卡。'
  if (!windowOpen('IN') && !windowOpen('OUT')) return '当前不在定位打卡时段。'
  return ''
})
function canPunch(type: PunchType) {
  return Boolean(home.value && !unavailableReason.value && windowOpen(type))
}
function requestId() {
  if (crypto.randomUUID) return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6]! & 15) | 64; bytes[8] = (bytes[8]! & 63) | 128
  const text = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
  return `${text.slice(0, 8)}-${text.slice(8, 12)}-${text.slice(12, 16)}-${text.slice(16, 20)}-${text.slice(20)}`
}
async function commitPunch() {
  const payload = pendingPayload.value
  if (!payload) return
  const controller = new AbortController()
  punchController = controller
  let timeout: ReturnType<typeof setTimeout> | undefined
  try {
    const result = await Promise.race([
      api<PunchResult>('/employee/location-punch', { method: 'POST', body: JSON.stringify(payload), signal: controller.signal }),
      new Promise<never>((_, reject) => {
        timeout = setTimeout(() => {
          controller.abort()
          reject(new ApiError('打卡结果暂未确认', 0))
        }, 20_000)
      }),
    ])
    clearTimeout(timeout)
    if (!result || !['SUCCESS', 'DUPLICATE'].includes(result.status) || !result.acceptedAt) throw new Error('打卡结果未确认')
    if (disposed) return
    pendingPayload.value = null
    punchResult.value = result
    punchError.value = ''
    await load()
  } catch (e) {
    if (disposed) return
    if (e instanceof ApiError && e.status > 0 && e.status < 500 && e.status !== 408) {
      pendingPayload.value = null
      punchError.value = e.code === 'LOCATION_STALE'
        ? '位置信息已过期。请确认设备使用自动日期和时间，再重新定位打卡。' : errorText(e)
      await fetchHome().catch(() => {})
    } else {
      punchError.value = '暂未确认本次打卡结果。请点击“核实并重试”，系统会沿用本次请求，避免重复记录。'
    }
  } finally { clearTimeout(timeout); if (punchController === controller) punchController = undefined }
}
async function punch(type: PunchType) {
  if (submitting.value || locating.value || pendingPayload.value) return
  submitting.value = true; attemptType.value = type; punchError.value = ''; punchResult.value = null
  try {
    await fetchHome()
    if (disposed) return
    if (!canPunch(type)) { punchError.value = unavailableReason.value || '当前不在该类型的打卡时段。'; return }
    const fix = await locate()
    if (!fix || disposed) return
    now.value = Date.now()
    const issue = locationFixIssue(fix, now.value, home.value?.policy.maxPositionAgeSeconds, home.value?.policy.maxAccuracyMeters)
    if (issue) {
      punchError.value = issue === 'INACCURATE'
        ? `当前定位精度约 ±${Math.ceil(fix.accuracyMeters)} 米，请移至开阔处重新打卡。`
        : '未获取到新鲜、有效的位置，请检查设备时间后重新定位。'
      return
    }
    const range = evaluateLocationRange(fix, places.value, home.value?.policy.maxAccuracyMeters ?? 100)
    if (range.reason) {
      punchError.value = range.reason === 'INACCURATE'
        ? `当前位置精度需在 ${range.accuracyLimit} 米以内，请到开阔处重新定位。`
        : range.reason === 'OUTSIDE' ? '当前位置不在可用打卡地点范围内，请到达地点后重试。' : '本次定位信息无效，请重新获取位置。'
      return
    }
    pendingPayload.value = { ...fix, type, requestId: requestId() }
    await commitPunch()
  } catch (e) { if (!disposed) punchError.value = errorText(e) }
  finally { if (!disposed) submitting.value = false }
}
async function retryPunch() {
  if (submitting.value || !pendingPayload.value) return
  submitting.value = true; punchError.value = ''
  try { await commitPunch() } finally { if (!disposed) submitting.value = false }
}
async function inspectLocation() {
  if (submitting.value || pendingPayload.value || locating.value) return
  punchError.value = ''
  await locate()
  now.value = Date.now()
}
function pendingFor(row: DailyRecord) {
  const type = row.status === 'MISSING_IN' ? 'IN' : 'OUT'
  return pendingCorrections.value.some(request => request.date === row.date && request.type === type)
}
function correctionLink(row: DailyRecord) {
  return { path: '/employee/requests', query: pendingFor(row)
    ? { requestType: 'correction', status: 'PENDING' }
    : { requestType: 'correction', new: '1', date: row.date, punchType: row.status === 'MISSING_IN' ? 'IN' : 'OUT' } }
}
async function refreshLightly() {
  if (disposed || refreshingHome || submitting.value || locating.value || loading.value || document.hidden || !auth.user?.employeeId) return
  refreshingHome = true; lastLightRefresh = performance.now()
  const previousDate = home.value?.date
  try { await fetchHome(); if (previousDate !== home.value?.date) await loadRecent() } catch { /* The manual refresh keeps its visible error and retry path. */ }
  finally { refreshingHome = false }
}
function onVisibilityChange() { if (!document.hidden) void refreshLightly() }
onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange)
  timer = setInterval(() => {
    now.value = Date.now(); monotonicNow.value = performance.now()
    const elapsed = monotonicNow.value - lastLightRefresh
    if (elapsed >= 60_000 || (elapsed >= 10_000 && serverClock.value && home.value && serverClock.value.date !== home.value.date)) void refreshLightly()
  }, 1000)
})
onBeforeUnmount(() => { disposed = true; if (timer) clearInterval(timer); document.removeEventListener('visibilitychange', onVisibilityChange); punchController?.abort(); pendingPayload.value = null })
</script>

<template>
  <div class="employee-home">
    <header class="home-heading">
      <div><span class="home-date">{{ datedLabel }}</span><h1>我的工作台</h1></div>
      <router-link to="/employee/profile" class="identity-link"><span>{{ home?.employee.name?.slice(0, 1) || auth.user?.name?.slice(0, 1) || '我' }}</span><el-icon><ArrowRight /></el-icon></router-link>
    </header>

    <div v-if="!auth.user?.employeeId" class="account-notice"><el-icon><User /></el-icon><h2>账号尚未关联员工档案</h2><p>请联系管理员完成关联，即可查看考勤并打卡。</p><router-link to="/employee/profile">查看账号资料 <el-icon><ArrowRight /></el-icon></router-link></div>
    <template v-else>
      <div v-if="error" class="home-load-error"><el-alert :title="error" type="error" :closable="false" /><el-button :loading="loading" :disabled="submitting || locating" @click="load">重新加载</el-button></div>
      <div v-if="!home" class="loading-state"><span>{{ loading ? '正在加载今日考勤…' : '暂未获取到考勤信息' }}</span><el-button v-if="!loading" @click="load">重新加载</el-button></div>
      <template v-if="home">
        <section class="today-card" aria-label="今日考勤">
          <header><div><span class="small-label">今天</span><h2>{{ home.employee.name }}<small>{{ home.employee.departmentName }}</small></h2></div><StatusTag :status="home.today.status" :late="home.today.late" :early="home.today.early" /></header>
          <div class="today-punches"><div><span>上班签到</span><strong>{{ timeText(home.today.checkIn) }}</strong><small>{{ home.today.checkIn ? '已记录' : '尚未签到' }}</small></div><div><span>下班签退</span><strong>{{ timeText(home.today.checkOut) }}</strong><small>{{ home.today.checkOut ? '已记录' : '尚未签退' }}</small></div></div>
          <footer><span><el-icon><Calendar /></el-icon>班次 {{ timeText(home.rule.startTime) }} — {{ timeText(home.rule.endTime) }}</span><span>{{ modeLabel }}</span></footer>
        </section>

        <section class="today-events" aria-label="今日打卡记录"><header><h2>今日打卡记录</h2><span>{{ home.todayEvents?.length || 0 }} 条</span></header><p v-if="!home.todayEvents?.length">今天暂无打卡记录</p><div v-else class="today-event-rows"><article v-for="event in [...home.todayEvents].reverse()" :key="event.id"><time>{{ timeText(event.acceptedAt) }}</time><strong>{{ event.type === 'IN' ? '上班签到' : '下班签退' }}</strong><span>{{ event.source === 'LOCATION' ? '定位 · ' + (event.locationName || '考勤地点') : event.source === 'FACE' ? '刷脸' : '其他记录' }}</span></article></div></section>

        <div class="home-primary-grid" :class="{ 'location-disabled': !showLocationSection }">
          <section v-if="showLocationSection" class="location-workspace" aria-labelledby="location-punch-title">
            <div class="section-heading"><div><h2 id="location-punch-title"><el-icon><Location /></el-icon>定位打卡</h2><p>{{ locationCaption }}</p></div><el-button text :icon="Refresh" :disabled="submitting || locating || !!pendingPayload" :loading="locating && !submitting" @click="inspectLocation">{{ location ? '更新位置' : '查看位置' }}</el-button></div>
            <el-alert v-if="punchError || locationError" :title="punchError || locationError" type="warning" show-icon :closable="false" />
            <div v-if="punchResult" class="punch-success" role="status"><el-icon><CircleCheck /></el-icon><div><strong>{{ punchResult.message }}</strong><p>{{ timeText(punchResult.acceptedAt) }} · {{ punchResult.locationName }}</p></div></div>
            <div v-if="pendingPayload" class="retry-panel"><p>本次{{ pendingPayload.type === 'IN' ? '签到' : '签退' }}结果待确认</p><el-button type="primary" :loading="submitting" @click="retryPunch">核实并重试</el-button></div>
            <template v-else>
              <p v-if="unavailableReason" class="availability-note">{{ unavailableReason }}</p>
              <div class="location-actions"><el-button type="primary" :loading="submitting && attemptType === 'IN'" :disabled="submitting || locating || loading || !canPunch('IN')" @click="punch('IN')">定位签到</el-button><el-button :loading="submitting && attemptType === 'OUT'" :disabled="submitting || locating || loading || !canPunch('OUT')" @click="punch('OUT')">定位签退</el-button></div>
            </template>
            <div class="punch-windows"><span>签到 {{ timeText(home.rule.inWindowStart) }}–{{ timeText(home.rule.inWindowEnd) }}</span><span>签退 {{ timeText(home.rule.outWindowStart) }}–{{ timeText(home.rule.outWindowEnd) }}</span></div>
            <div class="location-places"><div class="places-heading"><h3>可用打卡地点</h3><span>{{ places.length }} 处</span></div><p v-if="!places.length" class="empty-copy">管理员设置地点后，即可使用定位打卡。</p><article v-for="place in nearestPlaces" :key="place.id" class="place-row"><span class="place-dot"><el-icon><Location /></el-icon></span><div><strong>{{ place.name }}</strong><p v-if="place.address">{{ place.address }}</p><small>允许半径 {{ place.radiusMeters }} 米<br>定位精度需在 {{ Math.min(home.policy.maxAccuracyMeters || 100, place.radiusMeters / 2) }} 米以内</small></div><span class="place-distance">{{ place.distance === null ? '待定位' : '距 ' + formatDistance(place.distance) }}</span></article></div>
            <p class="location-note">仅在操作时获取位置，不持续跟踪。距离仅供参考，打卡结果以服务端校验为准。</p>
          </section>

          <aside class="home-actions-column">
            <section v-if="faceEnabled" class="face-entry"><span class="face-entry-icon"><el-icon><Camera /></el-icon></span><div><h2>刷脸打卡</h2><p>{{ home.employee.faceEnrolled ? '前往已授权的公共终端打卡' : '请先联系管理员录入人脸' }}</p></div><router-link v-if="home.employee.faceEnrolled" to="/kiosk"><el-button>打开打卡页 <el-icon><ArrowRight /></el-icon></el-button></router-link></section>
            <section class="request-entry"><div class="section-heading"><h2>请假与补卡</h2><router-link :to="{ path: '/employee/requests', query: { requestType: home.pendingRequests.leave > 0 ? 'leave' : 'correction', status: 'PENDING' } }">{{ home.pendingRequestCount }} 项待审批 <el-icon><ArrowRight /></el-icon></router-link></div><div class="request-shortcuts"><router-link :to="{ path: '/employee/requests', query: { requestType: 'leave', new: '1' } }"><el-icon><Calendar /></el-icon><strong>申请请假</strong><small>按整天申请</small></router-link><router-link :to="{ path: '/employee/requests', query: { requestType: 'correction', new: '1' } }"><el-icon><DocumentChecked /></el-icon><strong>申请补卡</strong><small>近 7 个已结束日</small></router-link></div><div class="pending-breakdown"><router-link :to="{ path: '/employee/requests', query: { requestType: 'leave', status: 'PENDING' } }">请假待审批 {{ home.pendingRequests.leave }}</router-link><router-link :to="{ path: '/employee/requests', query: { requestType: 'correction', status: 'PENDING' } }">补卡待审批 {{ home.pendingRequests.correction }}</router-link></div></section>
          </aside>
        </div>

        <section class="month-section"><div class="section-heading"><div><h2>月度概况</h2><p>{{ monthLabel }} · 截至 {{ home.date }}</p></div><router-link to="/employee/attendance">我的考勤 <el-icon><ArrowRight /></el-icon></router-link></div><dl class="month-metrics"><div><dt>完成打卡</dt><dd>{{ home.monthSummary.completedDays }}<small>天</small></dd></div><div><dt>迟到</dt><dd>{{ home.monthSummary.lateCount }}<small>次</small></dd></div><div><dt>早退</dt><dd>{{ home.monthSummary.earlyCount }}<small>次</small></dd></div><div><dt>缺卡</dt><dd>{{ home.monthSummary.missingDays }}<small>天</small></dd></div></dl><p class="month-detail">应出勤 {{ home.monthSummary.requiredDays }} 天 · 请假 {{ home.monthSummary.leaveDays }} 天 · 缺勤 {{ home.monthSummary.absentDays }} 天</p></section>

        <section class="recent-section"><div class="section-heading"><div><h2>近期缺卡</h2><p>近 7 个已结束自然日</p></div><el-button v-if="recentError" text :loading="recentLoading" @click="loadRecent">重新加载</el-button></div><p v-if="recentError" class="recent-error">{{ recentError }}</p><p v-else-if="!recentRows.length" class="empty-copy">{{ recentLoading ? '正在读取近期记录…' : '近期没有缺卡记录' }}</p><div v-else class="missing-list"><article v-for="row in recentRows" :key="row.date"><div><strong>{{ row.date }}</strong><span>{{ row.status === 'MISSING_IN' ? '缺上班签到' : '缺下班签退' }}</span></div><router-link :to="correctionLink(row)"><el-button size="small" :plain="!pendingFor(row)">{{ pendingFor(row) ? '查看审批' : '申请补卡' }} <el-icon><ArrowRight /></el-icon></el-button></router-link></article></div></section>
        <footer class="home-footer"><span>更新于 {{ timeText(home.serverTime) }} · 北京时间</span><el-button text :icon="Refresh" :loading="loading" :disabled="submitting || locating" @click="load">刷新</el-button></footer>
      </template>
    </template>
  </div>
</template>

<style scoped>
.employee-home { max-width: 1080px; margin: 0 auto; color: #2f3f34; }
.home-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 25px; gap: 16px; }
.home-heading h1 { margin: 7px 0 0; font-size: 28px; letter-spacing: -.8px; }
.home-date { font-size: 12px; color: #6d7b6c; }
.identity-link { display: flex; align-items: center; gap: 10px; color: #70816d; }
.identity-link > span { display: grid; place-items: center; width: 40px; height: 40px; border-radius: 13px; background: #e6eddf; border: 1px solid #d7e1cf; color: #48633f; }
.identity-link .el-icon { font-size: 12px; }
.today-card { border-radius: 15px; background: #294d3c; color: #f8fbef; overflow: hidden; margin-bottom: 24px; }
.today-card > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 25px 29px 0; }
.small-label { font-size: 11px; color: #c6d7bf; }
.today-card h2 { margin: 6px 0 0; font-size: 19px; font-weight: 500; }
.today-card h2 small { font-size: 11px; margin-left: 13px; color: #d4dfcc; font-weight: 400; }
.today-card :deep(.el-tag) { background: #e6efdf; color: #3b613f; }
.today-punches { display: grid; grid-template-columns: 1fr 1fr; padding: 29px; }
.today-punches > div + div { border-left: 1px solid #5c7965; padding-left: 32px; }
.today-punches span, .today-punches small { display: block; font-size: 11px; color: #c8d8c3; }
.today-punches strong { display: block; font-size: 41px; font-weight: 500; letter-spacing: -1px; font-variant-numeric: tabular-nums; margin: 8px 0; }
.today-card > footer { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 10px; padding: 15px 29px; border-top: 1px solid #526f5b; background: #244633; font-size: 11px; color: #d9e5d2; }
.today-card footer > span { display: inline-flex; align-items: center; gap: 7px; }
.home-primary-grid { display: grid; grid-template-columns: minmax(0, 1.25fr) minmax(0, .85fr); gap: 22px; align-items: start; margin-bottom: 24px; }
.home-primary-grid.location-disabled { grid-template-columns: 1fr; }
.home-primary-grid.location-disabled .home-actions-column { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.location-workspace, .request-entry, .month-section, .recent-section { padding: 24px; border: 1px solid #e0e6d9; border-radius: 12px; background: #fffefa; min-width: 0; }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-bottom: 20px; }
.section-heading h2 { display: flex; align-items: center; gap: 8px; font-size: 15px; margin: 0; }
.section-heading h2 .el-icon { color: #65845a; }
.section-heading p { font-size: 11px; color: #6f7d66; line-height: 1.7; margin: 6px 0 0; }
.section-heading > a { display: inline-flex; align-items: center; gap: 5px; font-size: 11px; color: #5a754d; flex-shrink: 0; }
.section-heading .el-button { flex-shrink: 0; font-size: 11px; padding: 8px; }
.availability-note { color: #826a47; background: #faf6ea; border-radius: 6px; padding: 10px 12px; font-size: 12px; }
.location-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.location-actions .el-button { margin: 0; height: 44px; font-size: 13px; }
.punch-windows { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 9px; padding-top: 12px; color: #6f7d66; font-size: 10px; }
.places-heading { display: flex; align-items: center; justify-content: space-between; border-top: 1px solid #e9eddf; margin-top: 24px; padding-top: 20px; }
.places-heading h3 { font-size: 12px; margin: 0; font-weight: 500; }
.places-heading > span { font-size: 10px; color: #6f7d66; }
.place-row { display: flex; gap: 10px; padding: 17px 0; align-items: flex-start; border-bottom: 1px solid #edf0e6; }
.place-dot { display: grid; place-items: center; width: 28px; height: 30px; border-radius: 7px; background: #eef3e7; color: #71875e; flex-shrink: 0; }
.place-row > div { min-width: 0; flex: 1; }
.place-row strong { font-size: 12px; font-weight: 500; overflow-wrap: anywhere; }
.place-row p { margin: 5px 0; font-size: 11px; color: #6f7d66; overflow-wrap: anywhere; }
.place-row small { font-size: 10px; color: #6f7d66; }
.place-distance { font-size: 10px; color: #637953; padding-top: 4px; flex-shrink: 0; }
.location-note { font-size: 10px; line-height: 1.9; color: #6f7d66; margin: 15px 0 0; }
.home-actions-column { display: grid; gap: 20px; }
.face-entry { display: flex; flex-wrap: wrap; align-items: center; gap: 13px; padding: 23px; border: 1px solid #dce5d3; border-radius: 12px; background: #eef3e7; }
.face-entry-icon { width: 40px; height: 42px; display: grid; place-items: center; border-radius: 10px; background: #e2ebd6; color: #5f7b4d; font-size: 23px; }
.face-entry > div { flex: 1; min-width: 140px; }
.face-entry h2 { font-size: 14px; margin: 0 0 5px; }
.face-entry p { font-size: 11px; margin: 0; color: #607353; }
.face-entry > a { width: 100%; }
.face-entry .el-button { width: 100%; background: #f8fbf3; border-color: #d3dfc7; color: #48683c; }
.face-entry .el-button .el-icon { margin-left: auto; }
.request-shortcuts { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.request-shortcuts > a { display: grid; justify-items: start; gap: 7px; padding: 17px 14px; background: #f7f9f2; border: 1px solid #e6ecdc; border-radius: 8px; }
.request-shortcuts .el-icon { font-size: 19px; color: #748a61; margin-bottom: 3px; }
.request-shortcuts strong { font-size: 12px; font-weight: 500; }
.request-shortcuts small { font-size: 10px; color: #6f7d66; }
.pending-breakdown { display: flex; flex-wrap: wrap; gap: 12px; font-size: 10px; color: #627655; margin-top: 18px; }
.month-section, .recent-section { margin-top: 22px; }
.month-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); margin: 0; gap: 15px; }
.month-metrics > div { padding-left: 17px; border-left: 1px solid #e2e8d9; }
.month-metrics > div:first-child { border-left: 0; padding-left: 0; }
.month-metrics dt { color: #6f7d66; font-size: 11px; }
.month-metrics dd { margin: 10px 0 0; font-size: 27px; font-weight: 500; font-variant-numeric: tabular-nums; }
.month-metrics dd small { font-size: 10px; color: #6f7d66; margin-left: 6px; font-weight: 400; }
.month-detail { margin: 20px 0 0; padding-top: 15px; border-top: 1px solid #e9eddf; color: #6f7d66; font-size: 10px; }
.empty-copy { font-size: 12px; color: #6f7d66; margin: 18px 0 0; line-height: 1.8; }
.missing-list > article { display: flex; justify-content: space-between; align-items: center; gap: 14px; padding: 16px 0; border-top: 1px solid #e9eddf; }
.missing-list > article:last-child { padding-bottom: 0; }
.missing-list strong { display: block; font-size: 12px; font-weight: 500; font-variant-numeric: tabular-nums; }
.missing-list span { display: block; font-size: 11px; color: #886f4c; margin-top: 5px; }
.home-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 16px; font-size: 10px; color: #6f7d66; }
.home-load-error { margin-bottom: 20px; }
.home-load-error .el-alert { margin-bottom: 10px; }
.today-events { margin: -3px 0 24px; border-bottom: 1px solid #dce4d4; padding-bottom: 16px; }
.today-events header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 7px; }
.today-events h2 { font-size: 12px; font-weight: 500; margin: 0; }
.today-events header > span, .today-events > p { font-size: 10px; color: #6f7d66; }
.today-events > p { margin: 11px 0 0; }
.today-event-rows { max-height: 180px; overflow-y: auto; }
.today-event-rows article { display: grid; grid-template-columns: 54px 80px minmax(0, 1fr); align-items: start; gap: 8px; padding: 9px 0; font-size: 11px; }
.today-event-rows time { color: #547148; font-variant-numeric: tabular-nums; }
.today-event-rows strong { font-weight: 500; }
.today-event-rows span { color: #6f7d66; text-align: right; overflow-wrap: anywhere; }
.punch-success { display: flex; align-items: center; gap: 12px; margin-bottom: 17px; padding: 14px; border: 1px solid #dbe6ce; background: #eff6e7; border-radius: 8px; color: #527343; }
.punch-success > .el-icon { font-size: 23px; }
.punch-success strong { font-size: 13px; font-weight: 500; }
.punch-success p { margin: 4px 0 0; font-size: 11px; }
.retry-panel { padding: 14px; border-radius: 8px; background: #faf6ea; }
.retry-panel p { font-size: 12px; color: #826a47; margin-bottom: 12px; }
.recent-error { color: #9b6550; font-size: 12px; line-height: 1.8; }
.account-notice, .loading-state { display: grid; justify-items: start; gap: 14px; padding: 28px; border: 1px solid #e0e6d9; border-radius: 12px; background: #fffefa; }
.account-notice > .el-icon { font-size: 28px; color: #718360; }
.account-notice h2 { margin: 0; font-size: 17px; }
.account-notice p, .account-notice a, .loading-state { font-size: 12px; color: #627655; }
.account-notice p { margin: 0; }
@media (max-width: 1000px) { .home-primary-grid { grid-template-columns: 1fr; } .home-actions-column { grid-template-columns: repeat(2, minmax(0, 1fr)); } .face-entry { align-content: start; } }
@media (max-width: 600px) {
  .home-heading h1 { font-size: 25px; }
  .today-card > header { padding: 22px 20px 0; }
  .today-card h2 { font-size: 17px; }
  .today-card h2 small { display: block; margin: 6px 0 0; }
  .today-punches { padding: 25px 20px; }
  .today-punches > div + div { padding-left: 24px; }
  .today-punches strong { font-size: 36px; }
  .today-card > footer { padding: 14px 20px; }
  .home-actions-column, .home-primary-grid.location-disabled .home-actions-column { grid-template-columns: 1fr; }
  .location-workspace, .request-entry, .month-section, .recent-section { padding: 21px 18px; }
  .section-heading { gap: 10px; }
  .section-heading h2 { font-size: 14px; }
  .section-heading > a { font-size: 10px; }
  .month-metrics { gap: 8px; }
  .month-metrics > div { padding-left: 10px; }
  .month-metrics dd { font-size: 24px; }
  .month-metrics dd small { margin-left: 3px; }
  .place-row { gap: 8px; }
  .place-distance { max-width: 82px; text-align: right; line-height: 1.7; }
}
</style>
