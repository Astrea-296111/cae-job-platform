import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, setAuthorization } from '../api/client'

export const useSessionStore = defineStore('session', () => {
  const username = ref<string | null>(null)
  const roles = ref<string[]>([])
  async function login(name: string, password: string) {
    const bytes = new TextEncoder().encode(`${name}:${password}`)
    setAuthorization('Basic ' + btoa(String.fromCharCode(...bytes)))
    try { const response = await api.get<{username: string; roles: string[]}>('/auth/me'); username.value = response.data.username; roles.value = response.data.roles }
    catch (error) { logout(); throw error }
  }
  function logout() { username.value = null; roles.value = []; setAuthorization(''); window.dispatchEvent(new Event('cae-logout')) }
  const admin = () => roles.value.includes('ADMIN')
  const lead = () => admin() || roles.value.includes('PROJECT_LEAD')
  return { username, roles, login, logout, admin, lead }
})
