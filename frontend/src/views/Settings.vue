<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { Refresh, Connection } from '@element-plus/icons-vue'
import { api, put, post, errorText } from '../api'
import { useLoad } from '../composables'
import { ElMessage, ElMessageBox } from 'element-plus'
const health=ref<any>(null),saving=ref(false),syncing=ref(false),formError=ref(''),loaded=ref(false),originalLiveness=ref(false)
const form=reactive({testMode:false,livenessEnabled:true,recognitionThreshold:0.5,candidateMargin:0.08,calibrationConfirmed:false})
const {loading,error,load}=useLoad(async()=>{loaded.value=false;const [s,h]=await Promise.all([api('/settings'),api('/system/health')]);Object.assign(form,{testMode:!!s.testMode,livenessEnabled:s.livenessEnabled,recognitionThreshold:s.recognitionThreshold,candidateMargin:s.candidateMargin,calibrationConfirmed:s.calibrationConfirmed});originalLiveness.value=s.livenessEnabled;health.value=h;loaded.value=true})
function invalidateCalibration(){form.calibrationConfirmed=false}
function state(v:any){if(v===true||v==='UP'||v==='READY'||v==='OK'||v==='HEALTHY')return '正常';if(v===false||v==='DOWN'||v==='UNAVAILABLE')return '不可用';if(typeof v==='object'&&v)return v.status||'待确认';return v||'未知'}
const healthCards=computed(()=>[{label:'业务数据库',value:state(health.value?.database)},{label:'算法服务',value:state(health.value?.algorithm)},{label:'识别模型',value:state(health.value?.model)},{label:'人员库同步',value:health.value?health.value.gallerySynced?'已同步':'待同步':'未知'}])
async function save(){if(!loaded.value)return;try{if(form.livenessEnabled!==originalLiveness.value)await ElMessageBox.confirm('动作检测开关变更将立即使未完成打卡会话失效，公共设备需要重新开始。','确认调整动作检测',{confirmButtonText:'保存设置',cancelButtonText:'取消',type:'warning'});saving.value=true;formError.value='';await put('/settings',form);ElMessage.success('识别设置已保存');await load()}catch(e){if(e!=='cancel'&&e!=='close')formError.value=errorText(e)}finally{saving.value=false}}
async function sync(){syncing.value=true;try{await post('/system/sync-gallery');ElMessage.success('人员库已同步');await load()}catch(e){ElMessage.error(errorText(e))}finally{syncing.value=false}}
</script>
<template>
  <div class="page-head"><div><h1>系统运行</h1><p>服务状态、人员库与识别配置</p></div><div class="head-actions"><router-link to="/diagnostics"><el-button>识别诊断</el-button></router-link><el-button :icon="Refresh" :loading="loading" @click="load">刷新状态</el-button></div></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <div class="settings-workspace">
    <aside class="service-panel" v-loading="loading">
      <div class="service-heading"><span class="service-icon"><el-icon><Connection /></el-icon></span><div><h2>服务状态</h2><p>当前运行环境</p></div></div>
      <dl class="service-list"><div v-for="item in healthCards" :key="item.label"><dt>{{item.label}}</dt><dd>{{item.value}}</dd></div></dl>
      <div class="gallery-info"><span>人员库版本</span><strong>{{health?.galleryVersion??'—'}}</strong></div>
      <p class="service-note">{{health?.detail||'人员库未同步时暂不能打卡，可点击重新同步。'}}</p>
      <el-button :icon="Connection" :loading="syncing" @click="sync" class="sync-button">重新同步人员库</el-button>
    </aside>

    <section class="panel recognition-panel">
      <div class="panel-head"><div><h2>识别与动作检测</h2><p>调整参数后，完成校准并保存</p></div><span class="configuration-label">识别配置</span></div>
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" />
      <div class="configuration-group">
        <div class="group-label">打卡模式</div>
        <div class="setting-row"><div><h3>全天测试打卡</h3><p>全天可测试，不受请假限制。测试记录单独保存，不计入正式报表。</p></div><el-switch v-model="form.testMode" :disabled="!loaded" aria-label="全天测试打卡" /></div>
        <div class="setting-row"><div><h3>随机动作检测</h3><p>打卡时按随机顺序完成眨眼和张嘴。</p></div><el-switch v-model="form.livenessEnabled" :disabled="!loaded" active-text="开启" inactive-text="关闭" aria-label="随机动作检测" /></div>
        <div class="mode-caption">{{form.livenessEnabled?'开启后，打卡需完成动作验证。':'关闭后仍需通过人脸识别，动作结果记为「跳过」。'}}</div>
      </div>
      <el-alert v-if="loaded && !form.calibrationConfirmed" title="当前参数尚未确认校准；按此状态保存将关闭公共打卡。请完成独立样本校准后勾选下方确认项。" type="warning" show-icon :closable="false" class="section-gap" />
      <el-form v-if="loaded" label-position="top" class="recognition-form">
        <div class="configuration-group">
          <div class="group-label">识别参数</div>
          <div class="form-grid">
            <el-form-item label="识别相似度阈值"><el-input-number v-model="form.recognitionThreshold" @change="invalidateCalibration" :min="0.1" :max="1" :step="0.01" :precision="3" :disabled="!loaded" /><div class="help-text">第一候选必须达到该阈值。</div></el-form-item>
            <el-form-item label="候选最小分差"><el-input-number v-model="form.candidateMargin" @change="invalidateCalibration" :min="0" :max="1" :step="0.01" :precision="3" :disabled="!loaded" /><div class="help-text">前两名候选的最低分差；仅有一人时不使用。</div></el-form-item>
          </div>
          <el-checkbox v-model="form.calibrationConfirmed" :disabled="!loaded">已完成独立样本校准，并保留校准记录</el-checkbox>
        </div>
        <div class="configuration-footer"><span>修改参数后请重新校准</span><el-button type="primary" :loading="saving" :disabled="!loaded" @click="save">保存识别设置</el-button></div>
      </el-form>
    </section>
  </div>
</template>

<style scoped>
.settings-workspace { display: grid; grid-template-columns: minmax(235px, .8fr) minmax(0, 2fr); gap: 26px; align-items: start; }
.service-panel { padding: 8px 8px 0 0; }
.service-heading { display: flex; align-items: center; gap: 13px; margin-bottom: 26px; }
.service-heading h2 { margin: 0 0 5px; font-size: 14px; }
.service-heading p { margin: 0; font-size: 11px; color: var(--text-muted); }
.service-icon { display: grid; place-items: center; width: 42px; height: 42px; background: #e9eee5; border: 1px solid #dbe4d5; border-radius: 12px; color: #5c7855; font-size: 22px; }
.service-list { margin: 0; border-top: 1px solid var(--border); }
.service-list > div { display: grid; grid-template-columns: 1fr; gap: 9px; padding: 19px 0; border-bottom: 1px solid var(--border); }
.service-list dt { font-size: 11px; color: var(--text-muted); }
.service-list dd { margin: 0; font-size: 14px; line-height: 1.6; color: #3d5944; overflow-wrap: anywhere; }
.gallery-info { display: flex; align-items: center; justify-content: space-between; padding-top: 23px; font-size: 11px; color: var(--text-secondary); }
.gallery-info strong { font-weight: 500; font-size: 13px; }
.service-note { font-size: 11px; color: var(--text-muted); margin: 14px 0 20px; }
.sync-button { width: 100%; }
.recognition-panel { padding: 0; overflow: hidden; }
.recognition-panel .panel-head { padding: 26px 28px; margin: 0; border-bottom: 1px solid var(--border); }
.configuration-label { font-size: 10px; padding: 5px 9px; border: 1px solid #dfe5d9; border-radius: 4px; color: #87917f; white-space: nowrap; }
.configuration-group { padding: 24px 28px; }
.group-label { font-size: 11px; color: var(--text-muted); letter-spacing: .5px; margin-bottom: 6px; }
.setting-row { padding: 22px 0; gap: 20px; }
.setting-row h3 { font-size: 13px; }
.setting-row p { font-size: 11px; max-width: 390px; }
.setting-row:last-of-type { border-bottom: 0; }
.mode-caption { padding: 10px 13px; border-radius: 5px; background: #f5f7f1; color: #78866f; font-size: 11px; line-height: 1.7; }
.recognition-form { border-top: 1px solid var(--border); }
.recognition-form .group-label { margin-bottom: 23px; }
.recognition-form .help-text { width: 100%; font-size: 11px; margin-top: 8px; }
.recognition-form :deep(.el-checkbox__label) { font-size: 11px; }
.configuration-footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 19px 28px; background: #fafbf8; border-top: 1px solid var(--border); }
.configuration-footer > span { font-size: 10px; color: var(--text-muted); }
@media (max-width: 1060px) { .settings-workspace { grid-template-columns: 1fr; } .service-list { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 15px; } .service-list dd { font-size: 12px; } .service-note { margin-bottom: 12px; } .sync-button { width: auto; } }
@media (max-width: 600px) { .service-list { grid-template-columns: repeat(2, minmax(0, 1fr)); } .configuration-group { padding: 22px 18px; } .recognition-panel .panel-head { padding: 22px 18px; } .configuration-footer { padding: 18px; flex-wrap: wrap; } .configuration-label { display: none; } }
</style>
