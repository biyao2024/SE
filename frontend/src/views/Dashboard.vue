<script setup lang="ts">
import { ref, computed } from 'vue'
import { Calendar, CircleCheck, DocumentChecked, ArrowRight, Refresh } from '@element-plus/icons-vue'
import { api, query, items } from '../api'
import { useAuth } from '../stores/auth'
import { useLoad, monthRange, timeText } from '../composables'
import StatusTag from '../components/StatusTag.vue'
import AttendanceCharts from '../components/AttendanceCharts.vue'

const auth = useAuth()
const range = ref<[string, string]>(monthRange())
const summary = ref<any>(null)
const rows = ref<any[]>([])
const { loading, error, load } = useLoad(async () => {
  const params = { from: range.value[0], to: range.value[1] }
  const [s, r] = await Promise.all([
    api('/attendance/summary?' + query(params)),
    api('/attendance?' + query({ ...params, page: 0, size: 6 })),
  ])
  summary.value = s
  rows.value = items(r)
})
const dayUnit = computed(() => auth.isAdmin ? '人·天' : '天')
const recentActivity = computed<{date: string; completed: number}[]>(() => (summary.value?.trend || []).slice(-7))
const activityMax = computed(() => Math.max(1, ...recentActivity.value.map(day => day.completed)))
const anomalies = computed(() => [
  { label: '迟到', value: summary.value?.lateCount, unit: '次', tone: 'amber' },
  { label: '早退', value: summary.value?.earlyCount, unit: '次', tone: 'amber' },
  { label: '缺卡', value: summary.value?.missingDays, unit: dayUnit.value, tone: 'clay' },
  { label: '缺勤', value: summary.value?.absentDays, unit: dayUnit.value, tone: 'clay' },
])
function number(value?: number) { return value === undefined ? '—' : value.toLocaleString('zh-CN') }
</script>

<template>
  <div class="dashboard-page">
    <header class="dashboard-heading">
      <div class="heading-copy">
        <div class="heading-label"><span />{{ auth.isAdmin ? '团队出勤' : '个人出勤' }}</div>
        <h1>{{ auth.isAdmin ? '考勤概览' : '我的考勤' }}</h1>
        <p>所选日期内的出勤与异常汇总</p>
      </div>
      <div class="period-controls">
        <span class="period-label">统计日期</span>
        <div class="period-inputs">
          <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" :clearable="false" @change="load" />
          <el-button :icon="Refresh" :loading="loading" aria-label="刷新概览" @click="load" />
        </div>
      </div>
    </header>
    <div v-if="error" class="error-panel">{{ error }}<el-button text @click="load">重试</el-button></div>

    <section class="metric-grid overview-mosaic" aria-label="出勤汇总" v-loading="loading">
      <article class="completion-card">
        <div class="completion-top">
          <span><el-icon><CircleCheck /></el-icon>完成打卡</span>
          <span class="scope-badge">{{ auth.isAdmin ? '全部人员' : '本人' }}</span>
        </div>
        <div class="completion-value">{{ number(summary?.completedDays) }}<span>{{ dayUnit }}</span></div>
        <p class="completion-description">已完成签到与签退，含迟到、早退</p>
        <div class="completion-bottom">
          <div class="required-total">
            <span><el-icon><Calendar /></el-icon>应出勤</span>
            <div><strong>{{ number(summary?.requiredDays) }}</strong><small>{{ dayUnit }}</small></div>
          </div>
          <div v-if="recentActivity.length" class="activity-mini">
            <div class="activity-bars" role="img" :aria-label="'最近 ' + recentActivity.length + ' 个统计日的完成打卡记录'">
              <div v-for="day in recentActivity" :key="day.date" class="activity-column" :title="day.date + '：完成打卡 ' + day.completed + ' 条'">
                <span :style="{ height: (day.completed / activityMax * 100) + '%' }" />
              </div>
            </div>
            <span class="activity-caption">最近 {{ recentActivity.length }} 个统计日</span>
          </div>
          <span v-else class="activity-empty">暂无趋势记录</span>
        </div>
      </article>
      <article class="anomalies-card">
        <header class="card-heading"><h2>出勤异常</h2><span>所选日期</span></header>
        <div class="anomaly-grid">
          <div v-for="item in anomalies" :key="item.label" class="anomaly-item" :data-tone="item.tone">
            <div class="anomaly-label"><i />{{ item.label }}</div>
            <div class="anomaly-value">{{ number(item.value) }}<small>{{ item.unit }}</small></div>
          </div>
        </div>
        <p class="card-caption">缺卡与缺勤在日终确认，不含请假。</p>
      </article>
      <article class="requests-card">
        <header class="card-heading"><h2>请假与补卡</h2><el-icon><DocumentChecked /></el-icon></header>
        <div class="request-totals">
          <div class="request-total"><span>请假</span><div><strong>{{ number(summary?.leaveDays) }}</strong><small>{{ dayUnit }}</small></div></div>
          <div class="request-total"><span>批准补卡</span><div><strong>{{ number(summary?.correctionCount) }}</strong><small>次</small></div></div>
        </div>
        <router-link to="/requests" class="requests-link">{{ auth.isAdmin ? '查看申请与审批' : '查看我的申请' }}<el-icon><ArrowRight /></el-icon></router-link>
      </article>
    </section>

    <AttendanceCharts :summary="summary" />

    <section class="records-panel" aria-labelledby="recent-records-title">
      <header class="records-heading">
        <div><div class="records-title"><h2 id="recent-records-title">最近考勤</h2><span>{{ rows.length }} 条</span></div><p>所选日期内最近 6 条记录</p></div>
        <router-link to="/attendance" class="records-link">全部记录<el-icon><ArrowRight /></el-icon></router-link>
      </header>
      <div class="records-table">
        <el-table :data="rows" v-loading="loading" empty-text="所选日期暂无考勤记录">
          <el-table-column label="员工" min-width="155"><template #default="{ row }"><div class="name-cell"><span class="avatar">{{ row.employeeName?.slice(0, 1) }}</span><div>{{ row.employeeName }}<small>{{ row.employeeNo }}</small></div></div></template></el-table-column>
          <el-table-column prop="date" label="日期" width="125" />
          <el-table-column prop="departmentName" label="部门" min-width="110" />
          <el-table-column label="签到" width="100"><template #default="{ row }"><span class="punch-time">{{ timeText(row.checkIn) }}</span></template></el-table-column>
          <el-table-column label="签退" width="100"><template #default="{ row }"><span class="punch-time">{{ timeText(row.checkOut) }}</span></template></el-table-column>
          <el-table-column label="状态" min-width="170"><template #default="{ row }"><StatusTag :status="row.status" :late="row.late" :early="row.early" /></template></el-table-column>
        </el-table>
      </div>
      <div class="records-mobile" v-loading="loading">
        <article v-for="(row, index) in rows" :key="row.id ?? index" class="mobile-record">
          <div class="mobile-record-top">
            <div class="name-cell"><span class="avatar">{{ row.employeeName?.slice(0, 1) }}</span><div>{{ row.employeeName }}<small>{{ row.employeeNo }} · {{ row.departmentName }}</small></div></div>
            <StatusTag :status="row.status" :late="row.late" :early="row.early" />
          </div>
          <div class="mobile-record-times"><span>{{ row.date }}</span><span>签到 <strong>{{ timeText(row.checkIn) }}</strong></span><span>签退 <strong>{{ timeText(row.checkOut) }}</strong></span></div>
        </article>
        <div v-if="!rows.length" class="empty-note">所选日期暂无考勤记录</div>
      </div>
      <footer class="records-footer"><span>当天未结束的考勤显示为待完成。</span><span>北京时间</span></footer>
    </section>
  </div>
</template>

<style scoped>
.dashboard-page { color: var(--text-primary); }
.dashboard-heading { display: flex; justify-content: space-between; align-items: flex-end; gap: 24px; margin-bottom: 28px; }
.heading-label { display: flex; align-items: center; gap: 7px; margin-bottom: 10px; color: #65776b; font-size: 11px; }
.heading-label > span { width: 5px; height: 5px; border-radius: 50%; background: #6d8a74; }
.heading-copy h1 { margin: 0; font-size: 30px; letter-spacing: -1px; }
.heading-copy p { margin: 8px 0 0; font-size: 12px; color: var(--text-secondary); }
.period-controls { flex-shrink: 0; }
.period-label { display: block; margin-bottom: 8px; color: var(--text-muted); font-size: 11px; }
.period-inputs { display: flex; align-items: center; gap: 8px; }
.period-inputs :deep(.el-date-editor) { width: 270px; min-width: 0; }
.period-inputs :deep(.el-button) { width: 36px; padding: 8px; }
.overview-mosaic { display: grid; grid-template-columns: minmax(0, 1.42fr) minmax(0, 1.08fr) minmax(0, .8fr); gap: 18px; margin-bottom: 24px; }
.overview-mosaic > article { min-width: 0; border-radius: 12px; overflow: hidden; }
.completion-card { display: flex; flex-direction: column; padding: 24px 27px 22px; background: #254b3b; color: #fffef7; }
.completion-top { display: flex; align-items: center; justify-content: space-between; gap: 12px; font-size: 13px; }
.completion-top > span:first-child { display: flex; align-items: center; gap: 9px; }
.completion-top .el-icon { color: #b8ccb6; font-size: 18px; }
.scope-badge { border: 1px solid #577262; border-radius: 20px; padding: 4px 9px; color: #c8d7ca; font-size: 10px; white-space: nowrap; }
.completion-value { display: flex; flex-wrap: wrap; align-items: baseline; gap: 10px; margin: 21px 0 5px; font-size: clamp(42px, 4.5vw, 64px); font-weight: 500; line-height: 1.1; letter-spacing: -2.5px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.completion-value > span { font-size: 12px; font-weight: 400; color: #bbccbd; letter-spacing: 0; }
.completion-description { margin: 0 0 23px; color: #bdcfc0; font-size: 11px; line-height: 1.8; }
.completion-bottom { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: flex-end; gap: 20px; margin-top: auto; padding-top: 17px; border-top: 1px solid #4b6a57; }
.required-total > span { display: flex; align-items: center; gap: 6px; color: #bbccbd; font-size: 11px; }
.required-total > div { display: flex; align-items: baseline; gap: 7px; margin-top: 7px; }
.required-total strong { font-size: 25px; font-weight: 500; letter-spacing: -.5px; font-variant-numeric: tabular-nums; }
.required-total small { font-size: 10px; color: #bbccbd; white-space: nowrap; }
.activity-mini { width: 116px; flex-shrink: 0; text-align: right; }
.activity-bars { display: flex; height: 38px; align-items: flex-end; gap: 5px; }
.activity-column { flex: 1; height: 100%; display: flex; align-items: flex-end; border-bottom: 1px solid #7b947c; }
.activity-column > span { display: block; width: 100%; border-radius: 2px 2px 0 0; background: #a9bd99; }
.activity-column:last-child > span { background: #e0e5b9; }
.activity-caption, .activity-empty { display: block; margin-top: 6px; font-size: 9px; color: #bbcabc; }
.anomalies-card { display: flex; flex-direction: column; padding: 24px; background: #fffefa; border: 1px solid #e3e5dc; }
.card-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.card-heading h2 { margin: 0; font-size: 14px; font-weight: 600; }
.card-heading > span { color: var(--text-muted); font-size: 10px; }
.anomaly-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 20px; margin-top: 10px; }
.anomaly-item { padding: 15px 0 12px; min-width: 0; }
.anomaly-item:nth-child(n+3) { border-top: 1px solid #ebebe3; }
.anomaly-label { display: flex; align-items: center; gap: 6px; color: #77766b; font-size: 11px; }
.anomaly-label i { width: 4px; height: 4px; background: #b39a67; border-radius: 50%; }
.anomaly-item[data-tone="clay"] .anomaly-label i { background: #b77d68; }
.anomaly-value { display: flex; align-items: baseline; flex-wrap: wrap; gap: 6px; margin-top: 6px; color: #4b4b3d; font-size: 30px; font-weight: 500; line-height: 1.1; letter-spacing: -.8px; font-variant-numeric: tabular-nums; }
.anomaly-item[data-tone="clay"] .anomaly-value { color: #93664f; }
.anomaly-value small { font-size: 10px; font-weight: 400; color: var(--text-muted); letter-spacing: 0; }
.card-caption { margin: auto 0 0; padding-top: 13px; font-size: 10px; line-height: 1.8; color: var(--text-muted); }
.requests-card { display: flex; flex-direction: column; padding: 24px 21px 20px; background: #eef0e8; border: 1px solid #e0e4d8; }
.requests-card .card-heading > .el-icon { font-size: 18px; color: #8b9b82; }
.request-totals { display: flex; flex-direction: column; flex: 1; }
.request-total { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 22px 0; }
.request-total + .request-total { border-top: 1px solid #dce2d4; }
.request-total > span { font-size: 11px; color: #69745e; }
.request-total > div { display: flex; align-items: baseline; gap: 5px; }
.request-total strong { color: #344835; font-size: 28px; font-weight: 500; font-variant-numeric: tabular-nums; }
.request-total small { color: #74816b; font-size: 10px; }
.requests-link { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin-top: auto; padding-top: 17px; border-top: 1px solid #dce2d4; color: #506249; font-size: 11px; }
.requests-link:hover, .records-link:hover { color: #223c2d; }
.records-panel { min-width: 0; border: 1px solid var(--border); border-radius: 12px; background: var(--surface); overflow: hidden; }
.records-heading { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 24px 26px 20px; }
.records-title { display: flex; align-items: center; gap: 9px; }
.records-title h2 { margin: 0; font-size: 16px; }
.records-title > span { padding: 2px 7px; border-radius: 4px; background: #f0f2eb; font-size: 10px; color: #7b8574; }
.records-heading p { margin: 5px 0 0; color: var(--text-muted); font-size: 11px; }
.records-link { display: inline-flex; align-items: center; gap: 9px; font-size: 12px; color: #526b57; white-space: nowrap; }
.records-table { padding: 0 14px; min-width: 0; }
.records-mobile { display: none; }
.records-table :deep(.el-table) { --el-table-header-bg-color: #f7f8f3; }
.records-table :deep(.el-table td.el-table__cell) { height: 66px; }
.records-table :deep(.el-table th.el-table__cell) { height: 40px; }
.records-table .avatar { background: #e9eee3; color: #627356; border-radius: 50%; }
.punch-time { font-weight: 500; letter-spacing: .25px; font-variant-numeric: tabular-nums; }
.records-footer { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 8px; padding: 14px 26px; background: #fcfcf8; border-top: 1px solid #edf0e8; color: var(--text-muted); font-size: 10px; }
@media (max-width: 1180px) {
  .overview-mosaic { grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr); }
  .requests-card { grid-column: 1 / -1; display: grid; grid-template-columns: minmax(120px, .8fr) minmax(0, 1.5fr) auto; align-items: center; gap: 26px; padding: 20px 24px; }
  .request-totals { flex-direction: row; gap: 28px; }
  .request-total { flex: 1; padding: 0; }
  .request-total + .request-total { border-top: 0; padding-left: 24px; border-left: 1px solid #dce2d4; }
  .requests-link { padding: 0; border: 0; margin: 0; }
  .requests-card .card-heading > .el-icon { display: none; }
}
@media (max-width: 900px) {
  .dashboard-heading { align-items: flex-start; flex-direction: column; gap: 20px; }
  .period-controls { width: 100%; }
  .period-inputs :deep(.el-date-editor) { max-width: 300px; flex: 1; }
  .requests-card { gap: 20px; grid-template-columns: 1fr 2fr; }
  .requests-link { grid-column: 1 / -1; padding-top: 14px; border-top: 1px solid #dce2d4; justify-content: flex-end; }
}
@media (max-width: 600px) {
  .dashboard-heading { margin-bottom: 22px; }
  .heading-copy h1 { font-size: 28px; }
  .overview-mosaic { grid-template-columns: minmax(0, 1fr); gap: 14px; }
  .completion-card { padding: 24px; }
  .completion-value { font-size: 60px; }
  .activity-mini { width: 132px; }
  .anomalies-card { padding: 21px 24px; }
  .anomaly-grid { column-gap: 30px; }
  .anomaly-item { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 7px; padding: 16px 0; }
  .anomaly-value { margin-top: 0; font-size: 27px; }
  .card-caption { padding-top: 5px; }
  .requests-card { grid-template-columns: minmax(0, 1fr); gap: 18px; padding: 21px 24px; }
  .request-totals { gap: 22px; }
  .request-total + .request-total { padding-left: 22px; }
  .requests-link { justify-content: space-between; }
  .records-heading { padding: 21px 18px 18px; }
  .records-table { display: none; }
  .records-mobile { display: block; }
  .mobile-record { padding: 18px; border-top: 1px solid #edf0e8; }
  .mobile-record-top { display: flex; justify-content: space-between; align-items: center; gap: 14px; }
  .mobile-record-top .name-cell { font-size: 12px; min-width: 0; }
  .mobile-record-top .name-cell small { font-size: 9px; line-height: 1.5; }
  .mobile-record-top :deep(.status-stack) { justify-content: flex-end; max-width: 112px; flex-shrink: 0; }
  .mobile-record-times { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 10px; margin-top: 16px; color: var(--text-muted); font-size: 10px; font-variant-numeric: tabular-nums; }
  .mobile-record-times strong { color: var(--text-primary); font-size: 11px; font-weight: 500; margin-left: 4px; }
  .records-footer { padding: 13px 18px; line-height: 1.7; }
}
</style>
