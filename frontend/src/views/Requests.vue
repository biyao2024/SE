<script setup lang="ts">
import { ref, reactive, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { api, post, items, query, errorText } from '../api'
import { today } from '../composables'
import { useAuth } from '../stores/auth'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '../components/StatusTag.vue'
import { correctionDateEligible } from '../locationMath.mjs'
const route=useRoute(),router=useRouter()
const requestStatuses=['PENDING','APPROVED','REJECTED','CANCELLED']
const auth=useAuth(),tab=ref<'leave'|'correction'>(route.query.requestType==='correction'?'correction':'leave'),rows=ref<any[]>([]),page=ref(1),total=ref(0),status=ref(typeof route.query.status==='string'&&requestStatuses.includes(route.query.status)?route.query.status:''),dialog=ref(false),saving=ref(false),formError=ref(''),reviewOpen=ref(false),reviewRow=ref<any>(null),approved=ref(true),comment=ref('')
const form=reactive({range:[] as string[],type:'PERSONAL',reason:'',date:'',requestedTime:'',punchType:'IN'})
const leaveTypes:Record<string,string>={PERSONAL:'事假',SICK:'病假',ANNUAL:'年假',OTHER:'其他'}
const path=()=>`/${tab.value}-requests`
const loading=ref(false),error=ref(''),reviewPath=ref('')
let loadVersion=0
async function load(){
  const current=++loadVersion,resource=path(),params={page:page.value-1,size:20,status:status.value}
  loading.value=true;error.value='';rows.value=[]
  try{const data=await api(resource+'?'+query(params));if(current!==loadVersion)return;rows.value=items(data);total.value=data.total??rows.value.length}
  catch(e){if(current===loadVersion)error.value=errorText(e)}
  finally{if(current===loadVersion)loading.value=false}
}
onMounted(load)
onBeforeUnmount(()=>{loadVersion++})
let applyingQuery=false,queryHandled=false
watch(tab,()=>{if(applyingQuery)return;reviewOpen.value=false;dialog.value=false;page.value=1;status.value='';load()},{flush:'sync'})
function newRequest(){Object.assign(form,{range:[],type:'PERSONAL',reason:'',date:'',requestedTime:'',punchType:'IN'});formError.value='';dialog.value=true}
function disabledDate(date:Date){const value=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(date);const lower=new Date(`${today()}T00:00:00+08:00`);lower.setUTCDate(lower.getUTCDate()-7);const first=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(lower);return value>=today()||value<first}
async function submit(){if(!form.reason.trim()){formError.value='请填写申请原因';return}if(tab.value==='leave'&&form.range.length!==2){formError.value='请选择完整的请假日期区间';return}if(tab.value==='correction'&&(!form.date||!form.requestedTime)){formError.value='请选择补卡日期和时间';return}saving.value=true;formError.value='';try{await post(path(),tab.value==='leave'?{startDate:form.range[0],endDate:form.range[1],type:form.type,reason:form.reason.trim()}:{date:form.date,type:form.punchType,requestedTime:form.requestedTime,reason:form.reason.trim()});ElMessage.success('申请已提交');dialog.value=false;page.value=1;load()}catch(e){formError.value=errorText(e)}finally{saving.value=false}}
async function cancel(row:any){const resource=path();try{await ElMessageBox.confirm('撤销后如需继续办理，需要重新提交申请。','撤销申请',{confirmButtonText:'撤销申请',cancelButtonText:'保留申请'});await post(`${resource}/${row.id}/cancel`);ElMessage.success('申请已撤销');load()}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(errorText(e))}}
function review(row:any,value:boolean){reviewPath.value=path();reviewRow.value=row;approved.value=value;comment.value='';formError.value='';reviewOpen.value=true}
async function submitReview(){if(!approved.value&&!comment.value.trim()){formError.value='请填写驳回原因，方便申请人了解处理结果';return}saving.value=true;formError.value='';try{await post(`${reviewPath.value}/${reviewRow.value.id}/review`,{approved:approved.value,comment:comment.value.trim()});reviewOpen.value=false;ElMessage.success(approved.value?'申请已批准':'申请已驳回');load()}catch(e){formError.value=errorText(e)}finally{saving.value=false}}
function own(row:any){return row.employeeId===auth.user?.employeeId||row.userId===auth.user?.id}
watch(()=>route.fullPath,()=>{
  const requestedType=route.query.requestType==='correction'?'correction':route.query.requestType==='leave'?'leave':tab.value
  const requestedStatus=typeof route.query.status==='string'&&requestStatuses.includes(route.query.status)?route.query.status:''
  const changed=tab.value!==requestedType||status.value!==requestedStatus
  if(tab.value!==requestedType){reviewOpen.value=false;dialog.value=false}
  applyingQuery=true;tab.value=requestedType;status.value=requestedStatus;applyingQuery=false
  if(changed&&queryHandled){page.value=1;load()}
  queryHandled=true
  if(route.query.new!=='1'||!auth.user?.employeeId)return
  newRequest()
  if(requestedType==='correction'){
    if(route.query.punchType==='IN'||route.query.punchType==='OUT')form.punchType=route.query.punchType
    if(typeof route.query.date==='string'){
      if(correctionDateEligible(route.query.date,today()))form.date=route.query.date
      else formError.value='所选日期不在可补卡的日期范围内，请重新选择。'
    }
  }
  const nextQuery:LocationQueryRaw={...route.query,requestType:requestedType}
  for(const key of ['new','date','punchType','requestedTime'])delete nextQuery[key]
  void router.replace({path:route.path,query:nextQuery}).catch(()=>{})
},{immediate:true})
</script>

<template>
  <div class="requests-page">
    <header class="page-head requests-heading">
      <div><h1>{{ auth.isAdmin ? '申请与审批' : '请假与补卡' }}</h1><p>{{ auth.isAdmin ? '查看申请，处理待审批事项' : '提交申请与查看处理进度' }}</p></div>
      <el-button type="primary" :icon="Plus" :disabled="!auth.user?.employeeId" @click="newRequest">{{ tab === 'leave' ? '申请请假' : '申请补卡' }}</el-button>
    </header>
    <el-alert v-if="!auth.user?.employeeId" :title="auth.isAdmin ? '当前账号可处理审批。提交个人申请需先关联员工档案。' : '账号尚未关联员工档案，请联系管理员完成关联。'" type="info" :closable="false" />

    <section class="request-surface" aria-label="申请列表">
      <div class="request-category">
        <el-tabs v-model="tab" class="request-tabs">
          <el-tab-pane name="leave"><template #label><span class="category-label">请假申请<small>按天</small></span></template></el-tab-pane>
          <el-tab-pane name="correction"><template #label><span class="category-label">补卡申请<small>近 7 日</small></span></template></el-tab-pane>
        </el-tabs>
        <p class="category-note">{{ tab === 'leave' ? '按整天提交请假，审批后计入考勤。' : '补卡限最近 7 个已结束自然日中的工作日。' }}</p>
      </div>

      <div class="request-toolbar">
        <div class="request-filter">
          <span class="filter-label">处理状态</span>
          <el-select v-model="status" placeholder="全部状态" clearable aria-label="筛选处理状态" @change="page=1;load()">
            <el-option label="待审批" value="PENDING" /><el-option label="已批准" value="APPROVED" /><el-option label="已驳回" value="REJECTED" /><el-option label="已撤销" value="CANCELLED" />
          </el-select>
          <span class="request-total">{{ loading ? '—' : total }} 项申请</span>
        </div>
        <el-button class="refresh-button" :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
      <el-alert v-if="error" class="request-alert" :title="error" type="error" :closable="false" />

      <el-table :data="rows" v-loading="loading" class="request-table" empty-text="当前筛选范围暂无申请">
        <el-table-column label="申请类型" min-width="130">
          <template #default="{ row }">
            <div class="request-type"><span class="type-indicator" :class="{ correction: tab === 'correction' }" aria-hidden="true"></span><strong>{{ tab === 'leave' ? (leaveTypes[row.type] || row.type) : row.type === 'IN' ? '补签到' : '补签退' }}</strong></div>
            <small class="request-number">申请 #{{ row.id }}</small>
          </template>
        </el-table-column>
        <el-table-column v-if="auth.isAdmin" label="申请人" min-width="145">
          <template #default="{ row }"><div class="applicant"><span class="applicant-avatar">{{ row.employeeName?.slice(0,1) }}</span><span>{{ row.employeeName }}</span></div></template>
        </el-table-column>
        <el-table-column label="申请日期 / 时间" min-width="170">
          <template #default="{ row }"><div class="request-date"><strong>{{ tab === 'leave' ? row.startDate : row.date }}</strong><small>{{ tab === 'leave' ? (row.startDate === row.endDate ? '全天' : '至 ' + row.endDate) : row.requestedTime }}</small></div></template>
        </el-table-column>
        <el-table-column label="申请原因" min-width="210" show-overflow-tooltip>
          <template #default="{ row }"><p class="request-reason" :title="row.reason">{{ row.reason }}</p></template>
        </el-table-column>
        <el-table-column label="处理状态" width="105"><template #default="{ row }"><StatusTag :status="row.status" request /></template></el-table-column>
        <el-table-column label="审批意见" min-width="170" show-overflow-tooltip><template #default="{ row }"><span class="review-comment" :class="{ empty: !row.reviewComment && !row.comment }">{{ row.reviewComment || row.comment || '—' }}</span></template></el-table-column>
        <el-table-column label="操作" min-width="155" fixed="right" align="right">
          <template #default="{ row }">
            <div v-if="row.status === 'PENDING'" class="review-actions">
              <template v-if="auth.isAdmin && !own(row)">
                <el-button class="approve-button" plain size="small" @click="review(row,true)">批准</el-button>
                <el-button text type="danger" size="small" @click="review(row,false)">驳回</el-button>
              </template>
              <el-button v-if="own(row) || !auth.isAdmin" text size="small" @click="cancel(row)">撤销申请</el-button>
            </div>
            <span v-else class="processed-label">已处理</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="request-mobile-list" v-loading="loading" aria-label="申请记录列表">
        <p v-if="!rows.length && !loading" class="mobile-empty">当前筛选范围暂无申请</p>
        <article v-for="row in rows" :key="row.id" class="request-card">
          <header class="request-card-header">
            <div><div class="request-type"><span class="type-indicator" :class="{ correction: tab === 'correction' }" aria-hidden="true"></span><strong>{{ tab === 'leave' ? (leaveTypes[row.type] || row.type) : row.type === 'IN' ? '补签到' : '补签退' }}</strong></div><small class="request-number">申请 #{{ row.id }}</small></div>
            <StatusTag :status="row.status" request />
          </header>
          <div class="request-card-body">
            <div v-if="auth.isAdmin" class="mobile-applicant"><span>申请人</span><div class="applicant"><span class="applicant-avatar">{{ row.employeeName?.slice(0,1) }}</span><strong>{{ row.employeeName }}</strong></div></div>
            <dl class="request-card-details">
              <div><dt>{{ tab === 'leave' ? '请假日期' : '补卡日期' }}</dt><dd>{{ tab === 'leave' ? (row.startDate === row.endDate ? row.startDate + ' · 全天' : row.startDate + ' 至 ' + row.endDate) : row.date }}</dd></div>
              <div v-if="tab === 'correction'"><dt>补卡时间</dt><dd>{{ row.requestedTime }}</dd></div>
              <div><dt>申请原因</dt><dd class="mobile-reason">{{ row.reason }}</dd></div>
              <div v-if="row.reviewComment || row.comment" class="mobile-review-note"><dt>审批意见</dt><dd>{{ row.reviewComment || row.comment }}</dd></div>
            </dl>
          </div>
          <footer v-if="row.status === 'PENDING'" class="request-card-actions">
            <template v-if="auth.isAdmin && !own(row)">
              <el-button text type="danger" @click="review(row,false)">驳回申请</el-button>
              <el-button class="approve-button" plain @click="review(row,true)">批准申请</el-button>
            </template>
            <el-button v-if="own(row) || !auth.isAdmin" text @click="cancel(row)">撤销申请</el-button>
          </footer>
        </article>
      </div>
      <footer class="request-footer">
        <span>管理员不能审批本人申请</span>
        <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="prev,pager,next" @current-change="load" />
      </footer>
    </section>
    <p v-if="tab === 'correction'" class="request-footnote">补卡批准后保留原始打卡记录，并记录人工修正。</p>
<el-dialog v-model="dialog" :title="tab==='leave'?'新建请假申请':'新建补卡申请'" width="550px" :close-on-click-modal="false"><el-alert v-if="formError" :title="formError" type="error" :closable="false"/><el-form label-position="top"><template v-if="tab==='leave'"><el-form-item label="请假日期（整天）" required><el-date-picker v-model="form.range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" style="width:100%"/></el-form-item><el-form-item label="请假类型" required><el-select v-model="form.type"><el-option v-for="(label,key) in leaveTypes" :key="key" :value="key" :label="label"/></el-select></el-form-item></template><template v-else><el-form-item label="补卡日期" required><el-date-picker v-model="form.date" type="date" value-format="YYYY-MM-DD" :disabled-date="disabledDate" style="width:100%"/></el-form-item><div class="form-grid"><el-form-item label="补卡类型" required><el-select v-model="form.punchType"><el-option label="补签到" value="IN"/><el-option label="补签退" value="OUT"/></el-select></el-form-item><el-form-item label="申请时间" required><el-time-picker v-model="form.requestedTime" format="HH:mm" value-format="HH:mm" style="width:100%"/></el-form-item></div></template><el-form-item label="申请原因" required><el-input v-model="form.reason" type="textarea" :rows="4" maxlength="500" show-word-limit placeholder="请填写申请原因"/></el-form-item></el-form><template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="submit">提交申请</el-button></template></el-dialog><el-dialog v-model="reviewOpen" :title="approved?'批准申请':'驳回申请'" width="480px"><div class="review-context"><div class="review-context-grid"><div><label>申请人</label><strong>{{ reviewRow?.employeeName }}</strong></div><div><label>申请日期</label><strong>{{ tab === 'leave' ? reviewRow?.startDate + ' 至 ' + reviewRow?.endDate : reviewRow?.date }}</strong></div><div v-if="tab === 'correction'"><label>补卡时间</label><strong>{{ reviewRow?.requestedTime }} · {{ reviewRow?.type === 'IN' ? '签到' : '签退' }}</strong></div><div v-else><label>请假类型</label><strong>{{ leaveTypes[reviewRow?.type] || reviewRow?.type }}</strong></div></div><div class="review-context-reason"><label>申请原因</label><p>{{ reviewRow?.reason }}</p></div></div><el-alert v-if="formError" :title="formError" type="error" :closable="false"/><el-input v-model="comment" type="textarea" :rows="4" maxlength="255" show-word-limit :placeholder="approved?'审批意见（可选）':'请说明驳回原因'"/><template #footer><el-button @click="reviewOpen=false">取消</el-button><el-button :type="approved?'primary':'danger'" :loading="saving" @click="submitReview">确认{{approved?'批准':'驳回'}}</el-button></template></el-dialog>
  </div>
</template>

<style scoped>
.requests-heading { margin-bottom: 27px; align-items: flex-end; }
.requests-heading h1 { font-size: 28px; letter-spacing: -.7px; }
.requests-heading p { color: #6e785f; margin-top: 9px; }
.request-surface { background: #fffefa; border: 1px solid #e1e6d9; border-radius: 12px; overflow: hidden; min-width: 0; }
.request-category { display: flex; align-items: center; justify-content: space-between; gap: 24px; padding: 0 25px; border-bottom: 1px solid #e3e8db; }
.request-tabs { min-width: 260px; }
.request-tabs :deep(.el-tabs__header) { margin: 0; }
.request-tabs :deep(.el-tabs__nav-wrap::after) { display: none; }
.request-tabs :deep(.el-tabs__item) { height: 67px; font-size: 14px; color: #6e785f; padding: 0 23px; }
.request-tabs :deep(.el-tabs__item:first-child) { padding-left: 0; }
.request-tabs :deep(.el-tabs__item.is-active) { color: #465f38; font-weight: 600; }
.request-tabs :deep(.el-tabs__active-bar) { height: 3px; border-radius: 3px 3px 0 0; background: #7f9868; }
.request-tabs :deep(.el-tabs__content) { display: none; }
.category-label { display: flex; align-items: center; gap: 8px; }
.category-label small { border: 1px solid #e4e9dc; background: #f7f9f2; color: #6e785f; border-radius: 4px; padding: 2px 5px; font-size: 10px; font-weight: 400; line-height: 1.5; }
.category-note { margin: 0; font-size: 11px; color: #6e785f; text-align: right; line-height: 1.8; }
.request-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 20px; padding: 21px 25px; background: #fcfdf8; }
.request-filter { display: flex; align-items: center; gap: 13px; flex-wrap: wrap; }
.filter-label { color: #6e785f; font-size: 11px; }
.request-filter :deep(.el-select) { width: 140px; }
.request-total { border-left: 1px solid #e3e8da; padding-left: 17px; margin-left: 4px; font-size: 11px; color: #6e785f; }
.refresh-button { background: transparent; border-color: #e0e6d7; color: #6e785f; }
.request-alert { margin: 0 25px 18px; width: auto; }
.request-table { --el-table-header-bg-color: #f7f9f3; --el-table-tr-bg-color: #fffefa; --el-table-row-hover-bg-color: #f5f8f0; }
.request-table :deep(th.el-table__cell) { height: 43px; font-size: 11px; border-top: 1px solid #e9eddf; }
.request-table :deep(td.el-table__cell) { height: 91px; }
.request-table :deep(th:first-child .cell), .request-table :deep(td:first-child .cell) { padding-left: 25px; }
.request-table :deep(th:last-child .cell), .request-table :deep(td:last-child .cell) { padding-right: 21px; }
.request-type { display: flex; align-items: center; gap: 8px; }
.request-type strong { font-size: 13px; font-weight: 500; color: #4b5b3e; }
.type-indicator { width: 5px; height: 14px; background: #c4d2b8; border-radius: 2px; }
.type-indicator.correction { background: #d3c8ae; }
.request-number { display: block; margin: 5px 0 0 13px; font-size: 10px; color: #6e785f; font-variant-numeric: tabular-nums; }
.applicant { display: flex; align-items: center; gap: 9px; font-size: 12px; color: #617053; }
.applicant-avatar { display: grid; place-items: center; width: 29px; height: 31px; flex-shrink: 0; background: #ecf1e4; border: 1px solid #dfe7d5; border-radius: 8px; font-size: 12px; color: #72865e; }
.request-date strong { font-size: 12px; font-weight: 500; color: #5e6b52; font-variant-numeric: tabular-nums; }
.request-date small { display: block; margin-top: 5px; color: #6e785f; font-size: 11px; font-variant-numeric: tabular-nums; }
.request-reason { margin: 0; font-size: 12px; color: #6e785f; line-height: 1.7; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.review-comment { color: #6e785f; font-size: 12px; }
.review-comment.empty { color: #b9beaf; }
.review-actions { display: flex; justify-content: flex-end; gap: 6px; white-space: nowrap; }
.review-actions .el-button { margin: 0; font-size: 11px; }
.approve-button { color: #61794f; border-color: #dce5cf; background: #f2f7e9; }
.review-actions :deep(.el-button--danger) { color: #926e60; }
.processed-label { font-size: 11px; color: #6e785f; }
.request-footer { display: flex; align-items: center; justify-content: space-between; gap: 18px; padding: 19px 25px; }
.request-footer > span, .request-footnote { font-size: 11px; color: #6e785f; }
.request-footnote { margin: 16px 2px 0; }
.review-context { border: 1px solid #e2e7d9; background: #f8faf3; border-radius: 8px; margin-bottom: 20px; overflow: hidden; }
.review-context-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 15px; padding: 17px; }
.review-context label { display: block; color: #6e785f; font-size: 11px; margin-bottom: 7px; }
.review-context strong { font-size: 13px; font-weight: 500; color: #526343; }
.review-context-reason { border-top: 1px solid #e4eadb; padding: 15px 17px; }
.review-context-reason p { margin: 0; font-size: 12px; line-height: 1.8; color: #6e785f; overflow-wrap: anywhere; }
@media (max-width: 1000px) {
  .request-category { flex-wrap: wrap; gap: 0; }
  .category-note { width: 100%; text-align: left; padding-bottom: 14px; }
}
@media (max-width: 800px) {
  .requests-heading { align-items: flex-start; }
  .request-category { padding: 0 18px; }
  .request-tabs :deep(.el-tabs__item) { height: 61px; }
  .request-toolbar { padding: 17px 18px; gap: 12px; }
  .filter-label { display: none; }
  .request-filter { gap: 10px; }
  .request-total { border: 0; margin: 0; padding: 0; }
  .request-filter :deep(.el-select) { width: 125px; }
  .request-footer { align-items: flex-start; flex-direction: column; gap: 12px; padding: 17px 18px; }
}

.request-mobile-list { display: none; }
@media (max-width: 600px) {
  .request-table { display: none; }
  .request-mobile-list { display: block; padding: 0 14px 5px; min-height: 110px; }
  .request-card { margin-bottom: 12px; border: 1px solid #e1e7d8; border-radius: 10px; overflow: hidden; background: #fffefa; }
  .request-card-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 17px 16px 15px; border-bottom: 1px solid #edf0e6; background: #f9fbf5; }
  .request-card .request-type strong { font-size: 14px; }
  .request-card .request-number { margin-top: 6px; }
  .request-card-body { padding: 16px; }
  .mobile-applicant { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-bottom: 18px; }
  .mobile-applicant > span { font-size: 11px; color: #748069; flex-shrink: 0; }
  .mobile-applicant .applicant { min-width: 0; }
  .mobile-applicant strong { font-weight: 500; font-size: 13px; color: #42543a; overflow-wrap: anywhere; }
  .request-card-details { display: grid; gap: 16px; margin: 0; }
  .request-card-details dt { margin-bottom: 6px; font-size: 10px; color: #748069; }
  .request-card-details dd { margin: 0; font-size: 12px; color: #42543a; line-height: 1.8; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
  .request-card-details .mobile-reason { white-space: pre-wrap; }
  .mobile-review-note { padding-top: 13px; border-top: 1px dashed #dfe7d6; }
  .request-card-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 9px; padding: 10px 14px; background: #f8faf4; border-top: 1px solid #edf0e6; }
  .request-card-actions .el-button { margin: 0; font-size: 12px; height: 36px; }
  .request-card-actions .el-button--danger { color: #926e60; }
  .mobile-empty { margin: 0; padding: 38px 18px; text-align: center; font-size: 12px; color: #748069; line-height: 1.8; }
  .request-footer { padding-top: 12px; }
  .request-footer :deep(.el-pagination) { max-width: 100%; overflow-x: auto; }
}
</style>
