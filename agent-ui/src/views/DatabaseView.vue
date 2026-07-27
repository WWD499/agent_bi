<template>
  <div class="page">
    <div class="toolbar glass">
      <div class="tb-title">数据库管理</div>
      <div class="tb-actions">
        <el-input
          v-model="keyword"
          placeholder="搜索数据源名称"
          clearable
          class="w220"
          :prefix-icon="Search"
        />
        <el-button type="primary" :icon="Plus" @click="openCreate">新建数据源</el-button>
        <el-button
          type="danger"
          :icon="Delete"
          :disabled="!selected.length"
          @click="batchRemove"
        >批量删除（{{ selected.length }}）</el-button>
      </div>
    </div>

    <div class="card glass">
      <el-table
        :data="filtered"
        border
        stripe
        row-key="id"
        max-height="560"
        empty-text="暂无数据源，点击「新建数据源」添加"
        @selection-change="onSelect"
      >
        <el-table-column type="selection" width="46" />
        <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column label="类型" width="120">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ typeLabel(row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="连接地址" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.host }}:{{ row.port }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="databaseName" label="数据库" min-width="120" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="160">
          <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="Link" :loading="row._testing" @click="testRow(row)">测试连接</el-button>
            <el-button link type="primary" :icon="Edit" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" :icon="Delete" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新建 / 编辑 对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑数据源' : '新建数据源'"
      width="520px"
      :close-on-click-modal="false"
      @closed="onDialogClosed"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="92px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="数据源名称，如：生产库" />
        </el-form-item>
        <el-form-item label="类型" prop="type">
          <el-select v-model="form.type" placeholder="选择数据库类型" @change="onTypeChange">
            <el-option label="MySQL" value="mysql" />
            <el-option label="PostgreSQL" value="postgresql" />
            <el-option label="Oracle" value="oracle" />
            <el-option label="SQL Server" value="sqlserver" />
          </el-select>
        </el-form-item>
        <el-form-item label="主机" prop="host">
          <el-input v-model="form.host" placeholder="如 127.0.0.1" />
        </el-form-item>
        <el-form-item label="端口" prop="port">
          <el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" />
        </el-form-item>
        <el-form-item label="数据库名" prop="databaseName">
          <el-input v-model="form.databaseName" placeholder="要连接的库名" />
        </el-form-item>
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="数据库账号" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="isEdit ? '不修改请留空' : '数据库密码'"
          />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :loading="testLoading" :icon="Link" @click="testInDialog">测试连接</el-button>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saveLoading" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { Plus, Delete, Edit, Link, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listDatasources,
  createDatasource,
  updateDatasource,
  deleteDatasource,
  batchDeleteDatasources,
  testDatasource
} from '@/api/datasource'

const TYPE_LABELS = {
  mysql: 'MySQL',
  postgresql: 'PostgreSQL',
  oracle: 'Oracle',
  sqlserver: 'SQL Server'
}
const DEFAULT_PORTS = { mysql: 3306, postgresql: 5432, oracle: 1521, sqlserver: 1433 }

const list = ref([])
const keyword = ref('')
const selected = ref([])
const loading = ref(false)

const dialogVisible = ref(false)
const isEdit = ref(false)
const saveLoading = ref(false)
const testLoading = ref(false)
const formRef = ref(null)
const form = reactive({
  id: null,
  name: '',
  type: 'mysql',
  host: '',
  port: 3306,
  databaseName: '',
  username: '',
  password: '',
  status: 1,
  remark: ''
})

const rules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择类型', trigger: 'change' }],
  host: [{ required: true, message: '请输入主机', trigger: 'blur' }],
  port: [{ required: true, message: '请输入端口', trigger: 'blur' }],
  databaseName: [{ required: true, message: '请输入数据库名', trigger: 'blur' }],
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }]
}

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return list.value
  return list.value.filter((d) => (d.name || '').toLowerCase().includes(kw))
})

function typeLabel(t) {
  return TYPE_LABELS[t] || t || '未知'
}

function fmtTime(t) {
  if (!t) return '—'
  // LocalDateTime 可能被 Jackson 序列化为数组或字符串，两种都兼容
  if (Array.isArray(t)) {
    const p = (n) => String(n).padStart(2, '0')
    return `${t[0]}-${p(t[1])}-${p(t[2])} ${p(t[3] || 0)}:${p(t[4] || 0)}`
  }
  return String(t).replace('T', ' ')
}

async function load() {
  loading.value = true
  try {
    const data = await listDatasources()
    list.value = Array.isArray(data) ? data : []
  } catch {
    list.value = []
  } finally {
    loading.value = false
  }
}

function onSelect(rows) {
  selected.value = rows
}

function resetForm() {
  Object.assign(form, {
    id: null,
    name: '',
    type: 'mysql',
    host: '',
    port: 3306,
    databaseName: '',
    username: '',
    password: '',
    status: 1,
    remark: ''
  })
  formRef.value?.clearValidate?.()
}

function openCreate() {
  isEdit.value = false
  resetForm()
  dialogVisible.value = true
}

function openEdit(row) {
  isEdit.value = true
  Object.assign(form, {
    id: row.id,
    name: row.name,
    type: row.type,
    host: row.host,
    port: row.port,
    databaseName: row.databaseName,
    username: '',
    password: '',
    status: row.status ?? 1,
    remark: row.remark ?? ''
  })
  dialogVisible.value = true
}

function onTypeChange(t) {
  // 端口为空或仍是上一次类型的默认值时，自动套用新类型的默认端口
  if (!form.port || DEFAULT_PORTS[form.type] === form.port) {
    form.port = DEFAULT_PORTS[t]
  }
}

function onDialogClosed() {
  formRef.value?.clearValidate?.()
}

// 构造测试连接请求体（编辑时带 id，密码留空则后端用库中原值）
function buildTestPayload() {
  return {
    id: isEdit.value ? form.id : null,
    type: form.type,
    host: form.host,
    port: form.port,
    databaseName: form.databaseName,
    username: form.username,
    password: form.password
  }
}

async function testInDialog() {
  if (!form.host || !form.port || !form.databaseName || !form.username) {
    ElMessage.warning('请先填写主机、端口、数据库名与用户名')
    return
  }
  testLoading.value = true
  try {
    const res = await testDatasource(buildTestPayload())
    if (res && res.code === 200) {
      ElMessage.success((res.data && res.data.message) || '连接成功')
    } else {
      ElMessage.error((res && res.msg) || '连接失败')
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e.message || '连接测试异常')
  } finally {
    testLoading.value = false
  }
}

async function testRow(row) {
  row._testing = true
  try {
    // 行内测试用已保存配置（密码在库中不回传，后端按 id 加载）
    const res = await testDatasource({ id: row.id })
    if (res && res.code === 200) {
      ElMessage.success(`${row.name}：${res.data?.message || '连接成功'}`)
    } else {
      ElMessage.error(`${row.name}：${res?.msg || '连接失败'}`)
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e.message || '连接测试异常')
  } finally {
    row._testing = false
  }
}

async function submit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    saveLoading.value = true
    try {
      const payload = { ...form }
      const res = isEdit.value
        ? await updateDatasource(payload)
        : await createDatasource(payload)
      if (res && res.code === 200) {
        ElMessage.success(isEdit.value ? '保存成功' : '创建成功')
        dialogVisible.value = false
        await load()
      } else {
        ElMessage.error((res && res.msg) || '保存失败')
      }
    } catch (e) {
      ElMessage.error(e?.response?.data?.msg || e.message || '保存异常')
    } finally {
      saveLoading.value = false
    }
  })
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除数据源「${row.name}」？该操作不可恢复。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }
    )
  } catch {
    return
  }
  try {
    const res = await deleteDatasource(row.id)
    if (res && res.code === 200) {
      ElMessage.success('已删除')
      await load()
    } else {
      ElMessage.error((res && res.msg) || '删除失败')
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e.message || '删除异常')
  }
}

async function batchRemove() {
  if (!selected.value.length) return
  const names = selected.value.map((d) => d.name).join('、')
  try {
    await ElMessageBox.confirm(
      `确认批量删除以下 ${selected.value.length} 个数据源？\n${names}`,
      '批量删除',
      { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }
    )
  } catch {
    return
  }
  try {
    const res = await batchDeleteDatasources(selected.value.map((d) => d.id))
    if (res && res.code === 200) {
      ElMessage.success('已批量删除')
      await load()
    } else {
      ElMessage.error((res && res.msg) || '批量删除失败')
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e.message || '批量删除异常')
  }
}

onMounted(load)
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.toolbar {
  padding: 16px 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.tb-title {
  font-size: 16px;
  font-weight: 700;
}
.tb-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.w220 {
  width: 220px;
}
.card {
  padding: 6px 6px 2px;
}
.mono {
  font-family: var(--mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 13px;
}
</style>
