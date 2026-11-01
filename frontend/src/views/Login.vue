<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '../stores/auth'
import { errorText } from '../api'

const auth = useAuth()
const router = useRouter()
const route = useRoute()
const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref(
  route.query.reason === 'expired'
    ? '登录已过期，请重新登录。'
    : route.query.reason === 'unavailable'
      ? '业务服务暂时不可用，请确认服务已启动后重试。'
      : '',
)

async function submit() {
  if (!username.value.trim() || !password.value) {
    error.value = '请输入账号和密码'
    return
  }

  loading.value = true
  error.value = ''
  try {
    await auth.login(username.value.trim(), password.value)
    const home = auth.isAdmin ? '/' : '/employee'
    const dest = String(route.query.redirect || home)
    await router.replace(
      auth.user?.mustChangePassword
        ? (auth.isAdmin ? '/password' : '/employee/password')
        : dest.startsWith('/') && !dest.startsWith('//') ? dest : home,
    )
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-identity" aria-labelledby="product-title">
      <div class="brand identity-brand">
        <span class="brand-mark" aria-hidden="true"><svg viewBox="0 0 32 32" fill="none"><path d="M8 8h16L8 24h16M8 16h16" stroke="currentColor" stroke-width="2.4" stroke-linecap="square" /></svg></span>
        <span>知行<small>考勤管理系统</small></span>
      </div>
      <div class="identity-content">
        <div class="product-heading">
          <span class="product-category">考勤工作台</span>
          <h1 id="product-title">考勤管理</h1>
        </div>
        <div class="product-illustration" aria-hidden="true">
          <svg viewBox="0 0 480 390" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M50 322H433M50 94H433M102 48V346M379 48V346" stroke="#53685B" />
            <path d="M50 208H433M241 48V346" stroke="#53685B" stroke-dasharray="3 8" />
            <rect x="84" y="73" width="274" height="274" rx="20" fill="#283B30" stroke="#67816E" />
            <rect x="100" y="89" width="242" height="242" rx="12" fill="#354D3B" />
            <path d="M131 144V123H153M289 123H311V144M131 274V296H153M289 296H311V274" stroke="#B6D0AD" stroke-width="2.5" stroke-linecap="round" />
            <path d="M180 181C180 156 197 140 221 140C245 140 262 156 262 181V201C262 230 245 250 221 250C197 250 180 230 180 201V181Z" stroke="#A5C69A" stroke-width="2" />
            <path d="M199 188H204M238 188H243M221 195V210H228M209 225C217 230 225 230 233 225" stroke="#A5C69A" stroke-width="2" stroke-linecap="round" />
            <path d="M164 283C172 264 193 255 221 255C249 255 270 264 278 283" stroke="#A5C69A" stroke-width="2" stroke-linecap="round" />
            <path d="M145 206H296" stroke="#CEE1BE" />
            <circle cx="145" cy="206" r="3" fill="#CEE1BE" />
            <circle cx="296" cy="206" r="3" fill="#CEE1BE" />
            <rect x="296" y="216" width="132" height="138" rx="12" fill="#1B2D22" fill-opacity=".3" />
            <rect x="288" y="204" width="132" height="138" rx="12" fill="#EEF1E4" />
            <path d="M308 225H334M308 237H362" stroke="#A2AF98" stroke-width="3" stroke-linecap="round" />
            <path d="M308 255H400" stroke="#CFD8C7" />
            <circle cx="315" cy="276" r="7" fill="#D5E0CC" />
            <path d="M312 276L314 278L318 274" stroke="#547148" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
            <path d="M333 276H389M333 285H372" stroke="#A2AF98" stroke-width="3" stroke-linecap="round" />
            <circle cx="315" cy="310" r="7" fill="#D5E0CC" />
            <path d="M312 310L314 312L318 308" stroke="#547148" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
            <path d="M333 310H389M333 319H363" stroke="#A2AF98" stroke-width="3" stroke-linecap="round" />
            <rect x="54" y="174" width="62" height="62" rx="12" fill="#D0DFBD" />
            <path d="M72 195V191H78M92 191H98V197M72 213V219H78M92 219H98V213M78 204L83 209L92 200" stroke="#3D5938" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </div>
      </div>
      <div class="identity-footer"><span>人员档案</span><span>出勤记录</span><span>请假补卡</span></div>
    </section>

    <section class="auth-access" aria-labelledby="login-title">
      <header class="access-header">
        <div class="brand mobile-brand"><span class="brand-mark" aria-hidden="true"><svg viewBox="0 0 32 32" fill="none"><path d="M8 8h16L8 24h16M8 16h16" stroke="currentColor" stroke-width="2.4" stroke-linecap="square" /></svg></span><span>知行</span></div>
        <router-link class="access-link" to="/kiosk">刷脸打卡 <span aria-hidden="true">↗</span></router-link>
      </header>
      <div class="auth-form">
        <div class="form-heading">
          <span class="form-section">账号登录</span>
          <h2 id="login-title">登录考勤工作台</h2>
          <p>使用管理员分配的账号登录。</p>
        </div>
        <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
        <el-form label-position="top" @submit.prevent="submit">
          <el-form-item label="账号">
            <el-input v-model="username" placeholder="输入账号" autocomplete="username" maxlength="64" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="password" type="password" show-password placeholder="输入密码" autocomplete="current-password" />
          </el-form-item>
          <el-button class="auth-submit" type="primary" native-type="submit" :loading="loading">
            <span>登录</span><span class="submit-arrow" aria-hidden="true">→</span>
          </el-button>
        </el-form>
        <p class="auth-account-help">首次登录需修改初始密码。忘记密码请联系管理员。</p>
        <router-link class="auth-alternative" to="/kiosk">
          <span class="alternative-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none">
              <path d="M8 5L9.5 3H14.5L16 5H20V20H4V5H8Z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" />
              <circle cx="12" cy="12" r="3.5" stroke="currentColor" stroke-width="1.5" />
            </svg>
          </span>
          <span><strong>刷脸打卡</strong><small>在已授权设备上记录出勤</small></span>
          <span class="alternative-arrow" aria-hidden="true">↗</span>
        </router-link>
      </div>
      <footer class="access-footer"><span>知行 · 考勤管理</span><span>账号由管理员创建</span></footer>
    </section>
  </main>
</template>

<style scoped>
.auth-page { min-height: 100vh; display: grid; grid-template-columns: minmax(400px, 48%) minmax(0, 1fr); padding: 20px; gap: 20px; background: #f7f8f4; }
.auth-identity { display: flex; flex-direction: column; min-height: calc(100vh - 40px); padding: 40px 48px 30px; overflow: hidden; background: #293f32; color: #f2f4e9; border-radius: 16px; }
.identity-brand { align-self: flex-start; }
.identity-brand .brand-mark { border-color: #84977c; color: #d4e5bd; background: #3c5642; }
.auth-page .brand-mark svg { width: 28px; height: 28px; }
.identity-brand small { color: #b6c6ad; letter-spacing: 1px; }
.identity-content { width: 100%; max-width: 490px; margin: auto; padding: 44px 0 24px; }
.product-category { display: inline-block; font-size: 11px; letter-spacing: 2px; color: #b1c3a7; }
.product-heading h1 { font-size: clamp(32px, 3.4vw, 48px); font-weight: 500; letter-spacing: 2px; margin: 15px 0 10px; line-height: 1.35; }
.product-illustration { width: calc(100% + 38px); margin: 16px 0 0 -22px; }
.product-illustration svg { display: block; width: 100%; height: auto; }
.identity-footer { display: flex; justify-content: space-between; gap: 16px; padding-top: 24px; border-top: 1px solid #53674f; color: #c0cfb6; font-size: 11px; letter-spacing: 1px; }
.auth-access { display: flex; flex-direction: column; min-width: 0; padding: 22px 42px 11px; }
.access-header { display: flex; align-items: center; justify-content: flex-end; }
.mobile-brand { display: none; }
.access-link { display: inline-flex; align-items: center; gap: 22px; font-size: 12px; color: var(--text-secondary); padding: 10px 0; }
.access-link:hover { color: var(--accent); }
.auth-form { width: 100%; max-width: 370px; margin: auto; padding: 44px 0; }
.form-heading { margin-bottom: 34px; }
.form-section { display: block; font-size: 11px; letter-spacing: 1px; color: var(--accent); margin-bottom: 14px; }
.form-heading h2 { margin-bottom: 12px; font-size: 29px; line-height: 1.4; font-weight: 550; letter-spacing: -.8px; }
.form-heading p { margin: 0; color: var(--text-secondary); font-size: 12px; }
.auth-form :deep(.el-form-item) { margin-bottom: 25px; }
.auth-form :deep(.el-form-item__label) { margin-bottom: 9px; font-size: 12px; }
.auth-form :deep(.el-input__wrapper) { min-height: 48px; padding: 3px 15px; border-radius: 7px; background: #fff; }
.auth-form :deep(.el-input__inner) { font-size: 13px; }
.auth-submit { width: 100%; height: 48px; margin-top: 7px; border-radius: 7px; font-size: 14px; }
.auth-submit :deep(> span) { width: 100%; display: flex; align-items: center; justify-content: space-between; }
.submit-arrow { font-size: 19px; font-weight: 400; }
.auth-account-help { font-size: 11px; line-height: 1.9; color: var(--text-muted); margin: 18px 0 0; }
.auth-alternative { display: flex; align-items: center; gap: 15px; margin-top: 37px; padding: 22px 0 0; border-top: 1px solid var(--border); }
.alternative-icon { flex-shrink: 0; width: 42px; height: 42px; display: grid; place-items: center; color: #60735b; border: 1px solid #dce3d5; border-radius: 9px; background: #eff2e9; }
.alternative-icon svg { width: 21px; height: 21px; }
.auth-alternative strong { display: block; font-size: 13px; font-weight: 500; }
.auth-alternative small { display: block; margin-top: 5px; font-size: 11px; color: var(--text-muted); }
.alternative-arrow { margin-left: auto; font-size: 19px; color: var(--text-secondary); }
.auth-alternative:hover .alternative-arrow { color: var(--accent); transform: translate(2px, -2px); }
.access-footer { display: flex; justify-content: space-between; gap: 20px; font-size: 10px; color: var(--text-muted); }
@media (min-width: 1500px) {
  .auth-page { padding: 28px; gap: 28px; }
  .auth-identity { min-height: calc(100vh - 56px); padding: 48px 64px 36px; }
  .auth-form { max-width: 400px; }
}
@media (max-width: 1000px) {
  .auth-page { grid-template-columns: minmax(320px, 46%) minmax(0, 1fr); padding: 14px; gap: 14px; }
  .auth-identity { min-height: calc(100vh - 28px); padding: 32px 30px 26px; }
  .auth-access { padding: 17px 24px 12px; }
  .identity-content { padding-top: 35px; }
  .form-heading h2 { font-size: 26px; }
  .identity-footer { font-size: 10px; gap: 10px; }
}
@media (max-width: 760px) {
  .auth-page { display: block; padding: 0; }
  .auth-identity { display: none; }
  .auth-access { min-height: 100vh; padding: 28px; }
  .access-header { justify-content: space-between; }
  .mobile-brand { display: inline-flex; }
  .auth-form { padding: 56px 0; max-width: 390px; }
  .form-heading h2 { font-size: 29px; }
  .access-footer { padding-top: 20px; border-top: 1px solid var(--border); font-size: 10px; }
}
@media (max-width: 380px) {
  .auth-access { padding: 24px; }
  .auth-form { padding: 44px 0; }
  .form-heading h2 { font-size: 26px; }
}
</style>
