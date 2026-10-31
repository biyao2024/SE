<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { Camera, CircleCheck, Close, Refresh } from '@element-plus/icons-vue'
import { api, post, remove, errorText } from '../api'
import { useCamera } from '../composables'
import { FrameBudget, FramePacer, maskedIdentity } from '../cameraProtocol.mjs'
const props=defineProps<{diagnostic?:boolean}>()
const diagnostics=ref<any[]>([])
const latestDiagnostic=computed(()=>diagnostics.value.at(-1))
function diagnosticValue(value:any){return value===null||value===undefined?'—':typeof value==='number'?value.toFixed(3):String(value)}
const gateNames:Record<string,string>={PASS:'身份检查通过',EMPTY_GALLERY:'人员库为空',MATCH_SCORE:'身份相似度不足',CANDIDATE_MARGIN:'候选分差不足',CANDIDATE_CHANGED:'候选人员改变',REFERENCE_SIMILARITY:'与首帧相似度不足',QUALITY:'画面质量未通过'}
function clearDiagnostics(){diagnostics.value=[];cancel()}
const {video,cameraError,cameraReady,start:cameraStart,stop:cameraStop,frame}=useCamera()
const status=ref<any>(null),statusError=ref(''),busy=ref(false),prompt=ref('选择上班签到或下班签退。'),result=ref<{success:boolean;message:string;employeeName?:string;employeeNo?:string}|null>(null),clock=ref(''),punchType=ref<'IN'|'OUT'>('IN')
const statusLoading=ref(false)
const canStart=computed(()=>!statusLoading.value&&status.value?.authorized===true&&status.value?.ready===true&&(!props.diagnostic||status.value?.testMode===true))
let ws:WebSocket|null=null,sessionId='',captureTimer:ReturnType<typeof setInterval>|undefined,timeoutTimer:ReturnType<typeof setTimeout>|undefined,resultTimer:ReturnType<typeof setTimeout>|undefined,clockTimer:ReturnType<typeof setInterval>|undefined,streamReady=false,pacer=new FramePacer(),inFlight=false,completed=false,epoch=0,resolving=false
function clearCapture(){streamReady=false;pacer.reset();if(captureTimer)clearInterval(captureTimer);if(timeoutTimer)clearTimeout(timeoutTimer);captureTimer=undefined;timeoutTimer=undefined;if(ws){ws.onclose=null;ws.onerror=null;ws.onmessage=null;ws.onopen=null;ws.close();ws=null}cameraStop();busy.value=false}
async function checkStatus(){statusLoading.value=true;statusError.value='';try{status.value=await api('/kiosk/status');return status.value?.authorized===true&&status.value?.ready===true}catch(e){status.value=null;statusError.value=errorText(e);return false}finally{statusLoading.value=false}}
function showResult(data:any){completed=true;clearCapture();result.value={success:['SUCCESS','DUPLICATE'].includes(data.status),message:data.message||'本次打卡已结束，请核查记录',...maskedIdentity(data.employeeName,data.employeeNo)};prompt.value='本次会话已结束';if(resultTimer)clearTimeout(resultTimer);resultTimer=setTimeout(()=>{result.value=null;prompt.value='选择上班签到或下班签退。'},3000)}
async function cancel(keepBusy=false){epoch++;const id=sessionId;sessionId='';completed=true;clearCapture();busy.value=keepBusy;prompt.value='已停止采集，可以重新开始。';if(id)await remove(`/kiosk/sessions/${encodeURIComponent(id)}`).catch(()=>{})}
async function fail(message:string){const id=sessionId;sessionId='';showResult({type:'ERROR',message});if(id)await remove(`/kiosk/sessions/${encodeURIComponent(id)}`).catch(()=>{})}
async function resolveDisconnected(){if(completed||!sessionId||resolving)return;resolving=true;const id=sessionId,current=epoch;clearCapture();try{const data=await api(`/kiosk/sessions/${encodeURIComponent(id)}`);if(current!==epoch)return;if(data.status==='COMPLETED'||data.status==='SUCCESS'||data.status==='DUPLICATE'){showResult(data);return}}catch{}finally{resolving=false}if(current===epoch)await fail('连接中断，本次结果未确认。请联系管理员核查记录后重新开始。')}
async function begin(type:'IN'|'OUT'){
  if(busy.value||statusLoading.value)return;const cancellation=cancel(true),current=epoch;await cancellation;if(current!==epoch)return;if(resultTimer)clearTimeout(resultTimer);result.value=null;diagnostics.value=[];punchType.value=type;prompt.value='正在检查设备与识别服务…'
  try{
    const ready=await checkStatus();if(current!==epoch)return;if(!ready){busy.value=false;prompt.value=status.value?.message||statusError.value||'打卡条件未满足，请联系管理员处理后刷新状态。';return}
    prompt.value='正在打开摄像头…'
    if(!await cameraStart()){busy.value=false;return};if(current!==epoch)return
    const data=await post(props.diagnostic?'/kiosk/diagnostics/sessions':'/kiosk/sessions',{type});if(current!==epoch){await remove(`/kiosk/sessions/${data.sessionId}`).catch(()=>{});return}
    sessionId=data.sessionId;completed=false;prompt.value=data.livenessRequired?'请单人正对镜头，保持自然睁眼、闭嘴，按提示完成动作。':'请单人正对镜头，保持面部清晰。'
    ws=new WebSocket(`${location.protocol==='https:'?'wss:':'ws:'}//${location.host}/api/v1/${props.diagnostic?'kiosk/diagnostics':'kiosk'}/stream?sessionId=${encodeURIComponent(sessionId)}`);ws.binaryType='arraybuffer'
    timeoutTimer=setTimeout(()=>{if(current===epoch&&!completed)fail('无法建立识别连接，请检查网络后重试。')},8000)
    ws.onopen=()=>{if(timeoutTimer)clearTimeout(timeoutTimer);const budget=new FrameBudget(performance.now());timeoutTimer=setTimeout(()=>fail('验证超时，请调整光线和位置后重试。'),52000);captureTimer=setInterval(async()=>{if(!streamReady||!pacer.canSend()||inFlight||!ws||ws.readyState!==WebSocket.OPEN||completed||current!==epoch||ws.bufferedAmount>=400*1024)return;inFlight=true;try{const blob=await frame();if(!blob||completed||current!==epoch)return;if(!budget.accept(blob.size,performance.now())){await fail('采集已达到本次上限，请重新开始。');return}if(ws?.readyState===WebSocket.OPEN){pacer.sent();ws.send(blob)}}catch(e){if(current===epoch&&!completed)await fail(errorText(e))}finally{inFlight=false}},167)}
    ws.onmessage=event=>{try{const data=JSON.parse(event.data);if(props.diagnostic&&data.type==='DIAGNOSTIC'){diagnostics.value=[...diagnostics.value.slice(-79),data.values];return;}if(data.streamReady===true)streamReady=true;pacer.reply(data);if(data.type==='RESULT')showResult(data);else if(data.type==='ERROR')fail(data.message||'本次验证未通过，请重试');else if(data.type==='PROMPT')prompt.value=data.message||({BLINK:'请眨眼，然后恢复自然睁眼',MOUTH:'请张嘴，然后闭嘴'} as any)[data.action]||'请按照提示完成动作'}catch{fail('服务返回了无法识别的消息，请重新开始。')}}
    ws.onerror=()=>{if(!completed)resolveDisconnected()};ws.onclose=()=>{if(!completed)resolveDisconnected()}
  }catch(e){if(current===epoch)await fail(errorText(e))}
}
onMounted(()=>{window.addEventListener('session-expired',clearDiagnostics);checkStatus();const tick=()=>clock.value=new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'long',day:'numeric',weekday:'long',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(new Date());tick();clockTimer=setInterval(tick,1000)})
onBeforeUnmount(()=>{window.removeEventListener('session-expired',clearDiagnostics);diagnostics.value=[];cancel();if(resultTimer)clearTimeout(resultTimer);if(clockTimer)clearInterval(clockTimer)})
</script>

<template>
  <div class="public-kiosk">
    <header class="public-header">
      <router-link class="brand" to="/">
        <span class="brand-mark">知</span>
        <span>知行<small>考勤管理</small></span>
      </router-link>
      <router-link class="public-nav" :to="props.diagnostic ? '/settings' : '/login'">
        {{ props.diagnostic ? '返回系统运行' : '管理入口' }} <span aria-hidden="true">→</span>
      </router-link>
    </header>

    <main class="kiosk-main">
      <div class="kiosk-heading">
        <div>
          <h1>{{ props.diagnostic ? '识别诊断' : '刷脸打卡' }}</h1>
          <p>{{ props.diagnostic ? '查看本次测试的逐帧识别结果。' : '选择签到或签退，按屏幕提示完成验证。' }}</p>
        </div>
        <div class="kiosk-clock">
          <span>北京时间</span>
          <time>{{ clock }}</time>
        </div>
      </div>

      <div class="kiosk-notices">
        <el-alert
          v-if="props.diagnostic"
          title="管理员测试：需开启全天测试打卡并授权当前浏览器。成功后保存测试记录；诊断分数仅保留在本页内存，不保存原图或特征。"
          type="info"
          :closable="false"
        />
        <el-alert
          v-if="status?.testMode"
          title="测试模式：全天允许签到、签退，记录不计入正式考勤。"
          type="warning"
          :closable="false"
        />
        <el-alert v-if="statusError" :title="statusError" type="warning" show-icon :closable="false" />
        <el-alert
          v-if="status && status.authorized === false"
          title="当前浏览器尚未授权。请管理员在本设备登录，在「打卡设备」中授权本浏览器。"
          type="warning"
          show-icon
          :closable="false"
        />
        <el-alert
          v-if="status?.authorized && !status.ready"
          :title="status.message || '识别服务或人员库尚未就绪，请联系管理员检查后刷新状态。'"
          type="warning"
          show-icon
          :closable="false"
        />
        <el-alert v-if="cameraError" :title="cameraError" type="error" show-icon :closable="false" />
      </div>

      <div class="capture-layout">
        <section class="capture-preview" aria-label="摄像头画面">
          <div class="camera-shell">
            <video ref="video" autoplay muted playsinline aria-label="实时摄像头预览" />
            <div v-if="!cameraReady" class="camera-placeholder">
              <el-icon :size="34"><Camera /></el-icon>
              <span>摄像头未开启</span>
              <small>选择打卡类型后开启摄像头</small>
            </div>
            <div v-if="cameraReady" class="camera-guide" />
            <div v-if="cameraReady" class="camera-caption">
              {{ punchType === 'IN' ? '上班签到' : '下班签退' }} · 请保持单人入镜
            </div>
          </div>
          <div class="preview-footnote">
            <span>{{ cameraReady ? '摄像头已开启' : '摄像头仅在打卡时开启' }}</span>
            <span>保持光线均匀、面部无遮挡</span>
          </div>
        </section>

        <section class="capture-controls" aria-labelledby="capture-title">
          <div class="control-title">
            <h2 id="capture-title">{{ props.diagnostic ? '开始测试' : '记录出勤' }}</h2>
            <span v-if="busy" class="capture-state">验证中</span>
          </div>
          <div class="capture-instruction" aria-live="polite">
            <span class="instruction-label">当前提示</span>
            <p>{{ prompt }}</p>
          </div>

          <div v-if="result" class="capture-result" :class="{ 'is-error': !result.success }" role="status">
            <el-icon :size="21"><CircleCheck v-if="result.success" /><Close v-else /></el-icon>
            <div>
              <h3>{{ result.message }}</h3>
              <p v-if="result.employeeName">{{ result.employeeName }} · {{ result.employeeNo }}</p>
              <small>本次结果将在 3 秒后清除</small>
            </div>
          </div>

          <div class="punch-actions">
            <template v-if="!busy">
              <el-button type="primary" size="large" :icon="Camera" :disabled="!canStart" @click="begin('IN')">
                上班签到
              </el-button>
              <el-button size="large" :disabled="!canStart" @click="begin('OUT')">下班签退</el-button>
            </template>
            <el-button v-else size="large" @click="cancel()">停止本次采集</el-button>
          </div>

          <div class="capture-help">
            <h3>识别遇到困难？</h3>
            <p>调整光线和位置后重试，或联系管理员办理人工补卡。</p>
            <el-button :icon="Refresh" text :loading="statusLoading" :disabled="busy" @click="checkStatus">
              刷新设备状态
            </el-button>
          </div>
        </section>
      </div>

      <p class="capture-privacy">原始图像仅用于本次识别，不在浏览器或业务数据库中持久保存。</p>

      <section v-if="props.diagnostic" class="panel diagnostic-panel" aria-labelledby="diagnostic-title">
        <div class="panel-head">
          <div>
            <h2 id="diagnostic-title">逐帧诊断</h2>
            <p>保留最近 80 帧；重新开始时清空，失败帧也会保留。</p>
          </div>
          <span class="diagnostic-count">{{ diagnostics.length }} 帧</span>
        </div>
        <p class="diagnostic-description">
          分数不是概率。只有一位有效人员时，候选分差显示“—”；动作关闭时，动作系数显示“—”。
        </p>

        <dl v-if="latestDiagnostic" class="diagnostic-metrics">
          <div
            v-for="field in [
              { key: 'score', label: '身份分数' },
              { key: 'margin', label: '候选分差' },
              { key: 'referenceSimilarity', label: '首帧相似度' },
              { key: 'eyeLeft', label: '左闭眼系数' },
              { key: 'eyeRight', label: '右闭眼系数' },
              { key: 'mouth', label: '张嘴系数' },
              { key: 'elapsedMs', label: '处理耗时 (ms)' },
              { key: 'action', label: '当前动作' },
              { key: 'phase', label: '处理前阶段' },
              { key: 'baselineFrames', label: '自然帧（需 2 帧）' },
              { key: 'completedActions', label: '完成动作（共 2 项）' },
              { key: 'neutralEyeMax', label: '自然睁眼上限' },
              { key: 'closedEyeMin', label: '闭眼下限' },
              { key: 'mouthClosedMax', label: '自然闭嘴上限' },
              { key: 'mouthOpenMin', label: '张嘴下限' },
            ]"
            :key="field.key"
          >
            <dt>{{ field.label }}</dt>
            <dd>{{ diagnosticValue(latestDiagnostic[field.key]) }}</dd>
          </div>
        </dl>

        <el-alert
          v-if="latestDiagnostic"
          :title="gateNames[latestDiagnostic.gate] || latestDiagnostic.gate"
          :type="latestDiagnostic.gate === 'PASS' ? 'success' : 'warning'"
          :closable="false"
        />

        <div class="diagnostic-table">
          <el-table :data="diagnostics" max-height="420" empty-text="开始一次管理员测试后，在此查看逐帧结果">
            <el-table-column prop="frame" label="帧" width="60" />
            <el-table-column
              v-for="field in [
                { key: 'score', label: '身份分数' },
                { key: 'threshold', label: '身份门槛' },
                { key: 'margin', label: '候选分差' },
                { key: 'marginThreshold', label: '分差门槛' },
                { key: 'referenceSimilarity', label: '首帧相似度' },
                { key: 'eyeLeft', label: '左闭眼系数' },
                { key: 'eyeRight', label: '右闭眼系数' },
                { key: 'mouth', label: '张嘴系数' },
                { key: 'elapsedMs', label: '处理毫秒' },
              ]"
              :key="field.key"
              :label="field.label"
              width="115"
            >
              <template #default="{ row }">{{ diagnosticValue(row[field.key]) }}</template>
            </el-table-column>
            <el-table-column prop="action" label="动作" width="100" />
            <el-table-column prop="phase" label="阶段" width="70" />
            <el-table-column label="身份 / 质量检查" min-width="180">
              <template #default="{ row }">
                {{ gateNames[row.gate] || row.gate }}{{ row.qualityCode ? ' · ' + row.qualityCode : '' }}
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="diagnostic-notes">
          <p>阶段为该帧处理前状态：−1 基准 / 收尾，0 自然状态，1 闭眼 / 张嘴，2 恢复。</p>
          <p>处理耗时包含解码、推理、匹配和等待推理锁，不含网络往返。身份检查通过不表示已完成动作或打卡。</p>
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.public-kiosk { min-height: 100vh; padding: 0 40px 36px; background: var(--surface-subtle); }
.public-header { max-width: 1180px; margin: 0 auto; padding: 26px 0; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--border); }
.public-nav { display: inline-flex; align-items: center; gap: 18px; color: var(--text-secondary); font-size: 13px; }
.public-nav:hover { color: var(--accent); }
.kiosk-main { max-width: 1180px; margin: 0 auto; padding-top: 36px; }
.kiosk-heading { display: flex; justify-content: space-between; align-items: center; gap: 28px; margin-bottom: 28px; }
.kiosk-heading h1 { margin-bottom: 9px; }
.kiosk-heading p { margin: 0; font-size: 13px; color: var(--text-secondary); }
.kiosk-clock { flex-shrink: 0; text-align: right; font-size: 12px; }
.kiosk-clock span { display: block; margin-bottom: 8px; font-size: 11px; color: var(--text-muted); }
.kiosk-clock time { color: var(--text-secondary); font-variant-numeric: tabular-nums; }
.kiosk-notices:empty { display: none; }
.capture-layout { display: grid; grid-template-columns: minmax(0, 1.65fr) minmax(310px, 1fr); gap: 28px; align-items: start; }
.capture-preview { min-width: 0; }
.preview-footnote { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 8px 20px; margin-top: 12px; color: var(--text-muted); font-size: 11px; }
.capture-controls { padding: 28px; background: var(--surface); border: 1px solid var(--border); border-radius: 8px; min-width: 0; }
.control-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 26px; }
.control-title h2 { font-size: 17px; margin: 0; }
.capture-state { padding: 4px 7px; background: #edf1ee; color: var(--accent); font-size: 11px; border-radius: 4px; }
.capture-instruction { min-height: 104px; }
.instruction-label { display: block; margin-bottom: 10px; font-size: 11px; color: var(--text-muted); }
.capture-instruction p { margin: 0; font-size: 15px; line-height: 1.8; color: var(--text-primary); }
.punch-actions { display: grid; gap: 10px; margin-top: 24px; }
.punch-actions .el-button { width: 100%; margin: 0; }
.capture-result { display: flex; align-items: flex-start; gap: 12px; padding: 15px; margin-top: 15px; background: #edf3ee; color: #3f654d; border: 1px solid #d7e2d8; border-radius: 6px; }
.capture-result .el-icon { flex-shrink: 0; margin-top: 2px; }
.capture-result h3 { margin: 0; font-size: 14px; line-height: 1.7; }
.capture-result p { margin: 6px 0 0; font-size: 12px; }
.capture-result small { display: block; margin-top: 9px; font-size: 10px; }
.capture-result.is-error { background: #fcf3f0; color: #9c5145; border-color: #ead6cf; }
.capture-help { margin-top: 28px; padding-top: 22px; border-top: 1px solid var(--border); }
.capture-help h3 { font-size: 12px; font-weight: 500; margin-bottom: 7px; }
.capture-help p { margin-bottom: 10px; color: var(--text-secondary); font-size: 12px; }
.capture-help .el-button { padding: 0; height: 30px; color: var(--text-secondary); }
.capture-help .el-button:hover { background: transparent; color: var(--accent); }
.capture-privacy { margin: 24px 0 0; color: var(--text-muted); font-size: 11px; line-height: 1.8; }
.diagnostic-panel { margin-top: 30px; overflow: hidden; }
.diagnostic-count { color: var(--text-muted); font-size: 12px; font-variant-numeric: tabular-nums; }
.diagnostic-description { color: var(--text-secondary); font-size: 12px; margin-bottom: 20px; }
.diagnostic-metrics { margin: 0 0 22px; display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); border-top: 1px solid var(--border); border-left: 1px solid var(--border); }
.diagnostic-metrics > div { padding: 17px; border-right: 1px solid var(--border); border-bottom: 1px solid var(--border); min-width: 0; }
.diagnostic-metrics dt { color: var(--text-secondary); font-size: 11px; }
.diagnostic-metrics dd { margin: 8px 0 0; font-size: 18px; line-height: 1.4; font-weight: 500; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.diagnostic-table { min-width: 0; width: 100%; overflow-x: auto; }
.diagnostic-notes { margin-top: 18px; }
.diagnostic-notes p { margin: 6px 0 0; color: var(--text-muted); font-size: 11px; }
@media (max-width: 1000px) {
  .public-kiosk { padding-left: 26px; padding-right: 26px; }
  .capture-layout { gap: 20px; grid-template-columns: minmax(0, 1.3fr) minmax(290px, 1fr); }
  .capture-controls { padding: 23px; }
  .diagnostic-metrics { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
@media (max-width: 760px) {
  .public-kiosk { padding: 0 18px 26px; }
  .public-header { padding: 22px 0; }
  .kiosk-main { padding-top: 28px; }
  .kiosk-heading { display: block; margin-bottom: 22px; }
  .kiosk-heading h1 { font-size: 25px; }
  .kiosk-heading p { font-size: 12px; }
  .kiosk-clock { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 15px; text-align: left; font-size: 11px; }
  .kiosk-clock span { margin: 0; }
  .capture-layout { grid-template-columns: 1fr; gap: 22px; }
  .capture-controls { padding: 23px; }
  .control-title { margin-bottom: 20px; }
  .capture-instruction { min-height: auto; }
  .punch-actions { grid-template-columns: 1fr 1fr; }
  .punch-actions > .el-button:only-child { grid-column: 1 / -1; }
  .capture-help { margin-top: 23px; padding-top: 19px; }
  .diagnostic-metrics > div { padding: 12px 10px; }
  .diagnostic-metrics dt { font-size: 10px; line-height: 1.6; }
  .diagnostic-metrics dd { font-size: 16px; }
  .diagnostic-count { white-space: nowrap; }
}
@media (max-width: 380px) {
  .public-kiosk { padding-left: 14px; padding-right: 14px; }
  .capture-controls { padding: 20px; }
  .punch-actions .el-button { padding: 12px; font-size: 13px; }
}
</style>
