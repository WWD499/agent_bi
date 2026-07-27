import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 开发态把 /api 代理到本地后端（:8080），免 CORS 烦恼。
// 生产态由后端 CorsConfig 放行，或反向代理统一收口。
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    // 把体积较大的第三方库拆成独立 chunk，提升浏览器缓存命中率，
    // 并抬高单 chunk 体积告警阈值（echarts 全量本身约 1MB，属正常范围）。
    chunkSizeWarningLimit: 2000,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules')) {
            if (id.includes('echarts') || id.includes('zrender')) return 'echarts'
            if (id.includes('grid-layout-plus')) return 'grid-layout'
            if (id.includes('element-plus') || id.includes('@element-plus')) return 'element-plus'
            if (id.includes('vue') || id.includes('@vue') || id.includes('vue-router') || id.includes('pinia')) return 'vue'
            return 'vendor'
          }
        }
      }
    }
  }
})
