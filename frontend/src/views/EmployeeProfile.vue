<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Lock, SwitchButton, User } from '@element-plus/icons-vue'
import { api, errorText } from '../api'
import { useAuth } from '../stores/auth'
import { useLoad } from '../composables'

interface EmployeeProfile {
  id: number; employeeNo: string; name: string; departmentName: string; startDate: string
  endDate: string | null; active: boolean; faceEnrolled: boolean; username: string; role: string
}
const auth = useAuth(), router = useRouter()
const profile = ref<EmployeeProfile | null>(null), loggingOut = ref(false), accountError = ref('')
const { loading, error, load } = useLoad(async () => {
  if (auth.user?.employeeId) profile.value = await api<EmployeeProfile>('/employee/profile')
})
async function logout() {
  if (loggingOut.value) return
  loggingOut.value = true; accountError.value = ''
  try { await auth.logout(); await router.replace('/login') }
  catch (e) { accountError.value = errorText(e) }
  finally { loggingOut.value = false }
}
</script>

<template>
  <div class="employee-profile">
    <header class="profile-heading"><h1>我的资料</h1><router-link to="/employee">返回工作台 <el-icon><ArrowRight /></el-icon></router-link></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <section class="profile-card" v-loading="loading">
      <div class="profile-person"><span class="profile-avatar">{{ profile?.name?.slice(0,1) || auth.user?.name?.slice(0,1) || '我' }}</span><div><h2>{{ profile?.name || auth.user?.name || auth.user?.username }}</h2><p>{{ profile ? '工号 ' + profile.employeeNo : '账号 ' + (auth.user?.username || '') }}</p></div><span v-if="profile" class="profile-state" :class="{ inactive: !profile.active }">{{ profile.active ? '在职' : '已停用' }}</span></div>
      <template v-if="profile">
        <dl class="profile-details"><div><dt>所属部门</dt><dd>{{ profile.departmentName }}</dd></div><div><dt>入职日期</dt><dd>{{ profile.startDate }}</dd></div><div v-if="profile.endDate"><dt>停用日期</dt><dd>{{ profile.endDate }}</dd></div><div><dt>人脸录入</dt><dd><span class="face-state" :class="{ enrolled: profile.faceEnrolled }">{{ profile.faceEnrolled ? '已录入' : '尚未录入' }}</span></dd></div><div><dt>登录账号</dt><dd>{{ profile.username }}</dd></div></dl>
        <p class="profile-note">人员资料由管理员维护。如需变更或重新录入人脸，请联系管理员。</p>
      </template>
      <div v-else-if="!auth.user?.employeeId" class="unlinked-account"><el-icon><User /></el-icon><div><h3>尚未关联员工档案</h3><p>请联系管理员完成关联，之后即可查看个人考勤并打卡。</p></div></div>
      <el-button v-else-if="!loading" class="profile-retry" @click="load">重新加载资料</el-button>
    </section>

    <section class="account-section"><h2>账号管理</h2><router-link to="/employee/password" class="account-action"><span class="account-action-icon"><el-icon><Lock /></el-icon></span><div><strong>修改密码</strong><small>更新当前账号的登录密码</small></div><el-icon><ArrowRight /></el-icon></router-link><el-alert v-if="accountError" :title="accountError" type="error" :closable="false" /><el-button class="logout-button" :icon="SwitchButton" :loading="loggingOut" @click="logout">退出登录</el-button></section>
  </div>
</template>

<style scoped>
.employee-profile { max-width: 760px; margin: 0 auto; color: #2f3f34; }
.profile-heading { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 27px; }
.profile-heading h1 { margin: 0; font-size: 28px; letter-spacing: -.8px; }
.profile-heading > a { display: flex; align-items: center; gap: 8px; font-size: 11px; color: #5b7450; }
.profile-card { border: 1px solid #e0e6d9; border-radius: 14px; background: #fffefa; overflow: hidden; min-height: 170px; }
.profile-person { display: flex; align-items: center; gap: 16px; padding: 29px; background: #f0f5e9; border-bottom: 1px solid #e2e9da; }
.profile-avatar { width: 58px; height: 60px; display: grid; place-items: center; border: 1px solid #d4e0c8; background: #e3ecd9; color: #577247; border-radius: 17px; font-size: 23px; flex-shrink: 0; }
.profile-person > div { flex: 1; min-width: 0; }
.profile-person h2 { margin: 0; font-size: 20px; font-weight: 500; overflow-wrap: anywhere; }
.profile-person p { font-size: 11px; color: #687d5b; margin: 7px 0 0; overflow-wrap: anywhere; }
.profile-state { font-size: 11px; color: #50733f; padding: 5px 9px; border-radius: 5px; background: #e2ecd7; flex-shrink: 0; }
.profile-state.inactive { background: #e9ebe5; color: #707765; }
.profile-details { margin: 0; padding: 7px 29px; }
.profile-details > div { display: flex; align-items: flex-start; justify-content: space-between; gap: 22px; padding: 20px 0; border-bottom: 1px solid #e9eddf; }
.profile-details > div:last-child { border: 0; }
.profile-details dt { font-size: 12px; color: #6b7c61; flex-shrink: 0; }
.profile-details dd { margin: 0; font-size: 13px; text-align: right; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
.face-state { color: #8a7149; font-size: 12px; }
.face-state.enrolled { color: #5c7849; }
.profile-note { border-top: 1px solid #e9eddf; margin: 0; padding: 16px 29px; font-size: 11px; line-height: 1.8; color: #6b7c61; background: #fafcf6; }
.account-section { margin-top: 30px; }
.account-section h2 { font-size: 14px; margin-bottom: 15px; }
.account-action { display: flex; align-items: center; gap: 14px; padding: 21px; border: 1px solid #e0e6d9; border-radius: 10px; background: #fffefa; }
.account-action-icon { display: grid; place-items: center; width: 38px; height: 40px; border-radius: 11px; background: #edf3e5; color: #5c7849; font-size: 19px; }
.account-action > div { flex: 1; }
.account-action strong { font-size: 13px; font-weight: 500; }
.account-action small { display: block; font-size: 11px; color: #6b7c61; margin-top: 5px; }
.account-action > .el-icon { font-size: 12px; color: #6b7c61; }
.logout-button { margin-top: 20px; width: 100%; height: 43px; background: transparent; border-color: #dce3d5; color: #6b7c61; }
.unlinked-account { display: flex; align-items: flex-start; gap: 12px; padding: 24px 29px; }
.unlinked-account > .el-icon { font-size: 20px; color: #6b7c61; }
.unlinked-account h3 { margin: 0 0 8px; font-size: 14px; font-weight: 500; }
.unlinked-account p { margin: 0; font-size: 12px; color: #6b7c61; line-height: 1.8; }
.profile-retry { margin: 24px 29px; }
@media (max-width: 600px) { .profile-heading h1 { font-size: 25px; } .profile-person { padding: 23px 20px; gap: 13px; } .profile-avatar { width: 49px; height: 52px; font-size: 20px; } .profile-person h2 { font-size: 18px; } .profile-details { padding: 5px 20px; } .profile-note { padding: 15px 20px; } .unlinked-account { padding: 23px 20px; } }
</style>
