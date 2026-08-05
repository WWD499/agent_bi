<template>
  <div class="page">
    <div class="card">
      <div class="toolbar">
        <div class="title">站内信 / 通知中心</div>
        <el-badge :value="unread" :hidden="unread === 0" type="danger" :max="99">
          <span class="unread-label">未读 {{ unread }}</span>
        </el-badge>
        <div class="spacer" />
        <el-switch v-model="onlyUnread" active-text="仅看未读" @change="load" />
        <el-button @click="markAll">全部已读</el-button>
        <el-button :disabled="!selected.length" type="danger" @click="batchDelete">
          批量删除
        </el-button>
      </div>

      <el-table :data="rows" v-loading="loading" @selection-change="onSelect" border stripe>
        <el-table-column type="selection" width="44" />
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.isRead === 1 ? 'info' : 'warning'" size="small">
              {{ row.isRead === 1 ? '已读' : '未读' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
        <el-table-column label="级别" width="92" align="center">
          <template #default="{ row }">
            <el-tag :type="levelType(row.level)" size="small">{{ row.level || 'info' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="时间" width="172" />
        <el-table-column label="操作" width="172" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="open(row)">查看</el-button>
            <el-button v-if="row.isRead === 0" link type="success" @click="read(row)">标记已读</el-button>
            <el-button link type="danger" @click="del(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="detailVisible" title="通知详情" width="640px">
      <template v-if="detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="标题">{{ detail.title }}</el-descriptions-item>
          <el-descriptions-item label="级别">{{ detail.level }}</el-descriptions-item>
          <el-descriptions-item label="时间">{{ detail.createTime }}</el-descriptions-item>
        </el-descriptions>
        <div class="block-label">内容</div>
        <pre class="content">{{ detail.content }}</pre>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button
          v-if="detail && detail.isRead === 0"
          type="primary"
          @click="read(detail)"
        >
          标记为已读
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listNotifications,
  unreadCount,
  markRead,
  markAllRead,
  deleteNotification,
  deleteNotifications
} from '@/api/notification'

const rows = ref([])
const loading = ref(false)
const unread = ref(0)
const onlyUnread = ref(false)
const selected = ref([])
const detailVisible = ref(false)
const detail = ref(null)

async function load() {
  loading.value = true
  try {
    rows.value = await listNotifications({
      unreadOnly: onlyUnread.value,
      page: 1,
      size: 50
    })
    unread.value = await unreadCount()
  } catch (e) {
    ElMessage.error('加载通知失败')
  } finally {
    loading.value = false
  }
}

function onSelect(val) {
  selected.value = val
}

function levelType(l) {
  if (l === 'critical') return 'danger'
  if (l === 'warning') return 'warning'
  return 'info'
}

async function open(row) {
  detail.value = row
  detailVisible.value = true
  if (row.isRead === 0) await read(row)
}

async function read(row) {
  await markRead(row.id)
  row.isRead = 1
  unread.value = Math.max(0, unread.value - 1)
}

async function markAll() {
  await markAllRead()
  ElMessage.success('已全部标记为已读')
  load()
}

async function del(row) {
  await ElMessageBox.confirm('确认删除该通知？', '提示', { type: 'warning' })
  await deleteNotification(row.id)
  ElMessage.success('已删除')
  load()
}

async function batchDelete() {
  const ids = selected.value.map((r) => r.id)
  await ElMessageBox.confirm(`确认删除选中的 ${ids.length} 条通知？`, '提示', { type: 'warning' })
  await deleteNotifications(ids)
  ElMessage.success('已删除')
  load()
}

onMounted(load)
</script>

<style scoped>
.page {
  padding: 16px;
}
.card {
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color);
  border-radius: 16px;
  padding: 16px;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}
.title {
  font-size: 18px;
  font-weight: 700;
}
.spacer {
  flex: 1;
}
.unread-label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.block-label {
  margin: 14px 0 6px;
  font-weight: 600;
}
.content {
  background: var(--el-fill-color-light);
  border-radius: 10px;
  padding: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 13px;
  max-height: 320px;
  overflow: auto;
}
</style>
