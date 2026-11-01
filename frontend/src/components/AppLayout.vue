<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  DataBoard, Calendar, DocumentChecked, User, Monitor, Setting,
  Timer, List, ArrowRight, Menu, SwitchButton, Lock, Camera, ArrowDown, Fold, Expand, Location,
} from '@element-plus/icons-vue'
import { useAuth } from '../stores/auth'
import { ElMessage } from 'element-plus'
import { errorText } from '../api'

const auth = useAuth()
const route = useRoute()
const router = useRouter()
const open = ref(false)
const collapsed = ref(false)
const accountMenu = ref<{ handleClose: () => void } | null>(null)
function closeMenus() { open.value = false; accountMenu.value?.handleClose() }
const now = ref(new Date())
const dateLabel = computed(() => new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', month: 'long', day: 'numeric', weekday: 'long',
}).format(now.value))
let dateTimer: ReturnType<typeof setInterval> | undefined
onMounted(() => { dateTimer = setInterval(() => { now.value = new Date() }, 60000) })
onBeforeUnmount(() => { if (dateTimer) clearInterval(dateTimer) })
const nav = [
  { path: '/', title: '考勤概览', icon: DataBoard },
  { path: '/attendance', title: '考勤记录', icon: Calendar },
  { path: '/requests', title: '请假与补卡', icon: DocumentChecked },
]
const adminNav = [
  { path: '/employees', title: '人员与部门', icon: User },
  { path: '/devices', title: '打卡设备', icon: Monitor },
  { path: '/rules', title: '规则与日历', icon: Timer },
  { path: '/attendance-policy', title: '考勤方式与地点', icon: Location },
  { path: '/diagnostics', title: '识别诊断', icon: Camera },
  { path: '/settings', title: '系统运行', icon: Setting },
  { path: '/audit', title: '操作日志', icon: List },
]

async function logout() {
  try {
    await auth.logout()
    await router.push('/login')
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}
</script>

<template>
  <div class="app" :class="{ 'is-collapsed': collapsed }" @keydown.esc="closeMenus">
    <button v-if="open" class="scrim visible" aria-label="关闭导航" @click="open = false" />
    <aside id="app-navigation" class="sidebar" :class="{ open }">
      <div class="sidebar-brand-row">
        <router-link class="brand" to="/" title="知行 · 考勤管理" @click="open = false">
          <span class="brand-mark" aria-hidden="true">
            <svg viewBox="0 0 32 32" fill="none"><path d="M8 8h16L8 24h16M8 16h16" stroke="currentColor" stroke-width="2.4" stroke-linecap="square" /></svg>
          </span>
          <div class="brand-copy">知行<small>考勤管理</small></div>
        </router-link>
        <button class="sidebar-toggle" :aria-label="collapsed ? '展开侧栏' : '收起侧栏'" :title="collapsed ? '展开侧栏' : '收起侧栏'" @click="collapsed = !collapsed">
          <el-icon><Expand v-if="collapsed" /><Fold v-else /></el-icon>
        </button>
      </div>

      <nav class="nav-group" aria-label="业务导航">
        <div class="nav-label">工作台</div>
        <router-link
          v-for="item in nav"
          :key="item.path"
          :to="item.path"
          :title="item.title"
          class="nav-item"
          @click="open = false"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
        </router-link>
      </nav>

      <nav v-if="auth.isAdmin" class="nav-group" aria-label="管理导航">
        <div class="nav-label">组织管理</div>
        <router-link
          v-for="item in adminNav"
          :key="item.path"
          :to="item.path"
          :title="item.title"
          class="nav-item"
          @click="open = false"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
        </router-link>
      </nav>

      <div class="sidebar-footer">
        <router-link to="/kiosk" class="sidebar-kiosk" title="刷脸打卡">
          <span class="sidebar-kiosk-icon"><el-icon><Camera /></el-icon></span>
          <span class="sidebar-kiosk-copy"><strong>刷脸打卡</strong><small>打开公共打卡终端</small></span>
          <el-icon class="sidebar-kiosk-arrow"><ArrowRight /></el-icon>
        </router-link>
        <div class="sidebar-account">
          <el-dropdown ref="accountMenu" trigger="click" placement="top-start">
            <button class="profile" aria-label="账号菜单">
              <span class="avatar">{{ auth.user?.name?.slice(0, 1) || '用' }}</span>
              <span class="profile-text">
                {{ auth.user?.name || auth.user?.username }}
                <small>{{ auth.isAdmin ? '管理员' : '员工' }}</small>
              </span>
              <el-icon class="profile-chevron"><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item :icon="Lock" @click="router.push('/password')">修改密码</el-dropdown-item>
                <el-dropdown-item :icon="SwitchButton" @click="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
    </aside>

    <main class="main">
      <header class="topbar">
        <el-button
          class="menu-button"
          :icon="Menu"
          text
          aria-label="展开导航"
          aria-controls="app-navigation"
          :aria-expanded="open"
          @click="open = !open"
        />
        <div class="breadcrumb" aria-label="当前位置">
          <span>{{ route.meta.admin ? '组织管理' : '工作台' }}</span>
          <el-icon><ArrowRight /></el-icon>
          <span>{{ route.meta.title }}</span>
        </div>
        <div class="topbar-actions">
          <span class="topbar-date"><el-icon><Calendar /></el-icon>{{ dateLabel }}<small>北京</small></span>
          <router-link to="/kiosk">
            <el-button :icon="Camera" class="topbar-kiosk">刷脸打卡</el-button>
          </router-link>
        </div>
      </header>
      <section class="content"><router-view /></section>
    </main>
  </div>
</template>
