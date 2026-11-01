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
        <span class="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 32 32" fill="none"><path d="M8 8h16L8 24h16M8 16h16" stroke="currentColor" stroke-width="2.4" stroke-linecap="square" /></svg>
        </span>
        <span>知行<small>考勤管理</small></span>
      </router-link>
      <div class="header-context"><span></span>{{ props.diagnostic ? '管理员诊断' : '自助打卡' }}</div>
      <router-link class="public-nav" :to="props.diagnostic ? '/settings' : '/login'">
        {{ props.diagnostic ? '返回系统运行' : '登录工作台' }} <span aria-hidden="true">↗</span>
      </router-link>
    </header>

    <main class="kiosk-main">
      <div class="kiosk-heading">
        <div>
          <span class="page-category">{{ props.diagnostic ? '设备与识别' : '人脸考勤' }}</span>
          <h1>{{ props.diagnostic ? '识别诊断' : '刷脸打卡' }}</h1>
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
        <el-alert v-if="status?.testMode" title="测试模式：全天允许签到、签退，记录不计入正式考勤。" type="warning" :closable="false" />
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

      <div class="capture-workspace">
        <ol class="capture-steps" aria-label="打卡流程">
          <li :class="{ 'is-current': !busy && !result, 'is-complete': busy || !!result }" :aria-current="!busy && !result ? 'step' : undefined">
            <span class="step-number">01</span><span>选择打卡类型</span>
          </li>
          <li :class="{ 'is-current': busy, 'is-complete': result?.success }" :aria-current="busy ? 'step' : undefined">
            <span class="step-number">02</span><span>按提示完成验证</span>
          </li>
          <li :class="{ 'is-current': !!result }" :aria-current="result ? 'step' : undefined">
            <span class="step-number">03</span><span>查看打卡结果</span>
          </li>
        </ol>

        <div class="capture-layout">
          <section class="capture-preview" aria-label="摄像头画面">
            <div class="preview-topline">
              <span class="camera-status" :class="{ 'is-active': cameraReady }"><i></i>{{ cameraReady ? '实时画面' : '相机预览' }}</span>
              <span>单人入镜</span>
            </div>
            <div class="camera-shell">
              <video ref="video" autoplay muted playsinline aria-label="实时摄像头预览" />
              <div v-if="!cameraReady" class="camera-placeholder">
                <svg class="standby-face" viewBox="0 0 180 190" fill="none" aria-hidden="true">
                  <path d="M23 49V22H50M130 22H157V49M23 141V168H50M130 168H157V141" stroke="#889C86" stroke-width="1.5" stroke-linecap="round" />
                  <path d="M62 73C62 52 74 40 90 40C106 40 118 52 118 73V87C118 108 107 122 90 122C73 122 62 108 62 87V73Z" stroke="#99AE93" stroke-width="1.5" />
                  <path d="M74 76H77M103 76H106M90 80V92H95M82 103C87 106 93 106 98 103M47 153C54 134 69 127 90 127C111 127 126 134 133 153" stroke="#99AE93" stroke-width="1.5" stroke-linecap="round" />
                </svg>
                <span>摄像头未开启</span>
                <small>选择打卡类型后，允许使用摄像头</small>
              </div>
              <div v-if="cameraReady" class="camera-guide" />
              <div v-if="cameraReady" class="camera-caption">{{ punchType === 'IN' ? '上班签到' : '下班签退' }} · 请保持正对镜头</div>
            </div>
            <div class="preview-footnote">
              <span>光线均匀</span><span>面部无遮挡</span><span>正对镜头</span>
            </div>
          </section>

          <section class="capture-controls" aria-labelledby="capture-title">
            <div class="control-title">
              <span class="control-section">{{ props.diagnostic ? '测试操作' : '本次打卡' }}</span>
              <span v-if="busy" class="capture-state">验证中</span>
            </div>
            <h2 id="capture-title">{{ busy ? (punchType === 'IN' ? '上班签到' : '下班签退') : result ? '本次结果' : '选择打卡类型' }}</h2>

            <div v-if="!result" class="capture-instruction" aria-live="polite">
              <span class="instruction-symbol" aria-hidden="true"><el-icon :size="18"><Camera /></el-icon></span>
              <p>{{ prompt }}</p>
            </div>
            <div v-if="result" class="capture-result" :class="{ 'is-error': !result.success }" role="status">
              <el-icon :size="24"><CircleCheck v-if="result.success" /><Close v-else /></el-icon>
              <div>
                <h3>{{ result.message }}</h3>
                <p v-if="result.employeeName">{{ result.employeeName }} · {{ result.employeeNo }}</p>
                <small>本次结果将在 3 秒后清除</small>
              </div>
            </div>

            <div class="punch-actions">
              <template v-if="!busy">
                <el-button type="primary" size="large" :disabled="!canStart" @click="begin('IN')">
                  <span>上班签到</span><span class="action-arrow" aria-hidden="true">→</span>
                </el-button>
                <el-button size="large" :disabled="!canStart" @click="begin('OUT')">
                  <span>下班签退</span><span class="action-arrow" aria-hidden="true">→</span>
                </el-button>
              </template>
              <el-button v-else size="large" @click="cancel()">停止本次采集</el-button>
            </div>

            <div class="device-refresh">
              <span>设备或服务状态有变化</span>
              <el-button :icon="Refresh" text :loading="statusLoading" :disabled="busy" @click="checkStatus">刷新设备状态</el-button>
            </div>
            <div class="capture-help">
              <h3>识别遇到困难？</h3>
              <p>调整光线和位置后重试，或联系管理员办理人工补卡。</p>
            </div>
          </section>
        </div>

        <div class="workspace-footer">
          <svg viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M10 2L16 4.5V9C16 13 13.5 16 10 18C6.5 16 4 13 4 9V4.5L10 2Z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round" /><path d="M7 10L9 12L13 8" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" /></svg>
          <p>摄像头仅在打卡时开启。原始图像仅用于本次识别，不在浏览器或业务数据库中持久保存。</p>
        </div>
      </div>
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
.public-kiosk { min-height: 100vh; padding: 0 40px 44px; background: #f5f7f2; }
.public-header { max-width: 1240px; margin: 0 auto; height: 88px; display: flex; align-items: center; justify-content: space-between; gap: 24px; border-bottom: 1px solid #dfe5d9; }
.public-header .brand-mark svg { width: 28px; height: 28px; }
.header-context { margin-right: auto; padding-left: 28px; border-left: 1px solid #d7dfd2; font-size: 12px; color: var(--text-secondary); display: flex; align-items: center; gap: 9px; }
.header-context > span { width: 5px; height: 5px; border-radius: 50%; background: #84967b; }
.public-nav { display: inline-flex; align-items: center; gap: 22px; font-size: 12px; color: var(--text-secondary); padding: 10px 0; }
.public-nav:hover { color: var(--accent); }
.kiosk-main { max-width: 1140px; margin: 0 auto; padding-top: 32px; }
.kiosk-heading { display: flex; align-items: center; justify-content: space-between; gap: 24px; margin-bottom: 26px; }
.page-category { display: block; font-size: 11px; letter-spacing: 1px; color: var(--text-muted); margin-bottom: 9px; }
.kiosk-heading h1 { font-size: 32px; margin: 0; letter-spacing: -1px; font-weight: 550; }
.kiosk-clock { text-align: right; flex-shrink: 0; }
.kiosk-clock span { display: block; margin-bottom: 9px; font-size: 11px; color: var(--text-muted); }
.kiosk-clock time { font-size: 13px; color: var(--text-secondary); font-variant-numeric: tabular-nums; }
.kiosk-notices:empty { display: none; }
.capture-workspace { border: 1px solid #dfe5d9; border-radius: 14px; background: #fff; box-shadow: 0 6px 28px #263a2410; overflow: hidden; }
.capture-steps { list-style: none; margin: 0; padding: 22px 30px; display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 22px; border-bottom: 1px solid #e9ede4; background: #fdfefa; }
.capture-steps li { display: flex; align-items: center; gap: 12px; min-width: 0; font-size: 12px; color: #929b8d; position: relative; }
.capture-steps li:not(:last-child)::after { content: ''; height: 1px; background: #e2e8da; flex: 1; margin-left: 12px; }
.step-number { display: grid; place-items: center; flex-shrink: 0; width: 29px; height: 29px; border: 1px solid #dde4d5; border-radius: 50%; font-size: 10px; font-variant-numeric: tabular-nums; }
.capture-steps .is-current { color: #3d5735; font-weight: 500; }
.capture-steps .is-current .step-number { background: #e9f0e0; border-color: #c9d6bc; color: #49613f; }
.capture-steps .is-complete .step-number { background: #536f47; border-color: #536f47; color: #fff; }
.capture-layout { display: grid; grid-template-columns: minmax(0, 1.6fr) minmax(320px, 1fr); }
.capture-preview { min-width: 0; padding: 25px 28px 21px; border-right: 1px solid #e9ede4; }
.preview-topline { display: flex; align-items: center; justify-content: space-between; margin-bottom: 15px; color: #7d8777; font-size: 10px; }
.camera-status { display: inline-flex; align-items: center; gap: 8px; color: #62705a; font-size: 11px; }
.camera-status i { width: 5px; height: 5px; border-radius: 50%; background: #a4afa0; }
.camera-status.is-active i { background: #78a05f; }
.camera-shell { aspect-ratio: 4 / 3; background: #26382d; border: 1px solid #26382d; border-radius: 10px; }
.camera-placeholder { gap: 0; padding: 24px; color: #d9e4cf; }
.standby-face { width: 38%; max-width: 164px; margin: 0 0 13px; }
.camera-placeholder > span { font-size: 13px; }
.camera-placeholder small { margin-top: 10px; color: #a3b59a; font-size: 10px; }
.camera-caption { font-size: 11px; }
.preview-footnote { display: flex; justify-content: center; gap: 24px; padding-top: 18px; color: #84907c; font-size: 10px; }
.preview-footnote span + span { position: relative; }
.preview-footnote span + span::before { content: ''; width: 2px; height: 2px; border-radius: 50%; position: absolute; left: -13px; top: 7px; background: #9daa92; }
.capture-controls { min-width: 0; padding: 35px 32px 25px; display: flex; flex-direction: column; }
.control-title { display: flex; justify-content: space-between; align-items: center; margin-bottom: 13px; min-height: 20px; }
.control-section { font-size: 10px; color: var(--text-muted); letter-spacing: 1px; }
.capture-state { padding: 3px 7px; border: 1px solid #d4dfca; background: #eef3e8; color: #607c4b; font-size: 10px; border-radius: 4px; }
.capture-controls h2 { font-size: 23px; font-weight: 550; margin: 0 0 22px; letter-spacing: -.5px; }
.capture-instruction { display: flex; align-items: flex-start; gap: 12px; padding: 16px; min-height: 86px; background: #f3f6ef; border: 1px solid #e8eddf; border-radius: 8px; }
.instruction-symbol { color: #728768; margin-top: 2px; flex-shrink: 0; }
.capture-instruction p { margin: 0; color: #56664d; font-size: 13px; line-height: 1.8; }
.punch-actions { display: grid; gap: 11px; margin-top: 24px; }
.punch-actions .el-button { width: 100%; height: 50px; margin: 0; padding: 12px 19px; border-radius: 7px; font-size: 14px; }
.punch-actions .el-button :deep(> span) { width: 100%; display: flex; justify-content: space-between; align-items: center; }
.action-arrow { font-size: 20px; font-weight: 400; }
.device-refresh { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 18px; }
.device-refresh > span { font-size: 10px; color: var(--text-muted); }
.device-refresh .el-button { padding: 0; height: 28px; font-size: 11px; color: var(--text-secondary); }
.device-refresh .el-button:hover { background: transparent; color: var(--accent); }
.capture-help { margin-top: auto; padding-top: 29px; }
.capture-help h3 { margin: 0 0 6px; font-size: 11px; font-weight: 500; color: var(--text-secondary); }
.capture-help p { margin: 0; font-size: 11px; line-height: 1.8; color: var(--text-muted); }
.capture-result { display: flex; align-items: flex-start; gap: 12px; padding: 17px; background: #edf3e8; color: #4d6d3f; border: 1px solid #d7e3ca; border-radius: 8px; }
.capture-result .el-icon { flex-shrink: 0; margin-top: 2px; }
.capture-result h3 { margin: 0; font-size: 14px; line-height: 1.7; }
.capture-result p { margin: 7px 0 0; font-size: 12px; }
.capture-result small { display: block; margin-top: 9px; font-size: 10px; }
.capture-result.is-error { background: #fcf3f0; color: #9c5145; border-color: #ead6cf; }
.workspace-footer { padding: 15px 28px; display: flex; align-items: center; gap: 10px; border-top: 1px solid #e9ede4; background: #fdfefa; color: #86927d; }
.workspace-footer svg { width: 17px; height: 17px; flex-shrink: 0; }
.workspace-footer p { margin: 0; font-size: 10px; line-height: 1.8; }
.diagnostic-panel { margin-top: 30px; overflow: hidden; border-radius: 12px; }
.diagnostic-count { color: var(--text-muted); font-size: 12px; font-variant-numeric: tabular-nums; }
.diagnostic-description { color: var(--text-secondary); font-size: 12px; margin-bottom: 20px; }
.diagnostic-metrics { margin: 0 0 22px; display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); border-top: 1px solid var(--border); border-left: 1px solid var(--border); }
.diagnostic-metrics > div { padding: 17px; border-right: 1px solid var(--border); border-bottom: 1px solid var(--border); min-width: 0; }
.diagnostic-metrics dt { color: var(--text-secondary); font-size: 11px; }
.diagnostic-metrics dd { margin: 8px 0 0; font-size: 18px; line-height: 1.4; font-weight: 500; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.diagnostic-table { min-width: 0; width: 100%; overflow-x: auto; }
.diagnostic-notes { margin-top: 18px; }
.diagnostic-notes p { margin: 6px 0 0; color: var(--text-muted); font-size: 11px; }
@media (max-width: 1050px) {
  .public-kiosk { padding-left: 26px; padding-right: 26px; }
  .capture-layout { grid-template-columns: minmax(0, 1.25fr) minmax(300px, 1fr); }
  .capture-preview { padding: 22px; }
  .capture-controls { padding: 28px 24px 22px; }
  .capture-steps { padding: 20px 22px; gap: 16px; }
  .capture-steps li { gap: 9px; font-size: 11px; }
  .capture-steps li:not(:last-child)::after { margin-left: 0; }
  .diagnostic-metrics { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
@media (max-width: 760px) {
  .public-kiosk { padding: 0 16px 28px; }
  .public-header { height: 78px; gap: 16px; }
  .header-context { display: none; }
  .public-nav { font-size: 11px; gap: 12px; }
  .kiosk-main { padding-top: 27px; }
  .kiosk-heading { align-items: flex-start; margin-bottom: 23px; gap: 18px; }
  .page-category { font-size: 10px; margin-bottom: 8px; }
  .kiosk-heading h1 { font-size: 27px; }
  .kiosk-clock { max-width: 178px; padding-top: 2px; }
  .kiosk-clock span { font-size: 10px; margin-bottom: 7px; }
  .kiosk-clock time { font-size: 11px; line-height: 1.8; display: block; }
  .capture-steps { padding: 18px 16px; gap: 8px; }
  .capture-steps li { flex-direction: column; align-items: flex-start; gap: 8px; font-size: 10px; }
  .capture-steps li:not(:last-child)::after { position: absolute; left: 40px; right: 8px; top: 14px; }
  .step-number { width: 28px; height: 28px; }
  .capture-layout { grid-template-columns: 1fr; }
  .capture-preview { padding: 20px; border-right: none; border-bottom: 1px solid #e9ede4; }
  .preview-topline { margin-bottom: 12px; }
  .camera-shell { aspect-ratio: 4 / 3; }
  .standby-face { width: 34%; max-height: 150px; margin-bottom: 8px; }
  .camera-placeholder small { font-size: 9px; margin-top: 8px; }
  .preview-footnote { padding-top: 14px; }
  .capture-controls { padding: 25px 22px; }
  .capture-controls h2 { font-size: 22px; margin-bottom: 18px; }
  .capture-instruction { min-height: auto; padding: 14px; }
  .punch-actions { grid-template-columns: 1fr 1fr; margin-top: 20px; gap: 10px; }
  .punch-actions > .el-button:only-child { grid-column: 1 / -1; }
  .punch-actions .el-button { padding: 12px 15px; font-size: 13px; height: 48px; }
  .capture-help { padding-top: 21px; }
  .workspace-footer { padding: 15px 20px; align-items: flex-start; }
  .workspace-footer svg { margin-top: 1px; }
  .diagnostic-metrics > div { padding: 12px 10px; }
  .diagnostic-metrics dt { font-size: 10px; line-height: 1.6; }
  .diagnostic-metrics dd { font-size: 16px; }
  .diagnostic-count { white-space: nowrap; }
}
@media (max-width: 380px) {
  .public-kiosk { padding-left: 12px; padding-right: 12px; }
  .capture-preview { padding: 16px; }
  .capture-controls { padding: 23px 18px; }
  .kiosk-clock { max-width: 150px; }
  .punch-actions .el-button { padding: 12px; font-size: 12px; }
  .device-refresh { flex-wrap: wrap; }
}
</style>
