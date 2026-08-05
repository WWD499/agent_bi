<template>
  <div class="sandbox">
    <div class="sb-head">
      <span class="sb-title">数据沙箱</span>
      <span class="sb-sub">新建「沙箱库」分组管理表，再从数据源克隆或粘贴导入，让 Agent 按库分析，源数据零污染</span>
      <el-button text :icon="Document" class="sb-audit-btn" @click="openAudit">审计日志</el-button>
    </div>

    <div class="sb-body">
      <!-- 左：沙箱库 → 表 两级结构 -->
      <aside class="sb-list glass">
        <div class="sb-list-head">
          <span>沙箱库 / 表</span>
          <div class="sb-list-actions">
            <el-button text :icon="Plus" @click="openCreateDb" />
            <el-button text :icon="Refresh" @click="reloadAll" />
          </div>
        </div>
        <div v-if="dbs.length === 0" class="sb-empty">暂无沙箱库，点 + 新建</div>
        <div
          v-for="g in grouped"
          :key="g.db.id"
          class="sb-group"
        >
          <div class="sb-group-head">
            <span class="sb-group-name">{{ g.db.name }}</span>
            <span class="sb-group-key">{{ g.db.dbKey }}</span>
            <span class="sb-group-count">{{ g.tables.length }}</span>
            <el-button
              v-if="g.db.dbKey !== 'default'"
              text type="danger" :icon="Delete"
              @click.stop="removeDb(g.db)"
            />
          </div>
          <div
            v-for="t in g.tables"
            :key="t.physicalName"
            class="sb-item"
            :class="{ active: selected === t.physicalName }"
          >
            <div class="sb-item-main" @click="selectTable(t.physicalName, t.dbId)">
              <span class="sb-item-name">{{ t.displayName || t.physicalName }}</span>
              <span class="sb-item-sub">{{ t.physicalName }}</span>
            </div>
            <div class="sb-item-actions">
              <el-button text :icon="Edit" title="修改显示名" @click.stop="renameTable(t)" />
              <el-button text type="danger" :icon="Close" @click.stop="removeTable(t.physicalName)" />
            </div>
          </div>
          <div v-if="g.tables.length === 0" class="sb-empty sm">该库暂无表</div>
        </div>
      </aside>

      <!-- 右：操作区 -->
      <section class="sb-main glass">
        <div class="sb-target-row">
          <span class="sb-label">导入到库：</span>
          <el-select v-model="targetDbId" placeholder="选择目标沙箱库" style="width:240px">
            <el-option v-for="d in dbs" :key="d.id" :label="`${d.name}（${d.dbKey}）`" :value="d.id" />
          </el-select>
        </div>

        <el-radio-group v-model="mode" size="small" class="sb-mode">
          <el-radio-button label="datasource">从数据源导入</el-radio-button>
          <el-radio-button label="paste">粘贴导入</el-radio-button>
          <el-radio-button label="upload">文件上传</el-radio-button>
        </el-radio-group>

        <!-- 模式一：从数据源勾选表导入 -->
        <div v-if="mode === 'datasource'" class="sb-ds">
          <div class="sb-ds-row">
            <el-select
              v-model="selectedDs"
              placeholder="选择数据源"
              style="width:260px"
              :loading="dsLoading"
              @change="onDsChange"
            >
              <el-option v-for="d in dsList" :key="d.id" :label="d.name + '（' + d.type + '）'" :value="d.id" />
            </el-select>
            <el-button
              type="primary"
              :icon="Upload"
              :disabled="!selectedDs || checkedTables.length === 0"
              :loading="importing"
              @click="doImportDs"
            >导入选中（{{ checkedTables.length }}）</el-button>
          </div>

          <div v-if="dsList.length === 0" class="sb-tip">
            尚未配置数据源，请先在「数据库管理」中添加可连接的数据源。
          </div>
          <div v-else-if="selectedDs && dsLoading" class="sb-tip">正在读取表清单…</div>
          <div v-else-if="selectedDs && dsTables.length === 0" class="sb-tip">该数据源暂无可见表。</div>
          <div v-else-if="selectedDs" class="sb-table-pick">
            <el-checkbox-group v-model="checkedTables" class="sb-check-wrap">
              <div v-for="t in dsTables" :key="t.tableName" class="sb-check-item">
                <el-checkbox :value="t.tableName" class="sb-check-box">{{ t.tableName }}</el-checkbox>
                <span v-if="t.remarks" class="sb-check-remark">{{ t.remarks }}</span>
              </div>
            </el-checkbox-group>
          </div>
          <div class="sb-tip">
            勾选后点击「导入选中」：系统会把所选表克隆进「{{ targetDbName }}」（每表最多 10000 行，源数据不会被修改）。
          </div>
        </div>

        <!-- 模式二：粘贴导入 -->
        <div v-else-if="mode === 'paste'" class="sb-import">
          <div class="sb-import-row">
            <el-input v-model="importName" placeholder="表名（英文/数字/下划线）" style="width:220px" />
            <el-select v-model="separator" placeholder="分隔符" style="width:150px">
              <el-option label="自动检测" value="" />
              <el-option label="逗号 ," value="," />
              <el-option label="制表符 Tab" :value="'\t'" />
            </el-select>
            <el-button type="primary" :icon="Upload" :loading="importing" @click="doImport">导入沙箱</el-button>
          </div>
          <el-input
            v-model="rawText"
            type="textarea"
            :rows="10"
            resize="none"
            placeholder="在此粘贴 CSV / TSV 数据（首行为表头）&#10;示例：&#10;region,amount&#10;华东,1200&#10;华北,980"
          />
          <div class="sb-tip">
            系统将自动推断列类型（整数/小数/日期/文本）并建表写入「{{ targetDbName }}」；导入后可在左侧选中查看，或切到「智能对话」选择对应沙箱库让 Agent 分析。
          </div>
        </div>

        <!-- 模式三：文件上传（M3） -->
        <div v-else class="sb-upload">
          <el-upload
            ref="uploadRef"
            :auto-upload="false"
            :limit="1"
            :show-file-list="true"
            accept=".csv,.xlsx,.xls"
            :on-change="onFileChange"
            :on-exceed="() => ElMessage.warning('每次仅支持单个文件')"
            class="sb-upload-drag"
          >
            <template #trigger>
              <el-button :icon="Upload">选择 Excel / CSV 文件</el-button>
            </template>
            <template #tip>
              <div class="sb-tip">支持 .csv / .xlsx / .xls；首行作为表头，自动推断列类型后建表写入「{{ targetDbName }}」</div>
            </template>
          </el-upload>
          <div class="sb-import-row">
            <el-input v-model="uploadTableName" placeholder="表名（留空则取文件名）" style="width:220px" />
            <el-button type="primary" :icon="Upload" :loading="uploading" :disabled="!uploadFile || targetDbId == null" @click="doUpload">导入沙箱</el-button>
          </div>
        </div>

        <!-- 选中表预览 -->
        <div v-if="selected" class="sb-preview">
          <div class="sb-preview-head">
            <span class="sb-preview-name">表：sandbox.{{ selected }}</span>
            <el-button text type="primary" :icon="ChatDotRound" @click="analyzeInChat">在对话中分析</el-button>
          </div>
          <div v-if="columns.length" class="sb-cols">
            <el-tag v-for="c in columns" :key="c.columnName" class="sb-col" type="info" effect="plain">
              {{ c.label || c.columnName }} <em>{{ c.dataType }}</em>
            </el-tag>
          </div>
          <el-table v-if="previewData.length" :data="previewData" size="small" max-height="320" border>
            <el-table-column v-for="col in previewColumns" :key="col" :prop="col" :label="colLabelOf(col)" sortable />
          </el-table>
          <div v-else class="sb-empty">暂无数据预览</div>
        </div>
      </section>
    </div>

    <!-- 新建沙箱库对话框 -->
    <el-dialog v-model="dbDialogVisible" title="新建沙箱库" width="420px">
      <el-form label-width="90px">
        <el-form-item label="库名称" required>
          <el-input v-model="newDbName" placeholder="如 销售主题域（可中文）" />
        </el-form-item>
        <el-form-item label="英文标识" required>
          <el-input v-model="newDbKey" placeholder="如 sales_dm（库前缀，不可重复）" />
          <div class="sb-tip">物理表名将使用 标识__表名 形式（如 sales_dm__orders）</div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="newDbRemark" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dbDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="createDb">创建</el-button>
      </template>
    </el-dialog>

    <!-- 沙箱操作审计抽屉（M3） -->
    <el-drawer v-model="auditVisible" title="沙箱操作审计" size="62%" direction="rtl">
      <div class="sb-audit">
        <div class="sb-audit-bar">
          <span class="sb-audit-tip">记录沙箱内的导入 / 建表 / 落表 / 删表等写操作（旁路登记，不影响主流程）</span>
          <el-button text :icon="Refresh" :loading="auditLoading" @click="loadAudit">刷新</el-button>
        </div>
        <el-table :data="auditList" size="small" max-height="72vh" border stripe>
          <el-table-column prop="operation" label="操作" width="130" />
          <el-table-column prop="target" label="对象" min-width="160" show-overflow-tooltip />
          <el-table-column prop="operator" label="操作人" width="100" />
          <el-table-column label="结果" width="80">
            <template #default="{ row }">
              <el-tag :type="row.success ? 'success' : 'danger'" size="small" effect="plain">
                {{ row.success ? '成功' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="时间" width="165" />
          <el-table-column prop="detail" label="详情" min-width="200" show-overflow-tooltip />
        </el-table>
        <div v-if="auditList.length === 0 && !auditLoading" class="sb-empty">暂无审计记录</div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh, Delete, Upload, ChatDotRound, Plus, Close, Edit, Document } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listSandboxDbs,
  createSandboxDb,
  dropSandboxDb,
  importSandboxText,
  importSandboxFromDatasource,
  importSandboxFile,
  listSandboxAudit,
  listSandboxTables,
  getSandboxColumns,
  getSandboxData,
  dropSandboxTable,
  updateSandboxDisplayName
} from '@/api/sandbox'
import { listDatasources, listTables } from '@/api/datasource'

const router = useRouter()
const dbs = ref([])
const allTables = ref([])
const activeDbId = ref(null)
const selected = ref('')
const selectedDbId = ref(null)
const columns = ref([])
const previewData = ref([])
const previewColumns = ref([])
const rawText = ref('')
const importName = ref('')
const separator = ref('')
const importing = ref(false)
const targetDbId = ref(null)

// 数据源导入相关状态
const mode = ref('datasource')
const dsList = ref([])
const selectedDs = ref('')
const dsTables = ref([])
const checkedTables = ref([])
const dsLoading = ref(false)

// 新建库对话框
const dbDialogVisible = ref(false)
const newDbName = ref('')
const newDbKey = ref('')
const newDbRemark = ref('')
const creating = ref(false)

// 文件上传（M3）
const uploadRef = ref(null)
const uploadFile = ref(null)
const uploadTableName = ref('')
const uploading = ref(false)

// 审计日志（M3）
const auditVisible = ref(false)
const auditList = ref([])
const auditLoading = ref(false)

const targetDbName = computed(() => {
  const d = dbs.value.find((x) => x.id === targetDbId.value)
  return d ? `${d.name}（${d.dbKey}）` : ''
})

// 按库分组的表列表
const grouped = computed(() =>
  dbs.value.map((db) => ({
    db,
    tables: allTables.value.filter((t) => t.dbId === db.id)
  }))
)

async function reloadAll() {
  await Promise.all([loadDbs(), loadTables()])
}
async function loadDbs() {
  try {
    const list = await listSandboxDbs()
    dbs.value = Array.isArray(list) ? list : []
    if (targetDbId.value == null && dbs.value.length > 0) {
      targetDbId.value = dbs.value[0].id
    }
  } catch (e) {
    dbs.value = []
  }
}
async function loadTables() {
  try {
    const list = await listSandboxTables()
    allTables.value = Array.isArray(list) ? list : []
  } catch (e) {
    allTables.value = []
  }
}

async function loadDatasources() {
  try {
    dsList.value = await listDatasources()
  } catch (e) {
    dsList.value = []
  }
}

async function onDsChange(id) {
  checkedTables.value = []
  dsTables.value = []
  if (!id) return
  dsLoading.value = true
  try {
    dsTables.value = await listTables(id)
  } catch (e) {
    dsTables.value = []
  } finally {
    dsLoading.value = false
  }
}

async function doImportDs() {
  if (!selectedDs.value) {
    ElMessage.warning('请先选择数据源')
    return
  }
  if (checkedTables.value.length === 0) {
    ElMessage.warning('请至少勾选一张表')
    return
  }
  if (targetDbId.value == null) {
    ElMessage.warning('请先选择目标沙箱库')
    return
  }
  importing.value = true
  try {
    const res = await importSandboxFromDatasource({
      datasourceId: selectedDs.value,
      tables: checkedTables.value,
      dbId: targetDbId.value
    })
    const ok = Array.isArray(res) ? res : []
    ElMessage.success(`导入成功：${ok.length} 张表，${ok.map(r => r.tableName).join('、')}`)
    checkedTables.value = []
    await loadTables()
    if (ok.length > 0) {
      selectTable(ok[0].tableName, targetDbId.value)
    }
  } catch (e) {
    // http 拦截器已提示
  } finally {
    importing.value = false
  }
}

async function selectTable(physicalName, dbId) {
  selected.value = physicalName
  selectedDbId.value = dbId
  columns.value = []
  previewData.value = []
  previewColumns.value = []
  try {
    const cols = await getSandboxColumns(physicalName, dbId)
    columns.value = Array.isArray(cols) ? cols : []
    const data = await getSandboxData(physicalName, 100, dbId)
    previewData.value = (data && data.data) || []
    previewColumns.value = (data && data.columns) || []
  } catch (e) {
    // 错误已由 http 拦截器统一提示
  }
}

/** 预览数据表头：优先显示中文列标签（label），回退物理列名 */
function colLabelOf(name) {
  const found = columns.value.find((c) => c.columnName === name)
  return found && found.label ? found.label : name
}

async function doImport() {
  if (!rawText.value.trim()) {
    ElMessage.warning('请先粘贴数据')
    return
  }
  if (!importName.value.trim()) {
    ElMessage.warning('请填写表名')
    return
  }
  if (targetDbId.value == null) {
    ElMessage.warning('请先选择目标沙箱库')
    return
  }
  importing.value = true
  try {
    const res = await importSandboxText({
      rawText: rawText.value,
      tableName: importName.value.trim(),
      separator: separator.value,
      dbId: targetDbId.value
    })
    ElMessage.success(`导入成功：${res.tableName}，共 ${res.rowCount} 行`)
    rawText.value = ''
    importName.value = ''
    await loadTables()
    if (res.tableName) {
      selectTable(res.tableName, targetDbId.value)
    }
  } catch (e) {
    // http 拦截器已提示
  } finally {
    importing.value = false
  }
}

async function removeTable(physicalName) {
  try {
    await dropSandboxTable(physicalName)
    ElMessage.success(`已删除 ${physicalName}`)
    if (selected.value === physicalName) {
      selected.value = ''
      selectedDbId.value = null
      columns.value = []
      previewData.value = []
      previewColumns.value = []
    }
    await loadTables()
  } catch (e) {
    // http 拦截器已提示
  }
}

// ===== M3：文件上传导入 =====

function onFileChange(file) {
  // el-upload 的 on-change 回调：file.raw 为原始 File 对象
  const raw = file && file.raw ? file.raw : file
  const name = (raw && raw.name) || ''
  const ok = /\.(csv|xlsx|xls)$/i.test(name)
  if (!ok) {
    ElMessage.warning('仅支持 .csv / .xlsx / .xls 文件')
    uploadFile.value = null
    if (uploadRef.value) uploadRef.value.clearFiles()
    return
  }
  uploadFile.value = raw
  // 未手动填表名时，用文件名（去扩展名）自动填充
  if (!uploadTableName.value.trim()) {
    uploadTableName.value = name.replace(/\.[^.]+$/, '')
  }
}

async function doUpload() {
  if (!uploadFile.value) {
    ElMessage.warning('请先选择文件')
    return
  }
  if (targetDbId.value == null) {
    ElMessage.warning('请先选择目标沙箱库')
    return
  }
  uploading.value = true
  try {
    const res = await importSandboxFile(uploadFile.value, uploadTableName.value.trim(), targetDbId.value)
    ElMessage.success(`导入成功：${res.tableName || ''}，共 ${res.rowCount || 0} 行`)
    uploadFile.value = null
    uploadTableName.value = ''
    if (uploadRef.value) uploadRef.value.clearFiles()
    await loadTables()
    const tName = res.tableName
    if (tName) {
      const dbId = targetDbId.value
      selectTable(tName, dbId)
    }
  } catch (e) {
    // http 拦截器已提示
  } finally {
    uploading.value = false
  }
}

// ===== M3：审计日志 =====

async function openAudit() {
  auditVisible.value = true
  await loadAudit()
}

async function loadAudit() {
  auditLoading.value = true
  try {
    const list = await listSandboxAudit(100)
    auditList.value = Array.isArray(list) ? list : []
  } catch (e) {
    auditList.value = []
  } finally {
    auditLoading.value = false
  }
}

async function renameTable(t) {
  try {
    const { value } = await ElMessageBox.prompt(
      '请输入该表的显示名（如 部门表、员工表，可中文）',
      '修改显示名',
      {
        inputValue: t.displayName || '',
        confirmButtonText: '保存',
        cancelButtonText: '取消',
        inputValidator: (v) => (v && v.trim().length > 0) || '显示名不能为空'
      }
    )
    await updateSandboxDisplayName(t.physicalName, value.trim())
    ElMessage.success('显示名已更新')
    await loadTables()
  } catch (e) {
    // 取消或 http 拦截器已提示
  }
}

async function removeDb(db) {
  try {
    await ElMessageBox.confirm(
      `确定删除沙箱库「${db.name}」？该库下所有表及数据将被一并删除（不可恢复）。`,
      '删除确认',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await dropSandboxDb(db.id)
    ElMessage.success(`已删除沙箱库 ${db.name}`)
    if (selectedDbId.value === db.id) {
      selected.value = ''
      selectedDbId.value = null
    }
    await reloadAll()
  } catch (e) {
    // http 拦截器已提示
  }
}

function openCreateDb() {
  newDbName.value = ''
  newDbKey.value = ''
  newDbRemark.value = ''
  dbDialogVisible.value = true
}

async function createDb() {
  if (!newDbName.value.trim()) {
    ElMessage.warning('请填写库名称')
    return
  }
  if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(newDbKey.value.trim())) {
    ElMessage.warning('英文标识须为合法标识符（英文/数字/下划线，首字符非数字）')
    return
  }
  creating.value = true
  try {
    await createSandboxDb({ name: newDbName.value.trim(), dbKey: newDbKey.value.trim(), remark: newDbRemark.value })
    ElMessage.success(`沙箱库「${newDbName.value.trim()}」创建成功`)
    dbDialogVisible.value = false
    await loadDbs()
  } catch (e) {
    // http 拦截器已提示
  } finally {
    creating.value = false
  }
}

function analyzeInChat() {
  if (!selected.value || selectedDbId.value == null) {
    ElMessage.warning('请先选择一张表')
    return
  }
  // 锁定具体沙箱库（datasourceId = -dbId），跳转到对话页
  localStorage.setItem('bi_ds_id', -(selectedDbId.value))
  router.push('/chat')
}

onMounted(() => {
  reloadAll()
  loadDatasources()
})
</script>

<style scoped>
.sandbox {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.sb-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.sb-title {
  font-size: 18px;
  font-weight: 700;
}
.sb-sub {
  font-size: 13px;
  color: var(--text-dim);
}
.sb-body {
  flex: 1;
  display: flex;
  gap: 14px;
  min-height: 0;
}
.glass {
  background: var(--glass-bg, rgba(255, 255, 255, 0.04));
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 14px;
  padding: 14px;
}
.sb-list {
  width: 260px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  overflow: auto;
}
.sb-list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 10px;
}
.sb-list-actions {
  display: flex;
  gap: 2px;
}
.sb-group {
  margin-bottom: 8px;
}
.sb-group-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 2px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-dim);
}
.sb-group-name {
  color: var(--text, #222);
}
.sb-group-key {
  font-family: monospace;
  font-size: 11px;
  background: var(--primary-soft, rgba(64, 158, 255, 0.12));
  color: var(--el-color-primary, #409eff);
  padding: 1px 6px;
  border-radius: 6px;
}
.sb-group-count {
  margin-left: auto;
  font-size: 12px;
}
.sb-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 7px 10px 7px 12px;
  border-radius: 8px;
  cursor: pointer;
  margin: 2px 0;
}
.sb-item:hover {
  background: var(--primary-soft, rgba(64, 158, 255, 0.1));
}
.sb-item.active {
  background: var(--primary-soft, rgba(64, 158, 255, 0.18));
  font-weight: 600;
}
.sb-item-main {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  cursor: pointer;
}
.sb-item-name {
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sb-item-sub {
  font-size: 11px;
  color: var(--text-dim);
  font-family: monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sb-item-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}
.sb-empty {
  color: var(--text-dim);
  font-size: 13px;
  text-align: center;
  padding: 20px 0;
}
.sb-empty.sm {
  padding: 6px 0;
}
.sb-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 14px;
  overflow: auto;
}
.sb-target-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.sb-label {
  font-size: 13px;
  color: var(--text-dim);
}
.sb-mode {
  align-self: flex-start;
}
.sb-ds-row {
  display: flex;
  gap: 10px;
  margin-bottom: 10px;
}
.sb-table-pick {
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 10px;
  padding: 8px;
  max-height: 320px;
  overflow: auto;
}
.sb-check-wrap {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}
.sb-check-item {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 4px 2px;
}
.sb-check-box {
  flex-shrink: 0;
}
.sb-check-remark {
  font-size: 12px;
  color: var(--text-dim);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sb-import-row {
  display: flex;
  gap: 10px;
  margin-bottom: 10px;
}
.sb-tip {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-dim);
  line-height: 1.6;
}
.sb-preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.sb-preview-name {
  font-size: 14px;
  font-weight: 600;
  font-family: monospace;
}
.sb-cols {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 10px;
}
.sb-col em {
  color: var(--text-dim);
  font-style: normal;
  margin-left: 4px;
}
.sb-head {
  position: relative;
}
.sb-audit-btn {
  margin-left: auto;
}
.sb-upload {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sb-upload-drag :deep(.el-upload),
.sb-upload-drag :deep(.el-upload-list) {
  width: 100%;
}
.sb-audit {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
}
.sb-audit-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.sb-audit-tip {
  font-size: 12px;
  color: var(--text-dim);
}
</style>
