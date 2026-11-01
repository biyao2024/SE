<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { House, Calendar, DocumentChecked, User, ArrowDown, Lock, SwitchButton } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useAuth } from '../stores/auth'
import { errorText } from '../api'

const auth = useAuth()
const router = useRouter()
const accountMenu = ref<{ handleClose: () => void } | null>(null)
const links = [
  { path: '/employee', label: '工作台', icon: House },
  { path: '/employee/attendance', label: '我的考勤', icon: Calendar },
  { path: '/employee/requests', label: '我的申请', icon: DocumentChecked },
  { path: '/employee/profile', label: '我的资料', icon: User },
]
async function logout() {
  try { await auth.logout(); await router.replace('/login') }
  catch (e) { ElMessage.error(errorText(e)) }
}
</script>

<template>
  <div class="employee-app" @keydown.esc="accountMenu?.handleClose()">
    <header class="employee-header">
      <div class="employee-header-inner">
        <router-link class="brand" to="/employee" aria-label="知行员工工作台">
          <span class="brand-mark" aria-hidden="true"><svg viewBox="0 0 32 32" fill="none"><path d="M8 8h16L8 24h16M8 16h16" stroke="currentColor" stroke-width="2.4" stroke-linecap="square" /></svg></span>
          <span>知行<small>员工工作台</small></span>
        </router-link>
        <nav class="employee-nav" aria-label="员工导航">
          <router-link v-for="link in links" :key="link.path" :to="link.path"><el-icon><component :is="link.icon" /></el-icon><span>{{ link.label }}</span></router-link>
        </nav>
        <el-dropdown ref="accountMenu" trigger="click">
          <button class="employee-account" aria-label="员工账号菜单"><span class="avatar">{{ auth.user?.name?.slice(0,1) }}</span><span>{{ auth.user?.name }}</span><el-icon><ArrowDown /></el-icon></button>
          <template #dropdown><el-dropdown-menu><el-dropdown-item :icon="Lock" @click="router.push('/employee/password')">修改密码</el-dropdown-item><el-dropdown-item :icon="SwitchButton" @click="logout">退出登录</el-dropdown-item></el-dropdown-menu></template>
        </el-dropdown>
      </div>
    </header>
    <main class="employee-content"><router-view /></main>
    <footer class="employee-footer">知行考勤<span>所有考勤日期按北京时间计算</span></footer>
  </div>
</template>

<style scoped>
.employee-app { min-height: 100vh; background: #f5f7f2; }
.employee-header { background: #fff; border-bottom: 1px solid #e1e7dc; }
.employee-header-inner { display: flex; align-items: center; justify-content: space-between; gap: 25px; max-width: 1240px; min-height: 82px; margin: 0 auto; padding: 0 32px; }
.employee-header .brand { flex-shrink: 0; font-size: 22px; }
.employee-nav { display: flex; gap: 6px; align-self: stretch; }
.employee-nav a { display: flex; align-items: center; gap: 8px; padding: 0 17px; border-bottom: 2px solid transparent; color: #77816f; font-size: 13px; transition: color .15s, background .15s; }
.employee-nav a:hover { color: #294c38; background: #fafbf7; }
.employee-nav .el-icon { font-size: 17px; }
.employee-nav .router-link-exact-active { color: #2b503c; border-bottom-color: #496e51; font-weight: 600; }
.employee-account { display: flex; align-items: center; gap: 10px; border: 0; background: transparent; color: #4e5a47; font-size: 12px; }
.employee-account > .el-icon { font-size: 10px; margin-left: 4px; }
.employee-content { max-width: 1200px; margin: 0 auto; padding: 36px 32px 32px; min-height: calc(100vh - 150px); }
.employee-footer { max-width: 1200px; margin: 0 auto; display: flex; justify-content: space-between; gap: 16px; padding: 0 32px 26px; font-size: 10px; color: #8d9686; }
@media (max-width: 950px) {
  .employee-header-inner { gap: 15px; padding: 0 24px; }
  .employee-nav a { padding: 0 11px; font-size: 12px; }
  .employee-content { padding: 28px 24px; }
}
@media (max-width: 700px) {
  .employee-header-inner { min-height: 70px; padding: 0 20px; }
  .employee-header .brand { font-size: 20px; }
  .employee-header .brand small { font-size: 9px; }
  .employee-account > span:nth-child(2) { max-width: 90px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .employee-nav { position: fixed; z-index: 25; left: 0; right: 0; bottom: 0; background: #fff; border-top: 1px solid #e0e6db; padding: 6px 8px calc(6px + env(safe-area-inset-bottom)); justify-content: space-around; gap: 0; }
  .employee-nav a { flex: 1; min-height: 54px; flex-direction: column; justify-content: center; gap: 5px; padding: 5px 3px; border: 0; font-size: 10px; }
  .employee-nav a .el-icon { font-size: 21px; }
  .employee-nav .router-link-exact-active { background: #f0f4eb; border-radius: 9px; }
  .employee-content { padding: 24px 16px; min-height: calc(100vh - 190px); }
  .employee-footer { padding: 0 18px calc(88px + env(safe-area-inset-bottom)); font-size: 9px; }
}
</style>
