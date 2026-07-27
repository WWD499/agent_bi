<template>
  <div class="page">
    <div class="head glass">
      <div class="h-title">知识库（RAG）</div>
      <div class="h-actions">
        <el-button type="primary" :icon="Plus" @click="openAdd">新增条目</el-button>
        <el-button :icon="Refresh" :loading="listLoading" @click="loadList">刷新</el-button>
      </div>
    </div>

    <div class="card glass">
      <div class="card-h">语义检索测试</div>
      <div class="search-row">
        <el-input v-model="q" placeholder="输入检索问题，如：如何计算月度环比？" class="q" @keydown.enter="runSearch" />
        <el-input-number v-model="topK" :min="1" :max="20" controls-position="right" class="tk" />
        <el-input v-model="domain" placeholder="业务域（可选）" class="dm" />
        <el-button type="primary" :icon="Search" :loading="searching" @click="runSearch">检索</el-button>
      </div>
      <el-table :data="results" border stripe v-loading="searching" empty-text="暂无检索结果" class="mt">
        <el-table-column prop="title" label="标题" min-width="160" show-over-flow-tooltip />
        <el-table-column prop="businessDomain" label="业务域" width="120" />
        <el-table-column prop="sourceType" label="来源" width="90" align="center" />
        <el-table-column label="内容摘要" min-width="220">
          <template #default="{ row }">
            <span class="snippet">{{ snippet(row.content) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="tags" label="标签" min-width="120" show-over-flow-tooltip />
      </el-table>
    </div>

    <div class="card glass">
      <div class="card-h">知识条目</div>
      <el-table :data="list" border stripe v-loading="listLoading" empty-text="暂无条目">
        <el-table-column prop="id" label="ID" width="70" align="center" />
        <el-table-column prop="title" label="标题" min-width="160" show-over-flow-tooltip />
        <el-table-column prop="businessDomain" label="业务域" width="120" />
        <el-table-column prop="sourceType" label="来源" width="90" align="center" />
        <el-table-column prop="tags" label="标签" min-width="120" show-over-flow-tooltip />
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" min-width="160" show-over-flow-tooltip />
        <el-table-column label="操作" width="140" align="center" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button text type="danger" size="small" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dlg" :title="editingId ? '编辑条目' : '新增条目'" width="560px">
      <el-form :model="form" label-width="84px" label-position="right">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" placeholder="知识标题" />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="form.content" type="textarea" :rows="4" resize="none" placeholder="知识正文（将被切片并向量化）" />
        </el-form-item>
        <el-form-item label="来源类型">
          <el-select v-model="form.sourceType" style="width: 160px">
            <el-option label="手动录入" value="manual" />
            <el-option label="OCR 识别" value="ocr" />
            <el-option label="文件上传" value="file" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源 URL">
          <el-input v-model="form.sourceUrl" placeholder="文件路径或链接（可选）" />
        </el-form-item>
        <el-form-item label="业务域">
          <el-input v-model="form.businessDomain" placeholder="如：财务 / 销售" />
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="form.tags" placeholder="逗号分隔，如：环比,报表" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch :model-value="form.status === 1" @change="(v) => (form.status = v ? 1 : 0)" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { addKnowledge, listKnowledge, searchKnowledge, deleteKnowledge } from '@/api/knowledge'

const q = ref('')
const topK = ref(5)
const domain = ref('')
const results = ref([])
const list = ref([])
const searching = ref(false)
const listLoading = ref(false)
const saving = ref(false)

const dlg = ref(false)
const editingId = ref(null)
const form = reactive({
  title: '',
  content: '',
  sourceType: 'manual',
  sourceUrl: '',
  businessDomain: '',
  tags: '',
  status: 1
})

function snippet(text) {
  if (!text) return '—'
  return text.length > 80 ? text.slice(0, 80) + '…' : text
}
function resetForm() {
  Object.assign(form, {
    title: '',
    content: '',
    sourceType: 'manual',
    sourceUrl: '',
    businessDomain: '',
    tags: '',
    status: 1
  })
}

async function loadList() {
  listLoading.value = true
  try {
    list.value = (await listKnowledge()) || []
  } catch {
    /* 拦截器已提示 */
  } finally {
    listLoading.value = false
  }
}
async function runSearch() {
  const kw = q.value.trim()
  if (!kw) {
    ElMessage.warning('请输入检索问题')
    return
  }
  searching.value = true
  results.value = []
  try {
    results.value = (await searchKnowledge(kw, topK.value, domain.value.trim())) || []
  } catch {
    /* 拦截器已提示 */
  } finally {
    searching.value = false
  }
}

function openAdd() {
  editingId.value = null
  resetForm()
  dlg.value = true
}
function openEdit(row) {
  editingId.value = row.id
  Object.assign(form, {
    title: row.title || '',
    content: row.content || '',
    sourceType: row.sourceType || 'manual',
    sourceUrl: row.sourceUrl || '',
    businessDomain: row.businessDomain || '',
    tags: row.tags || '',
    status: row.status === 0 ? 0 : 1
  })
  dlg.value = true
}
async function save() {
  if (!form.title.trim() || !form.content.trim()) {
    ElMessage.warning('请填写标题与内容')
    return
  }
  saving.value = true
  try {
    const payload = { ...form, title: form.title.trim(), content: form.content.trim() }
    if (editingId.value) payload.id = editingId.value
    await addKnowledge(payload)
    ElMessage.success('已保存')
    dlg.value = false
    await loadList()
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}
async function remove(row) {
  await ElMessageBox.confirm(`确认删除「${row.title}」？`, '提示', { type: 'warning' }).catch(() => Promise.reject('cancel'))
  try {
    await deleteKnowledge(row.id)
    ElMessage.success('已删除')
    await loadList()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败')
  }
}

onMounted(() => {
  loadList()
})
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
}
.h-title {
  font-size: 16px;
  font-weight: 700;
}
.h-actions {
  display: flex;
  gap: 10px;
}
.card {
  padding: 16px 18px;
}
.card-h {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 12px;
}
.search-row {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.q {
  flex: 1;
  min-width: 220px;
}
.tk {
  width: 130px;
}
.dm {
  width: 160px;
}
.mt {
  margin-top: 4px;
}
.snippet {
  color: var(--text-dim);
  font-size: 13px;
}
</style>
