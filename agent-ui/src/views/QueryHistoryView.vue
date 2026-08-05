<template>
  <div class="page">
    <div class="card">
      <div class="toolbar">
        <div class="title">查询历史</div>
        <div class="spacer" />
        <el-input
          v-model="keyword"
          placeholder="搜索问题 / SQL"
          clearable
          style="width: 240px"
          @keyup.enter="load"
          @clear="load"
        />
        <el-button type="primary" @click="load">查询</el-button>
        <el-button :disabled="!selected.length" type="danger" @click="batchDelete">
          批量删除
        </el-button>
      </div>

      <el-table :data="rows" v-loading="loading" @selection-change="onSelect" border stripe>
        <el-table-column type="selection" width="44" />
        <el-table-column prop="query" label="自然语言问题" min-width="200" show-overflow-tooltip />
        <el-table-column label="生成 SQL" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="sql">{{ row.sql || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="datasourceId" label="数据源" width="90" align="center" />
        <el-table-column prop="rowCount" label="行数" width="70" align="center" />
        <el-table-column prop="durationMs" label="耗时" width="92" align="center">
          <template #default="{ row }">{{ row.durationMs }} ms</template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'success' ? 'success' : 'danger'" size="small">
              {{ row.status === 'success' ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="时间" width="172" />
        <el-table-column label="操作" width="120" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="danger" @click="del(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="detailVisible" title="查询详情" width="760px">
      <template v-if="detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="问题" :span="2">{{ detail.query }}</el-descriptions-item>
          <el-descriptions-item label="数据源">{{ detail.datasourceId }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="detail.status === 'success' ? 'success' : 'danger'" size="small">
              {{ detail.status === 'success' ? '成功' : '失败' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="行数">{{ detail.rowCount }}</el-descriptions-item>
          <el-descriptions-item label="耗时">{{ detail.durationMs }} ms</el-descriptions-item>
          <el-descriptions-item label="时间" :span="2">{{ detail.createTime }}</el-descriptions-item>
        </el-descriptions>
        <div class="block-label">生成 SQL</div>
        <pre class="code">{{ detail.sql || '—' }}</pre>
        <template v-if="detail.status === 'failed'">
          <div class="block-label">失败原因</div>
          <pre class="code err">{{ detail.errorMsg }}</pre>
        </template>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listQueryHistory,
  getQueryHistory,
  deleteQueryHistory,
  deleteQueryHistories
} from '@/api/queryHistory'

const rows = ref([])
const loading = ref(false)
const keyword = ref('')
const selected = ref([])
const detailVisible = ref(false)
const detail = ref(null)

async function load() {
  loading.value = true
  try {
    rows.value = await listQueryHistory({
      keyword: keyword.value || undefined,
      page: 1,
      size: 50
    })
  } catch (e) {
    ElMessage.error('加载查询历史失败')
  } finally {
    loading.value = false
  }
}

function onSelect(val) {
  selected.value = val
}

async function openDetail(row) {
  detail.value = await getQueryHistory(row.id)
  detailVisible.value = true
}

async function del(row) {
  await ElMessageBox.confirm('确认删除该条历史？', '提示', { type: 'warning' })
  await deleteQueryHistory(row.id)
  ElMessage.success('已删除')
  load()
}

async function batchDelete() {
  const ids = selected.value.map((r) => r.id)
  await ElMessageBox.confirm(`确认删除选中的 ${ids.length} 条历史？`, '提示', { type: 'warning' })
  await deleteQueryHistories(ids)
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
.sql {
  font-family: monospace;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.block-label {
  margin: 14px 0 6px;
  font-weight: 600;
}
.code {
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color);
  border-radius: 10px;
  padding: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 13px;
  max-height: 320px;
  overflow: auto;
}
.code.err {
  color: var(--el-color-danger);
}
</style>
