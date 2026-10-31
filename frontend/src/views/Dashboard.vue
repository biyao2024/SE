<script setup lang="ts">
import { ref, computed } from 'vue'
import { Calendar, CircleCheck, AlarmClock, Warning, ArrowRight, Refresh } from '@element-plus/icons-vue'
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
const metrics = computed(() => [
  { label: '应出勤天数', value: summary.value?.requiredDays, unit: '天', note: '按工作日历与在职区间', icon: Calendar },
  { label: '完成打卡天数', value: summary.value?.completedDays, unit: '天', note: '含迟到、早退记录', icon: CircleCheck },
  { label: '迟到 / 早退', value: summary.value ? summary.value.lateCount + ' / ' + summary.value.earlyCount : undefined, unit: '次', note: '迟到与早退分别计次', icon: AlarmClock },
  { label: '缺卡 / 缺勤', value: summary.value ? summary.value.missingDays + ' / ' + summary.value.absentDays : undefined, unit: '天', note: '日终确认，不含请假', icon: Warning },
])
</script>

<template>
  <div class="page-head">
    <div>
      <h1>{{ auth.isAdmin ? '考勤概览' : '我的考勤' }}</h1>
      <p>{{ auth.isAdmin ? '所选日期内的出勤汇总' : '个人出勤与申请记录' }}</p>
    </div>
    <div class="head-actions">
      <el-date-picker
        v-model="range"
        type="daterange"
        value-format="YYYY-MM-DD"
        range-separator="—"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        :clearable="false"
        style="width: 250px"
        @change="load"
      />
      <el-button :icon="Refresh" :loading="loading" aria-label="刷新概览" @click="load" />
    </div>
  </div>

  <div v-if="error" class="error-panel">
    {{ error }}
    <el-button text @click="load">重试</el-button>
  </div>

  <section class="overview-summary" aria-label="出勤汇总" v-loading="loading">
    <div class="metric-grid">
      <div v-for="metric in metrics" :key="metric.label" class="metric">
        <div class="metric-label">
          <span>{{ metric.label }}</span>
          <el-icon><component :is="metric.icon" /></el-icon>
        </div>
        <div class="metric-value">{{ metric.value ?? '—' }}<small>{{ metric.unit }}</small></div>
        <div class="metric-note">{{ metric.note }}</div>
      </div>
    </div>
    <div class="summary-strip">
      <span>请假<strong>{{ summary?.leaveDays ?? '—' }}</strong>天</span>
      <span>批准补卡<strong>{{ summary?.correctionCount ?? '—' }}</strong>次</span>
    </div>
  </section>

  <AttendanceCharts :summary="summary" />

  <div class="panel">
    <div class="panel-head">
      <div>
        <h2>考勤明细</h2>
        <p>所选日期内最近 6 条记录</p>
      </div>
      <router-link to="/attendance">
        <el-button text>全部记录 <el-icon><ArrowRight /></el-icon></el-button>
      </router-link>
    </div>
    <el-table :data="rows" v-loading="loading" empty-text="所选日期暂无考勤记录">
      <el-table-column label="员工" min-width="145">
        <template #default="{ row }">
          <div class="name-cell">
            <span class="avatar">{{ row.employeeName?.slice(0, 1) }}</span>
            <div>{{ row.employeeName }}<small>{{ row.employeeNo }}</small></div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="date" label="日期" width="120" />
      <el-table-column prop="departmentName" label="部门" min-width="100" />
      <el-table-column label="签到" width="95">
        <template #default="{ row }">{{ timeText(row.checkIn) }}</template>
      </el-table-column>
      <el-table-column label="签退" width="95">
        <template #default="{ row }">{{ timeText(row.checkOut) }}</template>
      </el-table-column>
      <el-table-column label="状态" min-width="170">
        <template #default="{ row }">
          <StatusTag :status="row.status" :late="row.late" :early="row.early" />
        </template>
      </el-table-column>
    </el-table>
  </div>
  <p class="footnote">当天未结束的考勤显示为待完成。所有日期按北京时间统计。</p>
</template>
