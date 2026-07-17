<template>
  <el-drawer
    :model-value="modelValue"
    title="历史记录"
    direction="ltr"
    size="360px"
    @update:model-value="(v) => emit('update:modelValue', v)"
    @open="load"
  >
    <div class="hd-toolbar">
      <el-button size="small" :icon="Refresh" @click="load">刷新</el-button>
      <el-button size="small" type="danger" :icon="Delete" @click="onClearAll">清空全部</el-button>
    </div>

    <div v-if="loading" class="hd-tip">加载中…</div>
    <div v-else-if="!list.length" class="hd-tip">暂无历史会话</div>

    <div v-else class="hd-list">
      <div
        v-for="item in list"
        :key="item.sessionId"
        class="hd-card"
        :class="{ active: item.sessionId === activeSid }"
        @click="onOpen(item)"
      >
        <div class="hd-card-title" :title="item.title">{{ item.title }}</div>
        <div class="hd-card-preview">{{ item.preview || '（无预览）' }}</div>
        <div class="hd-card-meta">
          <span class="hd-time">{{ formatTime(item.lastActiveTime) }}</span>
          <span class="hd-count">{{ item.messageCount }} 条</span>
          <el-button
            class="hd-del"
            size="small"
            text
            type="danger"
            :icon="Close"
            title="删除"
            @click.stop="onDelete(item.sessionId)"
          />
        </div>
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
import { ref, computed } from 'vue'
import { Refresh, Delete, Close } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listHistory, getHistory, deleteHistory, clearHistory } from '@/api/agent'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  activeSid: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue', 'open-session'])

const list = ref([])
const loading = ref(false)

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

async function load() {
  loading.value = true
  try {
    const data = await listHistory(0, 50)
    list.value = (data && data.list) || []
  } catch (e) {
    list.value = []
  } finally {
    loading.value = false
  }
}

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(Number(ts))
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function onOpen(item) {
  try {
    const detail = await getHistory(item.sessionId)
    emit('open-session', {
      sessionId: item.sessionId,
      messages: (detail && detail.messages) || []
    })
    visible.value = false
  } catch (e) {
    ElMessage.error('加载会话失败')
  }
}

async function onDelete(sid) {
  try {
    await ElMessageBox.confirm('确定删除该会话？', '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteHistory(sid)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error('删除失败')
  }
}

async function onClearAll() {
  try {
    await ElMessageBox.confirm('确定清空全部历史会话？此操作不可恢复。', '提示', {
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await clearHistory()
    ElMessage.success('已清空')
    load()
  } catch (e) {
    ElMessage.error('清空失败')
  }
}
</script>

<style scoped>
.hd-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.hd-tip {
  color: var(--text-dim);
  font-size: 13px;
  padding: 16px 4px;
}
.hd-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.hd-card {
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 10px;
  padding: 10px 12px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.hd-card:hover {
  border-color: var(--el-color-primary, #409eff);
}
.hd-card.active {
  background: var(--el-color-primary-light-9, #ecf5ff);
  border-color: var(--el-color-primary, #409eff);
}
.hd-card-title {
  font-size: 14px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hd-card-preview {
  font-size: 12px;
  color: var(--text-dim);
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hd-card-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
  font-size: 11px;
  color: var(--text-dim);
}
.hd-time {
  flex: 1;
}
.hd-count {
  white-space: nowrap;
}
.hd-del {
  margin-left: auto;
}
</style>
