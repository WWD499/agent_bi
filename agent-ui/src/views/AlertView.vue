<template>
  <div class="page">
    <div class="head glass">
      <div class="h-title">预警中心</div>
      <div class="h-actions">
        <el-button type="primary" :icon="Plus" @click="openAdd">新增规则</el-button>
        <el-button :icon="Refresh" :loading="checking" @click="runCheck">立即检查</el-button>
        <el-button type="danger" :disabled="!selectedIds.length" :icon="Delete" @click="batchDelete">批量删除</el-button>
      </div>
    </div>

    <div class="card glass">
      <div class="card-h">预警规则</div>
      <el-table
        :data="rules"
        border
        stripe
        v-loading="rulesLoading"
        @selection-change="onSelect"
        empty-text="暂无规则"
      >
        <el-table-column type="selection" width="46" />
        <el-table-column prop="name" label="规则名称" min-width="140" show-over-flow-tooltip />
        <el-table-column prop="tableName" label="目标表" min-width="120" show-over-flow-tooltip />
        <el-table-column prop="metricField" label="指标字段" min-width="110" show-over-flow-tooltip />
        <el-table-column prop="comparisonOperator" label="比较" width="70" align="center" />
        <el-table-column prop="thresholdValue" label="阈值" width="90" align="center" />
        <el-table-column prop="checkInterval" label="间隔(分)" width="90" align="center" />
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button text type="danger" size="small" @click="removeOne(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="card glass">
      <div class="card-h">
        <span>预警记录</span>
        <el-button type="danger" size="small" :disabled="!recordSelectedIds.length" :icon="Delete" @click="batchDeleteRecords">批量删除</el-button>
      </div>
      <el-table
        :data="records"
        border
        stripe
        v-loading="recordsLoading"
        @selection-change="onRecordSelect"
        empty-text="暂无记录"
      >
        <el-table-column type="selection" width="46" />
        <el-table-column prop="ruleName" label="规则" min-width="140" show-over-flow-tooltip />
        <el-table-column prop="tableName" label="目标表" min-width="120" show-over-flow-tooltip />
        <el-table-column prop="actualValue" label="实际值" width="100" align="center" />
        <el-table-column prop="comparisonOperator" label="比较" width="64" align="center" />
        <el-table-column prop="thresholdValue" label="阈值" width="90" align="center" />
        <el-table-column prop="alertLevel" label="级别" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.alertLevel" size="small">{{ row.alertLevel }}</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="alertTime" label="触发时间" min-width="160" show-over-flow-tooltip />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'resolved' ? 'success' : (row.status === 'confirmed' ? 'warning' : 'danger')" size="small">
              {{ row.status || '待处理' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" align="center" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="openDetail(row)">查看</el-button>
            <el-button
              text
              type="success"
              size="small"
              :disabled="row.status === 'resolved'"
              @click="openHandle(row)"
            >处理</el-button>
            <el-button
              text
              type="danger"
              size="small"
              @click="removeRecord(row)"
            >删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 规则新增/编辑 -->
    <el-dialog v-model="ruleDlg" :title="editingId ? '编辑规则' : '新增规则'" width="560px">
      <el-form :model="form" label-width="92px" label-position="right">
        <el-form-item label="规则名称" required>
          <el-input v-model="form.name" placeholder="如：销售额异常波动" />
        </el-form-item>
        <el-form-item label="数据源ID">
          <el-input-number v-model="form.datasourceId" :min="0" controls-position="right" />
        </el-form-item>
        <el-form-item label="目标表">
          <el-input v-model="form.tableName" placeholder="如：bi_sales" />
        </el-form-item>
        <el-form-item label="指标字段">
          <el-input v-model="form.metricField" placeholder="如：amount" />
        </el-form-item>
        <el-form-item label="检查SQL">
          <el-input v-model="form.conditionSql" type="textarea" :rows="2" resize="none" placeholder="用于查出当前指标值的 SQL" />
        </el-form-item>
        <el-form-item label="比较运算符">
          <el-select v-model="form.comparisonOperator" style="width: 140px">
            <el-option v-for="op in operators" :key="op" :label="op" :value="op" />
          </el-select>
        </el-form-item>
        <el-form-item label="阈值">
          <el-input-number v-model="form.thresholdValue" :precision="2" controls-position="right" />
        </el-form-item>
        <el-form-item label="检查间隔(分)">
          <el-input-number v-model="form.checkInterval" :min="1" :max="1440" controls-position="right" />
        </el-form-item>
        <el-form-item label="通知方式">
          <el-input v-model="form.notifyType" placeholder="email / sms / wechat（逗号分隔）" />
        </el-form-item>
        <el-form-item label="通知目标">
          <el-input v-model="form.notifyTarget" placeholder="邮箱 / 手机号" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch :model-value="form.status === 1" @change="(v) => (form.status = v ? 1 : 0)" />
        </el-form-item>
        <el-form-item label="AI 分析">
          <el-switch :model-value="form.analysisEnabled === 1" @change="(v) => (form.analysisEnabled = v ? 1 : 0)" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveRule">保存</el-button>
      </template>
    </el-dialog>

    <!-- 记录详情 -->
    <el-dialog v-model="detailDlg" title="预警记录详情" width="600px">
      <el-descriptions :column="1" border v-if="detail">
        <el-descriptions-item label="规则">{{ detail.ruleName }}</el-descriptions-item>
        <el-descriptions-item label="目标表">{{ detail.tableName }}</el-descriptions-item>
        <el-descriptions-item label="检查 SQL">
          <pre class="code-box">{{ detail.checkSql }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="实际值 / 阈值">
          {{ detail.actualValue }} {{ detail.comparisonOperator }} {{ detail.thresholdValue }}
        </el-descriptions-item>
        <el-descriptions-item label="级别">{{ detail.alertLevel || '—' }}</el-descriptions-item>
        <el-descriptions-item label="触发时间">{{ detail.alertTime || '—' }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ detail.status || '待处理' }}</el-descriptions-item>
        <el-descriptions-item label="预警信息">{{ detail.alertMessage }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.analysisResult" label="AI 分析">
          {{ detail.analysisResult }}
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>

    <!-- 处理记录 -->
    <el-dialog v-model="handleDlg" title="处理预警记录" width="520px">
      <el-form :model="handleForm" label-width="84px" label-position="right">
        <el-form-item label="处理后状态">
          <el-select v-model="handleForm.status" style="width: 160px">
            <el-option label="已确认" value="confirmed" />
            <el-option label="已解决" value="resolved" />
          </el-select>
        </el-form-item>
        <el-form-item label="处理人">
          <el-input v-model="handleForm.handledBy" placeholder="处理人" />
        </el-form-item>
        <el-form-item label="处理备注">
          <el-input v-model="handleForm.handledRemark" type="textarea" :rows="3" resize="none" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="handleDlg = false">取消</el-button>
        <el-button type="primary" :loading="handling" @click="submitHandle">提交处理</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { Plus, Refresh, Delete } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listRules,
  addRule,
  updateRule,
  deleteRules,
  checkAlert,
  listRecords,
  getRecord,
  handleRecord,
  deleteRecords
} from '@/api/alert'

const operators = ['>', '<', '>=', '<=', '=', '!=']

const rules = ref([])
const records = ref([])
const rulesLoading = ref(false)
const recordsLoading = ref(false)
const checking = ref(false)
const saving = ref(false)
const handling = ref(false)
const selectedIds = ref([])
const recordSelectedIds = ref([])

const ruleDlg = ref(false)
const editingId = ref(null)
const detailDlg = ref(false)
const detail = ref(null)
const handleDlg = ref(false)
const handleForm = reactive({ id: null, status: 'confirmed', handledBy: '', handledRemark: '' })

const form = reactive({
  name: '',
  datasourceId: 0,
  tableName: '',
  metricField: '',
  conditionSql: '',
  thresholdValue: 0,
  comparisonOperator: '>',
  checkInterval: 60,
  notifyType: '',
  notifyTarget: '',
  status: 1,
  analysisEnabled: 1
})

function resetForm() {
  Object.assign(form, {
    name: '',
    datasourceId: 0,
    tableName: '',
    metricField: '',
    conditionSql: '',
    thresholdValue: 0,
    comparisonOperator: '>',
    checkInterval: 60,
    notifyType: '',
    notifyTarget: '',
    status: 1,
    analysisEnabled: 1
  })
}

async function loadRules() {
  rulesLoading.value = true
  try {
    rules.value = (await listRules()) || []
  } catch {
    /* 拦截器已提示 */
  } finally {
    rulesLoading.value = false
  }
}
async function loadRecords() {
  recordsLoading.value = true
  try {
    records.value = (await listRecords()) || []
  } catch {
    /* 拦截器已提示 */
  } finally {
    recordsLoading.value = false
  }
}

function onSelect(rows) {
  selectedIds.value = rows.map((r) => r.id)
}
function onRecordSelect(rows) {
  recordSelectedIds.value = rows.map((r) => r.id)
}
function openAdd() {
  editingId.value = null
  resetForm()
  ruleDlg.value = true
}
function openEdit(row) {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name || '',
    datasourceId: row.datasourceId || 0,
    tableName: row.tableName || '',
    metricField: row.metricField || '',
    conditionSql: row.conditionSql || '',
    thresholdValue: row.thresholdValue || 0,
    comparisonOperator: row.comparisonOperator || '>',
    checkInterval: row.checkInterval || 60,
    notifyType: row.notifyType || '',
    notifyTarget: row.notifyTarget || '',
    status: row.status === 0 ? 0 : 1,
    analysisEnabled: row.analysisEnabled === 0 ? 0 : 1
  })
  ruleDlg.value = true
}
async function saveRule() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写规则名称')
    return
  }
  saving.value = true
  try {
    const payload = { ...form, name: form.name.trim() }
    if (editingId.value) payload.id = editingId.value
    await (editingId.value ? updateRule(payload) : addRule(payload))
    ElMessage.success('已保存')
    ruleDlg.value = false
    await loadRules()
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}
async function removeOne(row) {
  await ElMessageBox.confirm(`确认删除规则「${row.name}」？`, '提示', { type: 'warning' }).catch(() => Promise.reject('cancel'))
  try {
    await deleteRules([row.id])
    ElMessage.success('已删除')
    await loadRules()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败')
  }
}
async function batchDelete() {
  if (!selectedIds.value.length) return
  await ElMessageBox.confirm(`确认删除选中的 ${selectedIds.value.length} 条规则？`, '提示', { type: 'warning' }).catch(() => Promise.reject('cancel'))
  try {
    await deleteRules(selectedIds.value)
    ElMessage.success('已删除')
    await loadRules()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败')
  }
}
async function runCheck() {
  checking.value = true
  try {
    await checkAlert()
    ElMessage.success('已触发检查')
    await loadRecords()
  } catch {
    /* 拦截器已提示 */
  } finally {
    checking.value = false
  }
}
async function openDetail(row) {
  detail.value = row
  try {
    detail.value = (await getRecord(row.id)) || row
  } catch {
    /* 拦截器已提示 */
  }
  detailDlg.value = true
}
function openHandle(row) {
  handleForm.id = row.id
  handleForm.status = 'confirmed'
  handleForm.handledBy = ''
  handleForm.handledRemark = ''
  handleDlg.value = true
}
async function removeRecord(row) {
  await ElMessageBox.confirm('确认删除该预警记录？删除后不可恢复。', '提示', { type: 'warning' }).catch(() => Promise.reject('cancel'))
  try {
    await deleteRecords([row.id])
    ElMessage.success('已删除')
    await loadRecords()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败')
  }
}
async function batchDeleteRecords() {
  if (!recordSelectedIds.value.length) return
  await ElMessageBox.confirm(`确认删除选中的 ${recordSelectedIds.value.length} 条预警记录？`, '提示', { type: 'warning' }).catch(() => Promise.reject('cancel'))
  try {
    await deleteRecords(recordSelectedIds.value)
    ElMessage.success('已删除')
    await loadRecords()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败')
  }
}
async function submitHandle() {
  if (!handleForm.status) {
    ElMessage.warning('请选择处理后状态')
    return
  }
  handling.value = true
  try {
    await handleRecord(handleForm.id, {
      status: handleForm.status,
      handledBy: handleForm.handledBy,
      handledRemark: handleForm.handledRemark
    })
    ElMessage.success('已处理')
    handleDlg.value = false
    await loadRecords()
  } catch {
    /* 拦截器已提示 */
  } finally {
    handling.value = false
  }
}

onMounted(() => {
  loadRules()
  loadRecords()
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
  flex-wrap: wrap;
}
.card {
  padding: 16px 18px;
}
.card-h {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
