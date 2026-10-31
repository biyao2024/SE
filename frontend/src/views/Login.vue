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
    const dest = String(route.query.redirect || '/')
    await router.replace(
      auth.user?.mustChangePassword
        ? '/password'
        : dest.startsWith('/') && !dest.startsWith('//') ? dest : '/',
    )
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <header class="auth-header">
      <div class="brand">
        <span class="brand-mark">知</span>
        <span>知行<small>考勤管理</small></span>
      </div>
      <router-link class="auth-kiosk-link" to="/kiosk">刷脸打卡 <span aria-hidden="true">↗</span></router-link>
    </header>

    <main class="auth-main">
      <section class="auth-overview" aria-labelledby="overview-title">
        <span class="section-label">管理入口</span>
        <h1 id="overview-title">考勤管理</h1>
        <dl class="module-directory">
          <div>
            <dt>人员档案</dt>
            <dd>维护员工信息、部门与人脸档案</dd>
          </div>
          <div>
            <dt>考勤记录</dt>
            <dd>查询日常出勤，筛选并导出报表</dd>
          </div>
          <div>
            <dt>请假与补卡</dt>
            <dd>提交申请、处理审批与查看结果</dd>
          </div>
        </dl>
        <p class="overview-note">登录后，根据账号权限显示可用功能。</p>
      </section>

      <section class="auth-form-area" aria-labelledby="login-title">
        <div class="auth-form">
          <h2 id="login-title">账号登录</h2>
          <p class="form-description">使用管理员分配的账号登录。</p>
          <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
          <el-form label-position="top" @submit.prevent="submit">
            <el-form-item label="账号">
              <el-input v-model="username" placeholder="输入账号" autocomplete="username" maxlength="64" />
            </el-form-item>
            <el-form-item label="密码">
              <el-input v-model="password" type="password" show-password placeholder="输入密码" autocomplete="current-password" />
            </el-form-item>
            <el-button class="auth-submit" type="primary" native-type="submit" :loading="loading">登录</el-button>
          </el-form>
          <p class="auth-account-help">首次登录需修改初始密码。忘记密码请联系管理员。</p>
          <div class="auth-alternative">
            <span>只需记录出勤？</span>
            <router-link to="/kiosk">前往刷脸打卡 <span aria-hidden="true">→</span></router-link>
          </div>
        </div>
      </section>
    </main>
    <footer class="auth-footer">知行 · 人脸识别考勤系统</footer>
  </div>
</template>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  padding: 0 52px;
  background: var(--surface-subtle);
}
.auth-header {
  width: 100%;
  max-width: 1160px;
  margin: 0 auto;
  padding: 32px 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--border);
}
.auth-kiosk-link {
  display: inline-flex;
  align-items: center;
  gap: 18px;
  font-size: 13px;
  color: var(--text-secondary);
}
.auth-kiosk-link:hover, .auth-alternative a:hover { color: var(--accent); }
.auth-main {
  width: 100%;
  max-width: 1020px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 88px;
  margin: auto;
  padding: 66px 0;
  align-items: center;
}
.section-label { font-size: 12px; color: var(--text-muted); }
.auth-overview h1 { margin: 18px 0 36px; font-size: 36px; font-weight: 500; letter-spacing: -1px; }
.module-directory { margin: 0; max-width: 370px; }
.module-directory > div { padding: 17px 0; display: grid; grid-template-columns: 92px 1fr; gap: 12px; border-top: 1px solid var(--border); }
.module-directory dt { font-size: 13px; font-weight: 500; line-height: 1.8; }
.module-directory dd { margin: 0; font-size: 12px; color: var(--text-secondary); line-height: 1.9; }
.overview-note { margin: 21px 0 0; font-size: 11px; color: var(--text-muted); }
.auth-form-area { padding: 40px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); }
.auth-form h2 { font-size: 23px; margin-bottom: 10px; font-weight: 500; }
.form-description { color: var(--text-secondary); font-size: 12px; margin-bottom: 30px; }
.auth-form :deep(.el-input__wrapper) { min-height: 44px; }
.auth-submit { width: 100%; height: 44px; margin-top: 5px; font-size: 14px; }
.auth-account-help { font-size: 11px; line-height: 1.9; color: var(--text-muted); margin: 17px 0 0; }
.auth-alternative { margin-top: 28px; padding-top: 22px; border-top: 1px solid var(--border); display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; font-size: 12px; }
.auth-alternative > span { color: var(--text-muted); }
.auth-alternative a { color: var(--accent); }
.auth-alternative a span { margin-left: 10px; }
.auth-footer { width: 100%; max-width: 1160px; margin: 0 auto; padding: 20px 0 25px; border-top: 1px solid var(--border); color: var(--text-muted); font-size: 11px; }
@media (max-width: 1000px) {
  .auth-page { padding: 0 32px; }
  .auth-main { gap: 44px; }
  .auth-form-area { padding: 32px; }
  .module-directory > div { grid-template-columns: 1fr; gap: 4px; }
}
@media (max-width: 720px) {
  .auth-page { padding: 0 22px; }
  .auth-header { padding: 24px 0; }
  .auth-main { display: block; max-width: 420px; padding: 44px 0; }
  .auth-overview { display: none; }
  .auth-form-area { padding: 30px 26px; }
  .auth-form h2 { font-size: 22px; }
  .auth-footer { text-align: center; }
}
@media (max-width: 380px) {
  .auth-page { padding: 0 16px; }
  .auth-form-area { padding: 28px 20px; }
}
</style>
