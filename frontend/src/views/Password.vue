<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '../stores/auth'
import { errorText } from '../api'
import { ElMessage } from 'element-plus'
const auth = useAuth(), router = useRouter(), oldPassword = ref(''), newPassword = ref(''), confirm = ref(''), loading = ref(false), error = ref('')
async function submit() { error.value=''; if (newPassword.value.length<12 || newPassword.value===oldPassword.value) { error.value='新密码至少12位，且不能与原密码相同'; return }; if (newPassword.value!==confirm.value) { error.value='两次输入的新密码不一致'; return }; loading.value=true; try { await auth.password(oldPassword.value,newPassword.value); oldPassword.value=''; newPassword.value=''; confirm.value=''; ElMessage.success('密码修改成功'); router.replace('/') } catch(e) { error.value=errorText(e) } finally { loading.value=false } }
</script>
<template><div class="page-head"><div><h1>修改密码</h1><p>新密码至少 12 位，且与当前密码不同。</p></div></div><el-alert v-if="auth.user?.mustChangePassword" title="首次登录，请修改初始密码。" type="warning" show-icon :closable="false"/><div class="panel" style="max-width:560px"><el-alert v-if="error" :title="error" type="error" :closable="false"/><el-form label-position="top" @submit.prevent="submit"><el-form-item label="当前密码"><el-input v-model="oldPassword" type="password" show-password autocomplete="current-password"/></el-form-item><el-form-item label="新密码"><el-input v-model="newPassword" type="password" show-password autocomplete="new-password"/></el-form-item><el-form-item label="确认新密码"><el-input v-model="confirm" type="password" show-password autocomplete="new-password"/></el-form-item><el-button type="primary" native-type="submit" :loading="loading">保存新密码</el-button></el-form></div></template>
