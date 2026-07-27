<template>
  <div class="login-page">
    <div class="login-card glass">
      <div class="lc-brand">
        <span class="logo">◆</span>
        <div>
          <div class="lc-title">BI Agent</div>
          <div class="lc-sub">智能数据分析平台 · 登录</div>
        </div>
      </div>
      <el-form label-position="top" @submit.prevent="onSubmit">
        <el-form-item label="用户名">
          <el-input v-model="username" placeholder="任意非空用户名（Phase0 演示，无真实校验）" :prefix-icon="User" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="password" type="password" show-password placeholder="任意非空" :prefix-icon="Lock" />
        </el-form-item>
        <el-button type="primary" :loading="loading" native-type="submit" class="lc-btn">登 录</el-button>
      </el-form>
      <div class="lc-tip">后端为 Sa-Token 最简登录：用户名/密码填任意非空值即可获取 token。</div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { User, Lock } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { login } from '@/api/agent'
import { useAuthStore } from '@/store/auth'

const router = useRouter()
const auth = useAuthStore()
const username = ref('')
const password = ref('')
const loading = ref(false)

async function onSubmit() {
  if (!username.value.trim() || !password.value.trim()) {
    ElMessage.warning('用户名和密码不能为空')
    return
  }
  loading.value = true
  try {
    const vo = await login(username.value.trim(), password.value.trim())
    auth.setAuth(vo.token, vo.username)
    ElMessage.success('登录成功')
    router.push('/')
  } catch (e) {
    // 错误已由 http 拦截器统一提示
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}
.login-card {
  width: 380px;
  max-width: 92vw;
  padding: 30px 28px;
}
.lc-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 22px;
}
.logo {
  font-size: 26px;
  color: var(--primary);
}
.lc-title {
  font-size: 20px;
  font-weight: 700;
}
.lc-sub {
  font-size: 12.5px;
  color: var(--text-dim);
}
.lc-btn {
  width: 100%;
  margin-top: 6px;
}
.lc-tip {
  margin-top: 14px;
  font-size: 12px;
  color: var(--text-dim);
  line-height: 1.5;
}
</style>
