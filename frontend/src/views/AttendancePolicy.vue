<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { Camera, Location, Connection, Plus, Aim, Edit, Delete, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, post, put, remove, errorText } from '../api'
import { useLoad } from '../composables'
import { useLocation } from '../useLocation'
import { modeLabels, type AttendanceMode, type AttendancePolicy, type AttendanceLocation } from '../attendancePolicy'

const policy = ref<AttendancePolicy | null>(null)
const selectedMode = ref<AttendanceMode>('FACE')
const locations = ref<AttendanceLocation[]>([])
const savingPolicy = ref(false), policyError = ref('')
const dialog = ref(false), editing = ref<number | null>(null), savingLocation = ref(false), formError = ref('')
const { locating, locationError, locate, clearLocation } = useLocation()
const form = reactive({ name: '', address: '', latitude: undefined as number | undefined, longitude: undefined as number | undefined, radiusMeters: 200, active: true })
const activeCount = computed(() => locations.value.filter(location => location.active).length)
const modes: { value: AttendanceMode; title: string; description: string; icon: typeof Camera }[] = [
  { value: 'FACE', title: '人脸打卡', description: '使用已授权的公共终端完成身份识别', icon: Camera },
  { value: 'LOCATION', title: '定位打卡', description: '员工登录后，在指定地点内签到或签退', icon: Location },
  { value: 'FACE_OR_LOCATION', title: '人脸或定位', description: '两种方式任选，汇入同一份考勤记录', icon: Connection },
]
const { loading, error, load } = useLoad(async () => {
  const [p, l] = await Promise.all([api<AttendancePolicy>('/attendance-policy'), api<AttendanceLocation[]>('/attendance-locations')])
  policy.value = p; selectedMode.value = p.mode; locations.value = l
})

async function savePolicy() {
  if (!policy.value || savingPolicy.value) return
  policyError.value = ''
  if (selectedMode.value !== 'FACE' && !activeCount.value) { policyError.value = '请先添加并启用至少一个考勤地点'; return }
  savingPolicy.value = true
  try { policy.value = await put<AttendancePolicy>('/attendance-policy', { mode: selectedMode.value }); selectedMode.value = policy.value.mode; ElMessage.success('考勤方式已更新') }
  catch (e) { policyError.value = errorText(e) }
  finally { savingPolicy.value = false }
}

function editLocation(location?: AttendanceLocation) {
  clearLocation(); editing.value = location?.id ?? null; formError.value = ''
  Object.assign(form, location ? { name: location.name, address: location.address, latitude: location.latitude, longitude: location.longitude, radiusMeters: location.radiusMeters, active: location.active } : { name: '', address: '', latitude: undefined, longitude: undefined, radiusMeters: 200, active: true })
  dialog.value = true
}

async function useCurrentPosition() {
  formError.value = ''
  const position = await locate()
  if (!position || !dialog.value) return
  if (position.accuracyMeters <= 0 || position.accuracyMeters > Math.min(policy.value?.maxAccuracyMeters ?? 100, form.radiusMeters / 2)) {
    formError.value = `当前位置精度约 ±${Math.round(position.accuracyMeters)} 米，请在地点附近重新定位，或填写准确坐标。`
    return
  }
  form.latitude = Number(position.latitude.toFixed(6)); form.longitude = Number(position.longitude.toFixed(6))
  ElMessage.success('已填入当前位置，请确认地点名称和考勤半径')
}

async function saveLocation() {
  if (savingLocation.value) return
  formError.value = ''
  if (!form.name.trim()) { formError.value = '请填写地点名称'; return }
  if (form.latitude === undefined || form.longitude === undefined || !Number.isFinite(form.latitude) || !Number.isFinite(form.longitude)) { formError.value = '请获取当前位置或填写完整的经纬度'; return }
  if (form.latitude < -90 || form.latitude > 90 || form.longitude < -180 || form.longitude > 180) { formError.value = '纬度范围为 -90 至 90，经度范围为 -180 至 180'; return }
  if (!Number.isInteger(form.radiusMeters) || form.radiusMeters < 50 || form.radiusMeters > 5000) { formError.value = '考勤半径须为 50 至 5000 米的整数'; return }
  savingLocation.value = true
  try {
    const payload = { ...form, name: form.name.trim(), address: form.address.trim() }
    if (editing.value !== null) await put('/attendance-locations/' + editing.value, payload)
    else await post('/attendance-locations', payload)
    dialog.value = false; ElMessage.success('考勤地点已保存')
    locations.value = await api<AttendanceLocation[]>('/attendance-locations')
  } catch (e) { if (dialog.value) formError.value = errorText(e); else ElMessage.error('地点已保存，但列表刷新失败：' + errorText(e)) }
  finally { savingLocation.value = false }
}

async function deleteLocation(location: AttendanceLocation) {
  try {
    await ElMessageBox.confirm(`删除「${location.name}」后，员工将不能再在该地点定位打卡。历史记录保留。`, '删除考勤地点', { confirmButtonText: '删除地点', cancelButtonText: '保留地点', type: 'warning' })
    await remove('/attendance-locations/' + location.id)
    locations.value = await api<AttendanceLocation[]>('/attendance-locations')
    ElMessage.success('考勤地点已删除')
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(errorText(e)) }
}
</script>

<template>
  <div class="policy-page">
    <header class="page-head"><div><h1>考勤方式与地点</h1><p>配置员工可用的打卡方式与到岗范围</p></div><el-button :icon="Refresh" :loading="loading" @click="load">刷新配置</el-button></header>
    <div v-if="error" class="error-panel">{{ error }}<el-button text @click="load">重试</el-button></div>
    <section class="policy-section" v-loading="loading">
      <div class="policy-heading"><div><h2>考勤方式</h2><p>适用于全体员工，保存后立即生效</p></div><span v-if="policy" class="current-mode">当前：{{ modeLabels[policy.mode] }}</span></div>
      <div class="mode-options" role="radiogroup" aria-label="考勤方式">
        <label v-for="mode in modes" :key="mode.value" class="mode-option" :class="{ selected: selectedMode === mode.value }">
          <input v-model="selectedMode" type="radio" name="attendance-mode" :value="mode.value" :disabled="!policy || savingPolicy" />
          <span class="mode-icon"><el-icon><component :is="mode.icon" /></el-icon></span>
          <strong>{{ mode.title }}</strong><p>{{ mode.description }}</p><span class="mode-choice" aria-hidden="true" />
        </label>
      </div>
      <el-alert v-if="policyError" :title="policyError" type="error" :closable="false" />
      <div class="policy-save"><span>{{ selectedMode !== 'FACE' && !activeCount ? '启用定位前，请先在下方添加考勤地点。' : '签到、签退时间沿用「规则与日历」中的班次设置。' }}</span><el-button type="primary" :disabled="!policy || selectedMode === policy.mode" :loading="savingPolicy" @click="savePolicy">保存考勤方式</el-button></div>
    </section>

    <section class="locations-section">
      <header class="locations-heading"><div><h2>考勤地点 <span>{{ activeCount }} 个启用</span></h2><p>员工在任意一个启用地点的范围内，即可定位打卡</p></div><el-button type="primary" :icon="Plus" @click="editLocation()">添加地点</el-button></header>
      <div v-if="!locations.length && !loading && !error" class="locations-empty"><span class="empty-location-icon"><el-icon><Location /></el-icon></span><h3>尚未设置考勤地点</h3><p>在办公地点获取当前位置，或填写地点坐标与考勤半径。</p><el-button :icon="Plus" @click="editLocation()">添加第一个地点</el-button></div>
      <div class="locations-grid" v-loading="loading">
        <article v-for="location in locations" :key="location.id" class="location-card" :class="{ inactive: !location.active }">
          <header><span class="location-pin"><el-icon><Location /></el-icon></span><div><h3>{{ location.name }}</h3><p>{{ location.address || '未填写地址说明' }}</p></div><el-tag :type="location.active ? 'success' : 'info'">{{ location.active ? '已启用' : '已停用' }}</el-tag></header>
          <div class="location-range"><div class="radius-symbol" aria-hidden="true"><span /></div><div><span>考勤半径</span><strong>{{ location.radiusMeters }}<small>米</small></strong></div><div class="location-coordinates"><span>纬度 {{ location.latitude.toFixed(6) }}</span><span>经度 {{ location.longitude.toFixed(6) }}</span></div></div>
          <footer><span>以配置坐标为中心</span><div><el-button :icon="Edit" text @click="editLocation(location)">编辑</el-button><el-button :icon="Delete" text type="danger" @click="deleteLocation(location)">删除</el-button></div></footer>
        </article>
      </div>
    </section>

    <el-dialog v-model="dialog" :title="editing !== null ? '编辑考勤地点' : '添加考勤地点'" width="590px" :close-on-click-modal="false" @closed="clearLocation">
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" />
      <el-form label-position="top" @submit.prevent="saveLocation">
        <el-form-item label="地点名称" required><el-input v-model="form.name" maxlength="80" placeholder="例如：总部办公楼" /></el-form-item>
        <el-form-item label="地址说明"><el-input v-model="form.address" maxlength="200" placeholder="填写楼栋、街道等方便员工识别的信息" /></el-form-item>
        <div class="coordinate-toolbar"><div><strong>地点坐标</strong><span>使用浏览器定位的 WGS84 坐标</span></div><el-button :icon="Aim" :loading="locating" @click="useCurrentPosition">使用当前位置</el-button></div>
        <el-alert v-if="locationError" :title="locationError" type="warning" :closable="false" />
        <div class="form-grid"><el-form-item label="纬度" required><el-input-number v-model="form.latitude" :min="-90" :max="90" :precision="6" :controls="false" placeholder="-90 至 90" /></el-form-item><el-form-item label="经度" required><el-input-number v-model="form.longitude" :min="-180" :max="180" :precision="6" :controls="false" placeholder="-180 至 180" /></el-form-item></div>
        <div class="form-grid"><el-form-item label="考勤半径（米）" required><el-input-number v-model="form.radiusMeters" :min="50" :max="5000" :step="50" :precision="0" /><span class="field-note">范围 50–5000 米</span></el-form-item><el-form-item label="地点状态"><el-switch v-model="form.active" active-text="启用" inactive-text="停用" /></el-form-item></div>
      </el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="savingLocation" :disabled="locating" @click="saveLocation">保存地点</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.policy-section { border: 1px solid var(--border); background: #fff; border-radius: 12px; overflow: hidden; }
.policy-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 26px 28px 22px; }
.policy-heading h2, .locations-heading h2 { margin: 0; font-size: 16px; }
.policy-heading p, .locations-heading p { color: var(--text-muted); font-size: 12px; margin: 7px 0 0; }
.current-mode { font-size: 11px; border: 1px solid #dfe6d7; color: #617658; border-radius: 5px; padding: 6px 10px; white-space: nowrap; }
.mode-options { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; padding: 0 28px 26px; }
.mode-option { position: relative; border: 1px solid #e1e6dc; border-radius: 10px; padding: 22px; cursor: pointer; transition: background .16s, border-color .16s; }
.mode-option:hover { border-color: #9eaf93; }
.mode-option.selected { border-color: #66845e; background: #f3f7ee; box-shadow: inset 0 0 0 1px #66845e; }
.mode-option input { position: absolute; opacity: 0; inset: 0; width: 100%; height: 100%; margin: 0; cursor: pointer; }
.mode-option:has(input:focus-visible) { outline: 2px solid #63825b; outline-offset: 3px; }
.mode-icon { display: grid; place-items: center; width: 37px; height: 37px; color: #5a7451; background: #eef3e8; border: 1px solid #dfe8d6; border-radius: 9px; font-size: 20px; margin-bottom: 20px; }
.mode-option strong { font-size: 14px; font-weight: 600; }
.mode-option p { font-size: 11px; line-height: 1.9; margin: 8px 0 0; color: #7b8872; }
.mode-choice { position: absolute; top: 22px; right: 20px; width: 15px; height: 15px; border: 1px solid #cdd6c5; border-radius: 50%; pointer-events: none; }
.selected .mode-choice { border: 4px solid #55784b; background: #fff; }
.policy-save { display: flex; justify-content: space-between; align-items: center; gap: 20px; padding: 19px 28px; background: #fafbf8; border-top: 1px solid #e6ebdf; }
.policy-save > span { font-size: 11px; line-height: 1.8; color: #7c8873; }
.locations-section { margin-top: 32px; }
.locations-heading { display: flex; justify-content: space-between; align-items: center; gap: 18px; margin-bottom: 22px; }
.locations-heading h2 span { font-size: 11px; color: #829079; font-weight: 400; margin-left: 9px; }
.locations-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; }
.location-card { min-width: 0; border: 1px solid #dfe6d8; background: #fffefa; border-radius: 12px; overflow: hidden; }
.location-card.inactive { background: #fafbf8; }
.location-card header { display: flex; align-items: flex-start; gap: 12px; padding: 24px 24px 20px; }
.location-pin { display: grid; place-items: center; width: 38px; height: 38px; border-radius: 10px; background: #eff3e9; color: #678359; font-size: 22px; flex-shrink: 0; }
.location-card header > div { flex: 1; min-width: 0; }
.location-card h3 { margin: 0; font-size: 14px; overflow-wrap: anywhere; }
.location-card header p { margin: 5px 0 0; font-size: 11px; color: #859078; overflow-wrap: anywhere; }
.location-range { display: flex; gap: 18px; align-items: center; padding: 4px 24px 24px; }
.radius-symbol { width: 50px; height: 50px; flex-shrink: 0; display: grid; place-items: center; border: 1px dashed #c0ceaf; border-radius: 50%; background: #f2f6eb; }
.radius-symbol span { width: 9px; height: 9px; background: #688750; border: 2px solid #fff; border-radius: 50%; box-shadow: 0 0 0 7px #e1ead5; }
.location-range > div:nth-child(2) > span { display: block; font-size: 10px; color: #8b947f; }
.location-range strong { display: block; font-weight: 500; font-size: 28px; margin-top: 5px; font-variant-numeric: tabular-nums; color: #415939; }
.location-range strong small { font-size: 11px; color: #7f8d72; margin-left: 6px; }
.location-coordinates { margin-left: auto; display: grid; gap: 7px; font-size: 10px; color: #839077; font-variant-numeric: tabular-nums; }
.location-card footer { display: flex; justify-content: space-between; align-items: center; gap: 12px; border-top: 1px solid #e9eddf; background: #fbfcf8; padding: 12px 18px 12px 24px; }
.location-card footer > span { font-size: 10px; color: #8b957f; }
.location-card footer .el-button { height: 30px; font-size: 11px; padding: 4px 7px; margin: 0; }
.locations-empty { padding: 38px 22px; text-align: center; border: 1px dashed #d7e0cd; border-radius: 12px; background: #fafbf7; }
.empty-location-icon { display: grid; place-items: center; font-size: 28px; color: #879d72; margin-bottom: 15px; }
.locations-empty h3 { margin-bottom: 8px; }
.locations-empty p { font-size: 12px; color: #859077; margin-bottom: 19px; }
.coordinate-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 18px; margin: 5px 0 18px; padding-top: 20px; border-top: 1px solid var(--border); }
.coordinate-toolbar strong { display: block; font-size: 12px; font-weight: 500; }
.coordinate-toolbar span { display: block; font-size: 10px; color: var(--text-muted); margin-top: 5px; }
.form-grid :deep(.el-input-number) { width: 100%; }
.field-note { width: 100%; font-size: 10px; color: var(--text-muted); }
@media (max-width: 1100px) { .locations-grid { grid-template-columns: 1fr; } .mode-options { gap: 12px; } .mode-option { padding: 20px 16px; } }
@media (max-width: 700px) {
  .policy-heading { padding: 22px 18px; align-items: flex-start; flex-direction: column; gap: 12px; }
  .mode-options { grid-template-columns: 1fr; padding: 0 18px 20px; }
  .mode-option { display: grid; grid-template-columns: 37px 1fr; gap: 6px 13px; padding: 18px; }
  .mode-icon { grid-row: span 2; margin-bottom: 0; }
  .mode-option strong { padding-right: 24px; }
  .mode-option p { margin: 0; }
  .policy-save { padding: 18px; flex-direction: column; align-items: stretch; gap: 12px; }
  .locations-heading { align-items: flex-start; }
  .locations-heading p { font-size: 11px; }
  .locations-heading > .el-button { flex-shrink: 0; }
  .location-card header { padding: 20px 18px; }
  .location-range { padding: 0 18px 20px; gap: 13px; }
  .radius-symbol { width: 42px; height: 42px; }
  .location-coordinates { font-size: 9px; }
  .location-card footer { padding: 12px 14px 12px 18px; }
  .coordinate-toolbar { align-items: flex-start; flex-wrap: wrap; }
}
</style>
