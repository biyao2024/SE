<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '../stores/auth'
import { errorText } from '../api'
import { ElMessage } from 'element-plus'
import { Lock } from '@element-plus/icons-vue'
const auth = useAuth(), router = useRouter(), oldPassword = ref(''), newPassword = ref(''), confirm = ref(''), loading = ref(false), error = ref('')
async function submit() { error.value=''; if (newPassword.value.length<12 || !/[A-Za-z]/.test(newPassword.value) || !/[0-9]/.test(newPassword.value) || new TextEncoder().encode(newPassword.value).length>72) { error.value='新密码至少12字符，包含字母和数字，UTF-8编码不超过72字节'; return }; if (newPassword.value===oldPassword.value) { error.value='新密码不能与当前密码相同'; return }; if (newPassword.value!==confirm.value) { error.value='两次输入的新密码不一致'; return }; loading.value=true; try { await auth.password(oldPassword.value,newPassword.value); oldPassword.value=''; newPassword.value=''; confirm.value=''; ElMessage.success('密码修改成功'); router.replace(auth.isAdmin ? '/' : '/employee') } catch(e) { error.value=errorText(e) } finally { loading.value=false } }
</script>
<template>
  <div class="page-head"><div><h1>修改密码</h1><p>管理当前账号的登录凭据</p></div></div>
  <el-alert v-if="auth.user?.mustChangePassword" title="首次登录，请修改初始密码。" type="warning" show-icon :closable="false" />
  <section class="password-workspace">
    <aside class="password-summary">
      <span class="password-symbol"><el-icon><Lock /></el-icon></span>
      <h2>账号安全</h2>
      <p>新密码至少 12 字符，包含字母和数字，且与当前密码不同。</p>
      <div class="password-account"><span class="avatar">{{auth.user?.name?.slice(0,1)||'用'}}</span><div>{{auth.user?.name||auth.user?.username}}<small>{{auth.user?.username}}</small></div></div>
    </aside>
    <div class="panel password-form">
      <div class="panel-head"><div><h2>更新登录密码</h2><p>填写当前密码验证身份</p></div></div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="当前密码"><el-input v-model="oldPassword" type="password" show-password autocomplete="current-password" placeholder="输入当前密码" /></el-form-item>
        <div class="password-divider" />
        <el-form-item label="新密码"><el-input v-model="newPassword" type="password" show-password autocomplete="new-password" placeholder="至少 12 位，与当前密码不同" /></el-form-item>
        <el-form-item label="确认新密码"><el-input v-model="confirm" type="password" show-password autocomplete="new-password" placeholder="再次输入新密码" /></el-form-item>
        <div class="password-actions"><el-button type="primary" native-type="submit" :loading="loading">保存新密码</el-button></div>
      </el-form>
    </div>
  </section>
</template>

<style scoped>
.password-workspace { max-width: 900px; display: grid; grid-template-columns: 240px minmax(0, 1fr); gap: 48px; border-top: 1px solid var(--border); padding-top: 32px; }
.password-summary { padding-top: 8px; }
.password-symbol { display: grid; place-items: center; width: 48px; height: 48px; background: #eaf0e5; color: #55734f; border: 1px solid #dce5d4; border-radius: 14px; margin-bottom: 24px; font-size: 23px; }
.password-summary h2 { margin-bottom: 10px; font-size: 15px; }
.password-summary p { font-size: 12px; color: var(--text-muted); }
.password-account { display: flex; align-items: center; gap: 12px; border-top: 1px solid var(--border); padding-top: 24px; margin-top: 28px; font-size: 12px; }
.password-account small { display: block; margin-top: 5px; font-size: 11px; color: var(--text-muted); }
.password-form { padding: 32px; }
.password-form .panel-head { margin-bottom: 30px; }
.password-form :deep(.el-input__wrapper) { min-height: 43px; }
.password-divider { margin-bottom: 24px; border-top: 1px solid #edf0e9; }
.password-actions { display: flex; justify-content: flex-end; border-top: 1px solid #edf0e9; padding-top: 20px; margin-top: 30px; }
@media (max-width: 1000px) { .password-workspace { grid-template-columns: 190px minmax(0, 1fr); gap: 26px; } }
@media (max-width: 700px) { .password-workspace { grid-template-columns: 1fr; gap: 24px; padding-top: 22px; } .password-summary { display: grid; grid-template-columns: 48px 1fr; gap: 0 16px; } .password-symbol { grid-row: span 2; margin-bottom: 0; } .password-summary h2 { margin-bottom: 4px; } .password-summary p { margin: 0; } .password-account { grid-column: span 2; margin-top: 18px; padding-top: 18px; } .password-form { padding: 24px 20px; } }
</style>
