import { defineStore } from 'pinia'

// 极简鉴权状态：仅持有 token / username，持久化到 localStorage。
// 后端用 Sa-Token，登录后返回 token，后续请求头携带即可。
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('bi_token') || '',
    username: localStorage.getItem('bi_username') || ''
  }),
  getters: {
    isLoggedIn: (s) => !!s.token
  },
  actions: {
    setAuth(token, username) {
      this.token = token
      this.username = username
      localStorage.setItem('bi_token', token)
      localStorage.setItem('bi_username', username)
    },
    logout() {
      this.token = ''
      this.username = ''
      localStorage.removeItem('bi_token')
      localStorage.removeItem('bi_username')
    }
  }
})
