<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { api, put, items, query, errorText } from '../api'
import { useLoad, today } from '../composables'
import { ElMessage } from 'element-plus'
const rule=ref<any>(null),pendingRule=ref<any>(null),saving=ref(false),calendar=ref<any[]>([]),month=ref(today().slice(0,7)),calendarError=ref(''),calendarLoading=ref(false),calendarDialog=ref(false),formError=ref(''),calendarForm=reactive({date:'',workday:true,note:''})
const form=reactive({startTime:'09:00:00',endTime:'18:00:00',lateGraceMinutes:5,earlyGraceMinutes:5,inWindowStart:'06:00:00',inWindowEnd:'11:59:59',outWindowStart:'12:00:00',outWindowEnd:'23:59:59'})
const fields=[{key:'startTime',label:'上班时间'},{key:'endTime',label:'下班时间'},{key:'inWindowStart',label:'签到窗口开始'},{key:'inWindowEnd',label:'签到窗口结束'},{key:'outWindowStart',label:'签退窗口开始'},{key:'outWindowEnd',label:'签退窗口结束'}] as const
const {loading,error,load}=useLoad(async()=>{const data=await api('/rules');rule.value=data.activeRule;pendingRule.value=data.pendingRule;if(data.pendingRule||data.activeRule){const source=data.pendingRule||data.activeRule;for(const key of Object.keys(form) as (keyof typeof form)[])(form as any)[key]=typeof source[key]==='string'&&source[key].length===5?source[key]+':00':source[key]};await loadCalendar()})
const effective=computed(()=>pendingRule.value?.effectiveDate||pendingRule.value?.effectiveFrom)
async function loadCalendar(){calendarLoading.value=true;calendarError.value='';try{const [y,m]=month.value.split('-').map(Number);const last=new Date(y!,m!,0).getDate();calendar.value=items(await api('/calendar?'+query({from:`${month.value}-01`,to:`${month.value}-${last}`,size:40})))}catch(e){calendarError.value=errorText(e)}finally{calendarLoading.value=false}}
async function save(){saving.value=true;formError.value='';try{await put('/rules',form);ElMessage.success('新规则已保存，从次日开始生效');await load()}catch(e){formError.value=errorText(e)}finally{saving.value=false}}
function editCalendar(row?:any){Object.assign(calendarForm,{date:row?.date||'',workday:row?.workday??true,note:row?.note||''});formError.value='';calendarDialog.value=true}
async function saveCalendar(){if(!calendarForm.date||calendarForm.date<=today()){formError.value='只能修改未来日期的工作日历';return}saving.value=true;formError.value='';try{await put(`/calendar/${calendarForm.date}`,{workday:calendarForm.workday,note:calendarForm.note});ElMessage.success('工作日历已更新');calendarDialog.value=false;loadCalendar()}catch(e){formError.value=errorText(e)}finally{saving.value=false}}
</script>

<template>
  <div class="rules-page">
    <div class="page-head">
      <div><h1>规则与日历</h1><p>规则适用于全体员工，修改后次日生效。</p></div>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />

    <section v-if="rule" class="active-schedule" aria-labelledby="active-schedule-title">
      <div class="schedule-header">
        <div><span class="schedule-dot" aria-hidden="true"></span><h2 id="active-schedule-title">当前生效班次</h2></div>
        <span>北京时间 · 默认周一至周五</span>
      </div>
      <div class="schedule-overview">
        <div class="schedule-time">
          <div><span>上班</span><time>{{ rule.startTime }}</time></div>
          <span class="schedule-connector" aria-hidden="true"><i></i><i></i></span>
          <div><span>下班</span><time>{{ rule.endTime }}</time></div>
        </div>
        <dl class="schedule-grace">
          <div><dt>迟到宽限</dt><dd>{{ rule.lateGraceMinutes }}<small>分钟</small></dd></div>
          <div><dt>早退宽限</dt><dd>{{ rule.earlyGraceMinutes }}<small>分钟</small></dd></div>
        </dl>
      </div>
      <div v-if="rule.effectiveDate || rule.effectiveFrom" class="schedule-effective">生效日期 · {{ rule.effectiveDate || rule.effectiveFrom }}</div>
    </section>

    <section class="panel rule-editor" v-loading="loading" aria-labelledby="rule-editor-title">
      <div class="editor-header">
        <div><h2 id="rule-editor-title">固定日班规则</h2><p>北京时间 · 默认周一至周五工作</p></div>
        <span class="editor-state" :class="{ 'is-pending': pendingRule }">{{ pendingRule ? '待生效配置' : '编辑规则' }}</span>
      </div>

      <el-alert
        v-if="pendingRule"
        :title="'存在待生效规则' + (effective ? '，生效日期：' + effective : '（次日生效）') + '。下面的表单展示待生效配置。'"
        type="info"
        show-icon
        :closable="false"
      />
      <el-alert v-if="formError && !calendarDialog" :title="formError" type="error" :closable="false" />

      <el-form label-position="top" class="rule-form">
        <div class="rule-group">
          <div class="rule-group-description"><span>01</span><h3>班次时间</h3><p>判定迟到与早退的时间基准。</p></div>
          <div class="rule-group-fields">
            <el-form-item v-for="field in fields.slice(0, 2)" :key="field.key" :label="field.label">
              <el-time-picker v-model="form[field.key]" format="HH:mm:ss" value-format="HH:mm:ss" :clearable="false" />
            </el-form-item>
          </div>
        </div>

        <div class="rule-group">
          <div class="rule-group-description"><span>02</span><h3>签到窗口</h3><p>允许员工进行上班签到的时段。</p></div>
          <div class="rule-group-fields">
            <el-form-item v-for="field in fields.slice(2, 4)" :key="field.key" :label="field.label">
              <el-time-picker v-model="form[field.key]" format="HH:mm:ss" value-format="HH:mm:ss" :clearable="false" />
            </el-form-item>
          </div>
        </div>

        <div class="rule-group">
          <div class="rule-group-description"><span>03</span><h3>签退窗口</h3><p>允许员工进行下班签退的时段。</p></div>
          <div class="rule-group-fields">
            <el-form-item v-for="field in fields.slice(4, 6)" :key="field.key" :label="field.label">
              <el-time-picker v-model="form[field.key]" format="HH:mm:ss" value-format="HH:mm:ss" :clearable="false" />
            </el-form-item>
          </div>
        </div>

        <div class="rule-group">
          <div class="rule-group-description"><span>04</span><h3>宽限时间</h3><p>上下班时间前后的宽限范围。</p></div>
          <div class="rule-group-fields">
            <el-form-item label="迟到宽限（分钟）">
              <el-input-number v-model="form.lateGraceMinutes" :min="0" :max="60" />
            </el-form-item>
            <el-form-item label="早退宽限（分钟）">
              <el-input-number v-model="form.earlyGraceMinutes" :min="0" :max="60" />
            </el-form-item>
          </div>
        </div>

        <div class="rule-save-row">
          <p>保存后次日生效，历史考勤沿用当日规则。</p>
          <el-button type="primary" :loading="saving" @click="save">保存规则</el-button>
        </div>
      </el-form>
      <p class="rule-scope-note">当前支持固定日班，不含跨夜、轮班和加班计算。</p>
    </section>

    <section class="panel calendar-panel" aria-labelledby="calendar-title">
      <div class="calendar-header">
        <div><h2 id="calendar-title">工作日历</h2><p>设置未来的调休与假期，其余日期按默认工作周计算。</p></div>
        <el-button type="primary" plain @click="editCalendar()">设置日期例外</el-button>
      </div>
      <div class="calendar-toolbar">
        <span>日期例外</span>
        <el-date-picker v-model="month" type="month" value-format="YYYY-MM" :clearable="false" aria-label="查看月份" @change="loadCalendar" />
      </div>
      <el-alert v-if="calendarError" :title="calendarError" type="error" :closable="false" />
      <el-table :data="calendar" v-loading="calendarLoading" empty-text="当前月份没有日期例外，默认周一至周五为工作日">
        <el-table-column prop="date" label="日期" width="150" />
        <el-table-column label="工作日设置" width="160">
          <template #default="{ row }"><el-tag :type="row.workday ? 'success' : 'info'">{{ row.workday ? '工作日' : '休息日' }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="note" label="备注" min-width="180" />
        <el-table-column label="操作" width="110">
          <template #default="{ row }"><el-button text type="primary" :disabled="row.date <= today()" @click="editCalendar(row)">修改</el-button></template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="calendarDialog" title="设置未来日期例外" width="470px">
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" />
      <el-form label-position="top">
        <el-form-item label="日期"><el-date-picker v-model="calendarForm.date" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
        <el-form-item label="当日类型">
          <el-radio-group v-model="calendarForm.workday"><el-radio-button :value="true">工作日</el-radio-button><el-radio-button :value="false">休息日</el-radio-button></el-radio-group>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="calendarForm.note" maxlength="200" placeholder="例如：国庆调休" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="calendarDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveCalendar">保存日期设置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.rules-page { max-width: 1180px; }
.active-schedule { margin-bottom: 24px; padding: 25px 30px 21px; border: 1px solid #d7e1ce; border-radius: 12px; background: #eff4e8; }
.schedule-header { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 22px; }
.schedule-header > div { display: flex; align-items: center; gap: 9px; }
.schedule-dot { width: 5px; height: 5px; border-radius: 50%; background: #6d8a52; }
.schedule-header h2 { margin: 0; font-size: 12px; font-weight: 500; color: #536647; }
.schedule-header > span { font-size: 11px; color: #819174; }
.schedule-overview { display: flex; align-items: center; justify-content: space-between; gap: 40px; }
.schedule-time { display: flex; align-items: center; gap: 24px; flex: 1; max-width: 550px; }
.schedule-time > div { min-width: 0; }
.schedule-time > div > span { display: block; margin-bottom: 9px; font-size: 11px; color: #79896d; }
.schedule-time time { font-size: clamp(25px, 2.7vw, 37px); font-weight: 500; color: #314329; font-variant-numeric: tabular-nums; letter-spacing: -1.5px; white-space: nowrap; }
.schedule-connector { position: relative; display: block; height: 1px; min-width: 32px; flex: 1; margin: 24px 0 0; background: #c4d2b7; }
.schedule-connector i { display: block; position: absolute; top: -2px; width: 5px; height: 5px; border: 1px solid #a4b693; background: #eff4e8; border-radius: 50%; }
.schedule-connector i:last-child { right: 0; }
.schedule-grace { display: flex; margin: 0; gap: 28px; padding-left: 30px; border-left: 1px solid #d4dfc9; }
.schedule-grace dt { font-size: 11px; color: #7a896f; margin-bottom: 11px; }
.schedule-grace dd { margin: 0; font-size: 22px; font-weight: 500; color: #415637; font-variant-numeric: tabular-nums; white-space: nowrap; }
.schedule-grace small { margin-left: 5px; font-size: 10px; font-weight: 400; color: #7a896f; }
.schedule-effective { margin-top: 19px; color: #7f9072; font-size: 10px; }
.rule-editor { padding: 0; overflow: hidden; }
.editor-header { padding: 25px 28px; display: flex; justify-content: space-between; align-items: center; gap: 20px; border-bottom: 1px solid var(--border); }
.editor-header h2 { margin: 0 0 6px; font-size: 17px; }
.editor-header p { margin: 0; font-size: 11px; color: var(--text-muted); }
.editor-state { padding: 5px 9px; background: #f4f6f0; border: 1px solid #e2e7da; border-radius: 5px; color: #829074; font-size: 10px; white-space: nowrap; }
.editor-state.is-pending { color: #8a713e; border-color: #e8ddc2; background: #faf6eb; }
.rule-editor > :deep(.el-alert) { width: auto; margin: 22px 28px 0; }
.rule-form { padding: 0 28px; }
.rule-group { display: grid; grid-template-columns: minmax(190px, .85fr) minmax(0, 2fr); gap: 36px; padding: 27px 0; border-bottom: 1px solid #e9ede3; align-items: center; }
.rule-group-description { position: relative; padding-left: 32px; }
.rule-group-description > span { position: absolute; top: 3px; left: 0; font-size: 10px; color: #a1ae95; font-variant-numeric: tabular-nums; }
.rule-group-description h3 { margin: 0 0 8px; font-size: 13px; font-weight: 500; }
.rule-group-description p { margin: 0; font-size: 11px; color: var(--text-muted); line-height: 1.8; }
.rule-group-fields { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 24px; }
.rule-group-fields :deep(.el-form-item) { margin: 0; min-width: 0; }
.rule-group-fields :deep(.el-form-item__label) { font-size: 11px; }
.rule-group-fields :deep(.el-date-editor.el-input), .rule-group-fields :deep(.el-input-number) { width: 100%; }
.rule-group-fields :deep(.el-input__wrapper) { min-height: 42px; }
.rule-save-row { display: flex; align-items: center; justify-content: space-between; gap: 24px; padding: 23px 0; }
.rule-save-row p { margin: 0; font-size: 11px; color: var(--text-muted); }
.rule-save-row .el-button { min-width: 108px; }
.rule-scope-note { margin: 0; padding: 14px 28px; border-top: 1px solid var(--border); background: #fafbf7; font-size: 10px; color: var(--text-muted); }
.calendar-panel { margin-top: 28px; padding: 26px 28px; }
.calendar-header { display: flex; align-items: center; justify-content: space-between; gap: 24px; margin-bottom: 25px; }
.calendar-header h2 { margin: 0 0 7px; font-size: 17px; }
.calendar-header p { margin: 0; font-size: 11px; color: var(--text-muted); }
.calendar-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 20px; border-top: 1px solid #e9ede3; padding-top: 20px; margin-bottom: 19px; }
.calendar-toolbar > span { font-size: 12px; color: var(--text-secondary); }
.calendar-toolbar :deep(.el-date-editor) { width: 160px; }
@media (max-width: 1050px) {
  .schedule-overview { gap: 25px; }
  .schedule-time { gap: 18px; }
  .schedule-grace { gap: 19px; padding-left: 23px; }
  .rule-group { grid-template-columns: minmax(150px, .7fr) minmax(0, 2fr); gap: 22px; }
  .rule-group-fields { gap: 18px; }
}
@media (max-width: 760px) {
  .active-schedule { padding: 22px; }
  .schedule-header { margin-bottom: 19px; }
  .schedule-header > span { font-size: 10px; }
  .schedule-overview { display: block; }
  .schedule-time { gap: 24px; max-width: none; }
  .schedule-time time { font-size: 29px; letter-spacing: -1px; }
  .schedule-grace { border-left: 0; border-top: 1px solid #d4dfc9; padding: 17px 0 0; margin-top: 21px; display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
  .schedule-grace > div { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
  .schedule-grace dt { margin: 0; font-size: 10px; }
  .schedule-grace dd { font-size: 18px; }
  .schedule-effective { margin-top: 15px; }
  .editor-header { padding: 22px; }
  .rule-editor > :deep(.el-alert) { margin: 18px 22px 0; }
  .rule-form { padding: 0 22px; }
  .rule-group { grid-template-columns: 1fr; gap: 20px; padding: 24px 0; }
  .rule-group-description { padding-left: 28px; }
  .rule-group-description h3 { margin-bottom: 5px; }
  .rule-group-fields { gap: 16px; }
  .rule-save-row { align-items: flex-start; }
  .rule-save-row p { max-width: 190px; font-size: 10px; line-height: 1.8; }
  .rule-save-row .el-button { min-width: 94px; flex-shrink: 0; }
  .rule-scope-note { padding: 14px 22px; }
  .calendar-panel { padding: 23px 22px; margin-top: 24px; }
  .calendar-header { align-items: flex-start; flex-wrap: wrap; gap: 15px; margin-bottom: 20px; }
  .calendar-header .el-button { flex-shrink: 0; }
}
@media (max-width: 420px) {
  .active-schedule { padding: 20px 18px; }
  .schedule-header { gap: 12px; flex-wrap: wrap; }
  .schedule-time { gap: 16px; }
  .schedule-time time { font-size: 25px; }
  .schedule-connector { min-width: 20px; }
  .schedule-grace { gap: 20px; }
  .editor-header { padding: 21px 18px; }
  .rule-form { padding: 0 18px; }
  .rule-editor > :deep(.el-alert) { margin-left: 18px; margin-right: 18px; }
  .rule-group-fields { gap: 12px; }
  .rule-group-fields :deep(.el-form-item__label) { font-size: 10px; }
  .rule-group-fields :deep(.el-input__inner) { font-size: 12px; }
  .rule-group-fields :deep(.el-input-number) { --el-input-number-width: 100%; }
  .rule-scope-note { padding-left: 18px; padding-right: 18px; }
  .calendar-panel { padding: 21px 18px; }
}
</style>
