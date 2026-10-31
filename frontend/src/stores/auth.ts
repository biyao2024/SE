import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { api, post, put, refreshCsrf, type User } from '../api'
export const useAuth = defineStore('auth', () => {
  const user = ref<User | null>(null); const checked = ref(false)
  const isAdmin = computed(() => user.value?.role === 'ADMIN')
  async function restore() { try { user.value = await api<User>('/auth/me') } catch (e: any) { if (e.status !== 401) throw e; user.value = null } finally { checked.value = true } }
  async function login(username: string, password: string) { await refreshCsrf(); user.value = await post<User>('/auth/login', { username, password }); checked.value = true; await refreshCsrf() }
  async function logout() { await post('/auth/logout'); user.value = null; await refreshCsrf() }
  async function password(oldPassword: string, newPassword: string) { await put('/auth/password', { oldPassword, newPassword }); if (user.value) user.value.mustChangePassword = false }
  return { user, checked, isAdmin, restore, login, logout, password }
})
