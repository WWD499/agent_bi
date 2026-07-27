import axios from 'axios'
import { ElMessage } from 'element-plus'

// 统一请求实例：自动带 Sa-Token 的 token（发 `satoken` 头，不加 Bearer 前缀），
// 与后端 sa-token.token-name=satoken 对齐；401 自动清登录态并跳登录页。
const http = axios.create({
  baseURL: '/api',
  // 全局默认 30s。大模型相关调用（抽取/向量化/对话）在各自 api 内单独放宽到 120s。
  timeout: 30000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('bi_token')
  if (token) {
    config.headers['satoken'] = token
  }
  return config
})

http.interceptors.response.use(
  (resp) => resp,
  (err) => {
    const status = err.response?.status
    if (status === 401) {
      localStorage.removeItem('bi_token')
      localStorage.removeItem('bi_username')
      if (location.pathname !== '/login') location.href = '/login'
    }
    const msg = err.response?.data?.msg || err.message || '请求失败'
    ElMessage.error(msg)
    return Promise.reject(err)
  }
)

export default http
