<template>
  <router-view />
</template>

<script setup>
import { onMounted } from 'vue'

// 应用启动时恢复主题（默认 light）
onMounted(() => {
  const t = localStorage.getItem('bi_theme') || 'light'
  document.documentElement.dataset.theme = t
})
</script>
