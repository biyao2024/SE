<script setup lang="ts">
import { ref } from 'vue'
import { Plus, Monitor, ArrowRight } from '@element-plus/icons-vue'
import { api, post, remove, items, errorText } from '../api'
import { useLoad } from '../composables'
import { ElMessage, ElMessageBox } from 'element-plus'
const rows=ref<any[]>([]),name=ref(''),dialog=ref(false),saving=ref(false),formError=ref('')
const {loading,error,load}=useLoad(async()=>{rows.value=items(await api('/devices?size=200'))})
async function authorize(){if(!name.value.trim()){formError.value='请为当前浏览器填写一个容易识别的名称';return}saving.value=true;formError.value='';try{await post('/devices',{name:name.value.trim()});dialog.value=false;name.value='';ElMessage.success('本浏览器已授权，可以退出管理员账号并进入公共打卡页');load()}catch(e){formError.value=errorText(e)}finally{saving.value=false}}
async function revoke(row:any){try{await ElMessageBox.confirm(`撤销「${row.name}」后，该浏览器立即失去公共打卡权限。`,'撤销设备授权',{type:'warning',confirmButtonText:'撤销授权',cancelButtonText:'取消'});await remove(`/devices/${row.id}`);ElMessage.success('设备授权已撤销');load()}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(errorText(e))}}
</script>
<template>
  <div class="page-head">
    <div><h1>打卡设备</h1><p>管理公共终端的浏览器授权</p></div>
    <el-button type="primary" :icon="Plus" @click="dialog=true;formError=''">授权本浏览器</el-button>
  </div>

  <section class="device-guide">
    <div class="device-guide-title"><span class="device-symbol"><el-icon><Monitor /></el-icon></span><div><h2>接入公共终端</h2><p>请在实际用于打卡的设备上操作</p></div></div>
    <ol class="device-steps">
      <li><span>1</span><div><strong>登录管理员账号</strong><small>使用终端上的浏览器</small></div></li>
      <li><span>2</span><div><strong>授权本浏览器</strong><small>命名后获得 7 天授权</small></div></li>
      <li><span>3</span><div><strong>退出账号，开始打卡</strong><small>从登录页进入公共终端</small></div></li>
    </ol>
  </section>

  <section class="panel device-register">
    <div class="panel-head"><div><h2>授权记录</h2><p>授权仅在对应浏览器生效</p></div><router-link to="/kiosk" class="text-link">打开打卡页<el-icon><ArrowRight /></el-icon></router-link></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table :data="rows" v-loading="loading" empty-text="暂无设备授权，请先授权当前浏览器">
      <el-table-column label="设备名称" min-width="210"><template #default="{row}"><div class="name-cell"><span class="device-row-icon"><el-icon><Monitor /></el-icon></span><span>{{row.name}}</span></div></template></el-table-column>
      <el-table-column prop="createdAt" label="授权时间" min-width="185" />
      <el-table-column prop="expiresAt" label="有效期至" min-width="185" />
      <el-table-column label="状态" width="110"><template #default="{row}"><el-tag :type="row.revoked?'info':new Date(row.expiresAt)<new Date()?'warning':'success'">{{row.revoked?'已撤销':new Date(row.expiresAt)<new Date()?'已过期':'已授权'}}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="120"><template #default="{row}"><el-button text type="danger" :disabled="row.revoked" @click="revoke(row)">撤销授权</el-button></template></el-table-column>
    </el-table>
    <div class="device-table-footer">更换浏览器或清除浏览器 Cookie 后，需要重新授权。</div>
  </section>

  <el-dialog v-model="dialog" title="授权本浏览器" width="470px">
    <el-alert v-if="formError" :title="formError" type="error" :closable="false" />
    <el-form label-position="top" @submit.prevent="authorize"><el-form-item label="设备名称"><el-input v-model="name" placeholder="例如：一楼前台 · Chrome" maxlength="80" /></el-form-item></el-form>
    <p class="help-text">请在实际用于打卡的电脑或手机上授权。</p>
    <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="authorize">确认授权</el-button></template>
  </el-dialog>
</template>

<style scoped>
.device-guide { display: grid; grid-template-columns: 230px 1fr; gap: 24px; padding: 28px 0; margin-bottom: 28px; border-top: 1px solid var(--border); border-bottom: 1px solid var(--border); }
.device-guide-title { display: flex; align-items: center; gap: 14px; }
.device-guide-title h2 { margin-bottom: 5px; font-size: 14px; }
.device-guide-title p { margin: 0; font-size: 11px; color: var(--text-muted); }
.device-symbol { width: 46px; height: 46px; display: grid; place-items: center; border: 1px solid #d9e1d5; border-radius: 12px; font-size: 24px; color: #4d6a50; background: #edf2e9; }
.device-steps { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); align-items: center; gap: 24px; list-style: none; padding: 0; margin: 0; }
.device-steps li { display: flex; gap: 10px; align-items: flex-start; }
.device-steps li > span { font-size: 10px; width: 22px; height: 22px; display: grid; place-items: center; flex-shrink: 0; border-radius: 50%; border: 1px solid #d6dfd1; color: #6b7e63; }
.device-steps strong { display: block; font-size: 12px; font-weight: 500; line-height: 22px; }
.device-steps small { display: block; font-size: 10px; margin-top: 5px; color: var(--text-muted); }
.device-register { padding: 0; overflow: hidden; }
.device-register .panel-head { padding: 24px 26px 0; }
.device-register :deep(.el-table .cell) { padding-left: 26px; }
.device-row-icon { width: 34px; height: 34px; display: grid; place-items: center; background: #f1f4ef; border-radius: 9px; color: #6b7e63; font-size: 18px; }
.device-table-footer { padding: 18px 26px; font-size: 11px; color: var(--text-muted); border-top: 1px solid var(--border); }
.text-link { display: inline-flex; align-items: center; gap: 8px; font-size: 11px; color: var(--accent); }
@media (max-width: 1180px) { .device-guide { grid-template-columns: 1fr; } }
@media (max-width: 600px) { .device-guide { padding: 20px 0; } .device-steps { grid-template-columns: 1fr; gap: 18px; } .device-steps small { margin-top: 2px; } .device-register .panel-head { padding: 20px 16px 0; } .device-register :deep(.el-table .cell) { padding-left: 16px; } .device-table-footer { padding: 16px; line-height: 1.7; } }
</style>
