import { createRouter, createWebHistory } from 'vue-router'
import LoginView from '@/views/LoginView.vue'
import MainLayout from '@/layout/MainLayout.vue'
import ChatView from '@/views/ChatView.vue'
import QueryView from '@/views/QueryView.vue'
import AlertView from '@/views/AlertView.vue'
import KnowledgeView from '@/views/KnowledgeView.vue'
import DatabaseView from '@/views/DatabaseView.vue'
import DashboardView from '@/views/DashboardView.vue'
import OcrView from '@/views/OcrView.vue'
import SandboxView from '@/views/SandboxView.vue'

const routes = [
  { path: '/login', name: 'login', component: LoginView },
  // 公开分享页：免登录、独立渲染（不经 MainLayout 侧栏），靠 ?token= 读取已公开大屏
  { path: '/share', name: 'share', component: DashboardView, meta: { requiresAuth: false } },
  {
    path: '/',
    component: MainLayout,
    meta: { requiresAuth: true },
    children: [
      { path: '', redirect: { name: 'chat' } },
      { path: 'chat', name: 'chat', component: ChatView, meta: { requiresAuth: true } },
      { path: 'query', name: 'query', component: QueryView, meta: { requiresAuth: true } },
      { path: 'alert', name: 'alert', component: AlertView, meta: { requiresAuth: true } },
      { path: 'knowledge', name: 'knowledge', component: KnowledgeView, meta: { requiresAuth: true } },
      { path: 'database', name: 'database', component: DatabaseView, meta: { requiresAuth: true } },
      { path: 'dashboard', name: 'dashboard', component: DashboardView, meta: { requiresAuth: true } },
      { path: 'ocr', name: 'ocr', component: OcrView, meta: { requiresAuth: true } },
      { path: 'sandbox', name: 'sandbox', component: SandboxView, meta: { requiresAuth: true } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫：未登录访问受保护页跳登录；已登录访问 /login 进对话页
router.beforeEach((to) => {
  const token = localStorage.getItem('bi_token')
  if (to.meta.requiresAuth && !token) return { name: 'login' }
  if (to.name === 'login' && token) return { name: 'chat' }
  return true
})

export default router
