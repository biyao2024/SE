<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Download, Search } from '@element-plus/icons-vue'
import { api, items, query, download, errorText, type Page } from '../api'
import { useAuth } from '../stores/auth'
import { useLoad, monthRange, timeText, statusNames } from '../composables'
import { ElMessage } from 'element-plus'
import StatusTag from '../components/StatusTag.vue'
const testRows=ref<any[]>([]),testError=ref('');async function loadTests(){testError.value='';try{testRows.value=await api('/attendance/test-events?'+query({from:range.value[0],to:range.value[1]}))}catch(e){testError.value=errorText(e)}}
const auth=useAuth(),range=ref<[string,string]>(monthRange()),departmentId=ref<number|undefined>(),employeeId=ref<number|undefined>(),status=ref(''),page=ref(1),size=ref(20),total=ref(0),rows=ref<any[]>([]),departments=ref<any[]>([]),employees=ref<any[]>([]),exporting=ref(false)
function params(){return{from:range.value[0],to:range.value[1],departmentId:departmentId.value,employeeId:employeeId.value,status:status.value}}
const {loading,error,load}=useLoad(async()=>{const data=await api<Page<any>>('/attendance?'+query({...params(),page:page.value-1,size:size.value}));rows.value=items(data);total.value=data.total??rows.value.length})
onMounted(async()=>{if(auth.isAdmin)try{const [d,e]=await Promise.all([api('/departments?size=200'),api('/employees?size=200')]);departments.value=items(d);employees.value=items(e)}catch(e){ElMessage.error(errorText(e))}})
function search(){page.value=1;load()}
async function exportFile(){exporting.value=true;try{await download('/attendance/export?'+query(params()),`考勤报表_${range.value[0]}_${range.value[1]}.xlsx`)}catch(e){ElMessage.error(errorText(e))}finally{exporting.value=false}}
</script>

<template>
  <div class="attendance-page">
    <header class="page-head record-heading">
      <div>
        <h1>{{ auth.isAdmin ? '考勤记录' : '我的考勤' }}</h1>
        <p>出勤明细与人工修正记录</p>
      </div>
      <el-button :icon="Download" :loading="exporting" @click="exportFile">导出报表</el-button>
    </header>

    <section class="record-surface" aria-label="正式考勤记录">
      <div class="filter-bar">
        <div class="filter-field date-field">
          <label>日期范围</label>
          <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" :clearable="false" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" />
        </div>
        <div v-if="auth.isAdmin" class="filter-field">
          <label>部门</label>
          <el-select v-model="departmentId" placeholder="全部部门" clearable aria-label="筛选部门">
            <el-option v-for="d in departments" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </div>
        <div v-if="auth.isAdmin" class="filter-field">
          <label>员工</label>
          <el-select v-model="employeeId" placeholder="全部员工" filterable clearable aria-label="筛选员工">
            <el-option v-for="e in employees" :key="e.id" :label="`${e.name} · ${e.employeeNo}`" :value="e.id" />
          </el-select>
        </div>
        <div class="filter-field status-field">
          <label>考勤状态</label>
          <el-select v-model="status" placeholder="全部状态" clearable aria-label="筛选考勤状态">
            <el-option v-for="s in ['PENDING','NORMAL','ABSENT','MISSING_IN','MISSING_OUT','LEAVE','REST']" :key="s" :label="statusNames[s]" :value="s" />
            <el-option label="迟到" value="LATE" />
            <el-option label="早退" value="EARLY" />
          </el-select>
        </div>
        <el-button class="filter-submit" :icon="Search" type="primary" @click="search">查询</el-button>
      </div>

      <div class="record-caption">
        <div class="record-title"><h2>考勤明细</h2><span class="record-count">{{ loading ? '—' : total }} 条</span></div>
        <span class="record-note">签到取首次 · 签退取末次</span>
      </div>
      <el-alert v-if="error" class="table-alert" :title="error" type="error" :closable="false" />
      <el-table :data="rows" v-loading="loading" class="record-table" empty-text="当前筛选范围暂无考勤记录">
        <el-table-column label="员工" min-width="190">
          <template #default="{ row }">
            <div class="person-cell">
              <span class="person-avatar">{{ row.employeeName?.slice(0,1) }}</span>
              <div><strong>{{ row.employeeName }}</strong><small>{{ row.employeeNo }}</small></div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="departmentName" label="部门" min-width="125" />
        <el-table-column prop="date" label="考勤日期" width="125" />
        <el-table-column label="签到" width="110">
          <template #default="{ row }"><span class="punch-time" :class="{ 'time-empty': !row.checkIn }">{{ timeText(row.checkIn) }}</span></template>
        </el-table-column>
        <el-table-column label="签退" width="110">
          <template #default="{ row }"><span class="punch-time" :class="{ 'time-empty': !row.checkOut }">{{ timeText(row.checkOut) }}</span></template>
        </el-table-column>
        <el-table-column label="考勤状态" min-width="175">
          <template #default="{ row }"><StatusTag :status="row.status" :late="row.late" :early="row.early" /></template>
        </el-table-column>
        <el-table-column label="人工修正" width="105" align="right">
          <template #default="{ row }"><span :class="row.correctionCount ? 'correction-count' : 'time-empty'">{{ row.correctionCount ? row.correctionCount + ' 次' : '—' }}</span></template>
        </el-table-column>
      </el-table>
      <footer class="record-footer">
        <span>导出包含考勤明细与月度汇总</span>
        <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total" :page-sizes="[20,50,100]" layout="prev, pager, next, sizes" @change="load" />
      </footer>
    </section>

    <details class="test-section">
      <summary>
        <div><span class="disclosure-arrow" aria-hidden="true"></span><strong>测试打卡记录</strong><span class="test-label">不计入正式报表</span></div>
        <span class="test-disclosure">查看记录</span>
      </summary>
      <div class="test-content">
        <div class="test-toolbar"><span>按上方日期范围查询</span><el-button size="small" @click="loadTests">加载测试记录</el-button></div>
        <el-alert v-if="testError" :title="testError" type="error" />
        <el-table :data="testRows" empty-text="点击加载查看测试记录">
          <el-table-column prop="employeeName" label="姓名" />
          <el-table-column prop="employeeNo" label="工号" />
          <el-table-column prop="acceptedAt" label="时间" min-width="185" />
          <el-table-column label="类型"><template #default="{ row }">{{ row.type === 'IN' ? '测试签到' : '测试签退' }}</template></el-table-column>
          <el-table-column prop="livenessStatus" label="动作结果" />
        </el-table>
      </div>
    </details>
  </div>
</template>

<style scoped>
.record-heading { margin-bottom: 26px; align-items: flex-end; }
.record-heading h1 { font-size: 28px; letter-spacing: -.7px; }
.record-heading p { color: #6e785f; margin-top: 9px; }
.record-surface { background: #fffefa; border: 1px solid #e3e6dd; border-radius: 12px; overflow: hidden; }
.filter-bar { display: flex; align-items: flex-end; gap: 14px; flex-wrap: wrap; padding: 23px 25px; background: #f8f9f4; border-bottom: 1px solid #e7e9e1; }
.filter-field { display: flex; flex: 1 1 120px; min-width: 120px; flex-direction: column; gap: 8px; }
.filter-field label { font-size: 11px; font-weight: 500; color: #6e785f; }
.filter-field :deep(.el-select) { width: 100%; }
.date-field { flex: 1.7 1 260px; }
.date-field :deep(.el-date-editor) { width: 100%; min-width: 0; }
.status-field { flex-grow: .8; }
.filter-submit { flex-shrink: 0; margin-left: 0; }
.record-caption { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 24px 25px 19px; }
.record-title { display: flex; align-items: center; gap: 12px; }
.record-title h2 { margin: 0; font-size: 15px; font-weight: 600; }
.record-count { color: #66785e; background: #eef2e9; font-size: 11px; padding: 3px 8px; border-radius: 5px; font-variant-numeric: tabular-nums; }
.record-note { color: #6e785f; font-size: 11px; }
.table-alert { margin: 0 24px 16px; width: auto; }
.record-table { --el-table-header-bg-color: #fffefa; --el-table-tr-bg-color: #fffefa; --el-table-row-hover-bg-color: #f6f8f1; }
.record-table :deep(th.el-table__cell) { border-top: 1px solid #edf0e7; font-size: 11px; height: 40px; }
.record-table :deep(td.el-table__cell) { height: 78px; }
.record-table :deep(th:first-child .cell), .record-table :deep(td:first-child .cell) { padding-left: 25px; }
.record-table :deep(th:last-child .cell), .record-table :deep(td:last-child .cell) { padding-right: 25px; }
.person-cell { display: flex; align-items: center; gap: 12px; }
.person-avatar { display: grid; place-items: center; width: 36px; height: 38px; border: 1px solid #dce4d6; background: #eaf0e3; color: #627357; border-radius: 10px; font-size: 14px; flex-shrink: 0; }
.person-cell strong { font-size: 13px; font-weight: 500; }
.person-cell small { display: block; margin-top: 5px; font-size: 11px; color: #6e785f; font-variant-numeric: tabular-nums; }
.punch-time { font-size: 14px; font-variant-numeric: tabular-nums; color: #444f40; }
.time-empty { color: #b1b5aa; }
.correction-count { color: #6e785f; font-size: 12px; }
.record-footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 18px 25px; background: #fdfdf9; }
.record-footer > span { color: #6e785f; font-size: 11px; }
.record-footer :deep(.el-pagination) { flex-wrap: wrap; gap: 3px; }
.test-section { margin-top: 24px; border-top: 1px solid #dfe4d8; border-bottom: 1px solid #dfe4d8; }
.test-section summary { list-style: none; display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 21px 2px; cursor: pointer; }
.test-section summary::-webkit-details-marker { display: none; }
.test-section summary > div { display: flex; align-items: center; gap: 13px; }
.test-section summary strong { font-size: 13px; font-weight: 500; color: #58634f; }
.disclosure-arrow { border-right: 1.5px solid #7c8972; border-bottom: 1.5px solid #7c8972; width: 5px; height: 5px; transform: rotate(-45deg); margin: 0 4px; transition: transform .15s; }
.test-section[open] .disclosure-arrow { transform: rotate(45deg); }
.test-label, .test-disclosure { font-size: 11px; color: #6e785f; }
.test-content { padding: 0 0 18px; }
.test-toolbar { display: flex; align-items: center; justify-content: space-between; padding: 0 0 15px; font-size: 12px; color: #6e785f; }
@media (max-width: 800px) {
  .record-heading { align-items: flex-start; }
  .filter-bar { padding: 18px; gap: 13px 12px; }
  .date-field { flex-basis: 100%; }
  .record-caption { padding: 20px 18px 16px; }
  .record-note { display: none; }
  .record-footer { align-items: flex-start; flex-direction: column; padding: 17px 18px; overflow-x: auto; }
  .test-label { display: none; }
}
</style>
