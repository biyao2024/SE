import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from './stores/auth'
export const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/login', component: () => import('./views/Login.vue'), meta: { public: true } },
  { path: '/kiosk', component: () => import('./views/Kiosk.vue'), meta: { public: true } },
  { path: '/', component: () => import('./components/AppLayout.vue'), children: [
    { path: '', component: () => import('./views/Dashboard.vue'), meta: { title: '考勤概览' } },
    { path: 'attendance', component: () => import('./views/Attendance.vue'), meta: { title: '考勤记录' } },
    { path: 'requests', component: () => import('./views/Requests.vue'), meta: { title: '请假与补卡' } },
    { path: 'employees', component: () => import('./views/Employees.vue'), meta: { title: '人员与部门', admin: true } },
    { path: 'devices', component: () => import('./views/Devices.vue'), meta: { title: '打卡设备', admin: true } },
    { path: 'rules', component: () => import('./views/Rules.vue'), meta: { title: '规则与日历', admin: true } },
    { path: 'settings', component: () => import('./views/Settings.vue'), meta: { title: '系统运行', admin: true } },
    { path: 'audit', component: () => import('./views/Audit.vue'), meta: { title: '操作日志', admin: true } },
    { path: 'password', component: () => import('./views/Password.vue'), meta: { title: '修改密码' } }
  ] },
  { path: '/:pathMatch(.*)*', redirect: '/' }
] })
router.beforeEach(async to => {
  const auth = useAuth()
  if (to.meta.public) return true
  if (!auth.checked) { try { await auth.restore() } catch { return { path: '/login', query: { reason: 'unavailable' } } } }
  if (!auth.user) return { path: '/login', query: { redirect: to.fullPath } }
  if (auth.user.mustChangePassword && to.path !== '/password') return '/password'
  if (to.meta.admin && !auth.isAdmin) return '/'
})
window.addEventListener('session-expired', () => { const auth = useAuth(); auth.user = null; router.replace({ path: '/login', query: { reason: 'expired' } }) })
