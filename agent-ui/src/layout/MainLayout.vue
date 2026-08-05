<template>
  <div class="layout">
    <aside class="sidebar glass">
      <div class="brand">
        <span class="logo">◆</span>
        <div>
          <div class="title">BI Agent</div>
          <div class="sub">智能数据分析平台</div>
        </div>
      </div>

      <el-menu class="menu" router :default-active="activeMenu">
        <el-menu-item index="/chat">
          <el-icon><ChatDotRound /></el-icon>
          <span>智能对话</span>
        </el-menu-item>
        <el-menu-item index="/query">
          <el-icon><DataAnalysis /></el-icon>
          <span>NL2SQL 查询</span>
        </el-menu-item>
        <el-menu-item index="/query-history">
          <el-icon><Clock /></el-icon>
          <span>查询历史</span>
        </el-menu-item>
        <el-menu-item index="/alert">
          <el-icon><Warning /></el-icon>
          <span>预警中心</span>
        </el-menu-item>
        <el-menu-item index="/notification">
          <el-icon><Bell /></el-icon>
          <span>通知中心</span>
        </el-menu-item>
        <el-menu-item index="/knowledge">
          <el-icon><Collection /></el-icon>
          <span>知识库</span>
        </el-menu-item>
        <el-menu-item index="/database">
          <el-icon><Coin /></el-icon>
          <span>数据库管理</span>
        </el-menu-item>
        <el-menu-item index="/dashboard">
          <el-icon><Monitor /></el-icon>
          <span>BI 大屏</span>
        </el-menu-item>
        <el-menu-item index="/ocr">
          <el-icon><Picture /></el-icon>
          <span>OCR 识别</span>
        </el-menu-item>
        <el-menu-item index="/sandbox">
          <el-icon><Box /></el-icon>
          <span>数据沙箱</span>
        </el-menu-item>
      </el-menu>

      <div class="user">
        <el-avatar class="ava">{{ initial }}</el-avatar>
        <div class="uinfo">
          <div class="uname">{{ auth.username || '用户' }}</div>
        </div>
        <el-badge :value="unread" :hidden="unread === 0" :max="99">
          <el-button text :icon="Bell" @click="goNotify" title="通知中心" />
        </el-badge>
        <el-button text :icon="theme === 'dark' ? Sunny : Moon" @click="toggleTheme" title="切换主题" />
        <el-button text :icon="SwitchButton" @click="doLogout" title="退出登录" />
      </div>
    </aside>

    <main class="content">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChatDotRound, DataAnalysis, Warning, Collection, Coin, Monitor, Sunny, Moon, SwitchButton, Picture, Box, Bell, Clock } from '@element-plus/icons-vue'
import { useAuthStore } from '@/store/auth'
import { unreadCount } from '@/api/notification'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const activeMenu = computed(() => route.path)
const initial = computed(() => (auth.username || 'U').charAt(0).toUpperCase())
const theme = ref(localStorage.getItem('bi_theme') || 'light')
const unread = ref(0)

async function refreshUnread() {
  try {
    unread.value = await unreadCount()
  } catch (e) {
    unread.value = 0
  }
}
function goNotify() {
  router.push('/notification')
}

function toggleTheme() {
  theme.value = theme.value === 'dark' ? 'light' : 'dark'
  document.documentElement.dataset.theme = theme.value
  localStorage.setItem('bi_theme', theme.value)
}
function doLogout() {
  auth.logout()
  router.push('/login')
}

onMounted(refreshUnread)
// 路由切换（含从通知中心返回）时刷新未读角标
watch(() => route.fullPath, refreshUnread)
</script>

<style scoped>
.layout {
  height: 100vh;
  display: flex;
  gap: 14px;
  padding: 14px;
}
.sidebar {
  width: 224px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  padding: 18px 14px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 4px 6px 16px;
  border-bottom: 1px solid var(--border);
}
.logo {
  font-size: 24px;
  color: var(--primary);
}
.title {
  font-size: 17px;
  font-weight: 700;
}
.sub {
  font-size: 12px;
  color: var(--text-dim);
}
.menu {
  flex: 1;
  margin-top: 12px;
  border-right: none;
  background: transparent;
}
.menu :deep(.el-menu-item) {
  border-radius: 10px;
  margin: 4px 0;
  height: 44px;
}
.menu :deep(.el-menu-item.is-active) {
  background: var(--primary-soft);
  color: var(--primary);
  font-weight: 600;
}
.user {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 6px 2px;
  border-top: 1px solid var(--border);
}
.ava {
  background: var(--primary);
  color: #fff;
}
.uinfo {
  flex: 1;
  min-width: 0;
}
.uname {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.content {
  flex: 1;
  min-width: 0;
  overflow: auto;
}
</style>
