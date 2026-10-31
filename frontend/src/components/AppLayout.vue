<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  DataBoard, Calendar, DocumentChecked, User, Monitor, Setting,
  Timer, List, ArrowRight, Menu, SwitchButton, Lock, Camera, ArrowDown,
} from '@element-plus/icons-vue'
import { useAuth } from '../stores/auth'
import { ElMessage } from 'element-plus'
import { errorText } from '../api'

const auth = useAuth()
const route = useRoute()
const router = useRouter()
const open = ref(false)
const nav = [
  { path: '/', title: '考勤概览', icon: DataBoard },
  { path: '/attendance', title: '考勤记录', icon: Calendar },
  { path: '/requests', title: '请假与补卡', icon: DocumentChecked },
]
const adminNav = [
  { path: '/employees', title: '人员与部门', icon: User },
  { path: '/devices', title: '打卡设备', icon: Monitor },
  { path: '/rules', title: '规则与日历', icon: Timer },
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
  <div class="app" @keydown.esc="open = false">
    <button v-if="open" class="scrim visible" aria-label="关闭导航" @click="open = false" />
    <aside id="app-navigation" class="sidebar" :class="{ open }">
      <router-link class="brand" to="/" @click="open = false">
        <span class="brand-mark">知</span>
        <div>知行<small>考勤管理</small></div>
      </router-link>

      <nav class="nav-group" aria-label="业务导航">
        <div class="nav-label">工作台</div>
        <router-link
          v-for="item in nav"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          @click="open = false"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
        </router-link>
      </nav>

      <nav v-if="auth.isAdmin" class="nav-group" aria-label="管理导航">
        <div class="nav-label">管理</div>
        <router-link
          v-for="item in adminNav"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          @click="open = false"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
        </router-link>
      </nav>

      <div class="sidebar-footer">北京时间 · UTC+8</div>
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
          <span>知行</span>
          <el-icon><ArrowRight /></el-icon>
          <span>{{ route.meta.title }}</span>
        </div>
        <div class="topbar-actions">
          <router-link to="/kiosk">
            <el-button :icon="Camera" plain>刷脸打卡</el-button>
          </router-link>
          <el-dropdown trigger="click">
            <button class="profile" aria-label="账号菜单">
              <span class="avatar">{{ auth.user?.name?.slice(0, 1) || '用' }}</span>
              <span class="profile-text">
                {{ auth.user?.name || auth.user?.username }}
                <small>{{ auth.isAdmin ? '管理员' : '员工' }}</small>
              </span>
              <el-icon><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item :icon="Lock" @click="router.push('/password')">修改密码</el-dropdown-item>
                <el-dropdown-item :icon="SwitchButton" @click="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>
      <section class="content"><router-view /></section>
    </main>
  </div>
</template>
