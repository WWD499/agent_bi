<template>
  <div class="page">
    <div class="toolbar glass">
      <div class="tb-title">NL2SQL 自然语言查询</div>
      <div class="tb-form">
        <el-input
          v-model="query"
          type="textarea"
          :rows="2"
          resize="none"
          :disabled="loading"
          placeholder="用自然语言描述分析需求，例如：分析上季度各区域销售额趋势"
          @keydown.enter.exact.prevent="run"
        />
        <div class="tb-row">
          <el-select
            v-model="datasourceId"
            placeholder="选择数据源"
            class="w200"
            :disabled="loading"
            filterable
          >
            <el-option
              v-for="ds in datasources"
              :key="ds.id"
              :label="`${ds.name}（${ds.type} / ${ds.databaseName}）`"
              :value="ds.id"
            />
          </el-select>
          <el-select
            v-model="tableName"
            placeholder="目标表名（可选）"
            class="w200"
            :disabled="loading"
            filterable
            clearable
            :loading="tableLoading"
          >
            <el-option
              v-for="t in tables"
              :key="t.tableName"
              :label="t.remarks ? `${t.tableName}（${t.remarks}）` : t.tableName"
              :value="t.tableName"
            />
          </el-select>
          <el-button type="primary" :loading="loading" :icon="Promotion" @click="run">分析</el-button>
          <el-button :disabled="loading" @click="reset">清空</el-button>
        </div>
      </div>
    </div>

    <div v-if="error" class="err glass">{{ error }}</div>

    <!-- 查询进行中：提示正在执行「数据探查前置 + NL2SQL 生成」 -->
    <div v-if="loading && !result" class="probe-loading glass">
      <span class="dot"></span> 正在探查数据分布并生成查询…
    </div>

    <template v-if="result">
      <div class="card glass">
        <div class="card-h">生成的 SQL</div>
        <pre class="code-box">{{ result.sql }}</pre>
      </div>

      <!-- 数据探查概览（DataProfile.toSummary()）：展示真实覆盖区间、关键枚举取值、行数 -->
      <div v-if="result.dataProfileSummary" class="card glass">
        <div class="card-h">
          数据探查概览
          <span class="tag">{{ result.probeSkipped ? '已降级跳过' : '已探查' }}</span>
        </div>
        <div class="probe-summary">{{ result.dataProfileSummary }}</div>
      </div>

      <!-- 0 行结果：按是否执行过探查给出不同友好说明，避免「SQL 正确却空白」的困惑 -->
      <div v-if="result.rowCount === 0" class="card glass zero-row">
        <div class="card-h">查询结果为空</div>
        <p v-if="result.probeSkipped" class="zero-tip">
          本次未执行数据探查（已自动降级）。可能原因：数据源连接超时或探查异常。
          请稍后重试，或检查数据源连接后再次分析。
        </p>
        <p v-else class="zero-tip">
          已基于<b>真实数据覆盖区间</b>重新生成查询，但当前条件仍无匹配数据。
          可尝试放宽时间范围、调整筛选条件，或更换目标表。
        </p>
      </div>

      <!-- 图表卡片：table 类型跳过（ECharts 无 table 系列，下方 el-table 已展示明细数据） -->
      <template v-if="chartOption && result?.chartType !== 'table'">
        <div class="card glass">
          <div class="card-h">
            {{ result.chartName || '可视化图表' }}
            <span class="tag">{{ result.chartType }}</span>
          </div>
          <ChartBlock :option="chartOption" />
        </div>
      </template>
      <div v-else-if="result?.chartType === 'table'" class="card glass" style="color:var(--text-dim);font-size:13px">
        数据以表格形式展示（见下方「数据明细」）
      </div>

      <div class="card glass">
        <div class="card-h">
          数据明细
          <span class="tag">共 {{ result.rowCount }} 行</span>
        </div>
        <el-table :data="tableData" border stripe max-height="360" empty-text="无数据">
          <el-table-column
            v-for="col in result.columns"
            :key="col"
            :prop="col"
            :label="col"
            show-overflow-tooltip
          />
        </el-table>
      </div>

      <div v-if="result.interpretation" class="card glass">
        <div class="card-h">分析解读</div>
        <div class="interpret">{{ result.interpretation }}</div>
      </div>
    </template>

    <div v-else-if="!loading && !error" class="empty glass">
      <div class="ph-icon">📊</div>
      <p>输入分析需求，让 BI Agent 把自然语言转成 SQL 并生成可视化图表。</p>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { Promotion } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { nl2sql } from '@/api/query'
import { listDatasources, listTables } from '@/api/datasource'
import ChartBlock from '@/components/ChartBlock.vue'

const query = ref('')
const datasourceId = ref(null)
const datasources = ref([])
const tableName = ref('')
const tables = ref([])
const tableLoading = ref(false)
const loading = ref(false)
const error = ref('')
const result = ref(null)

// 按数据源加载表列表，填充「目标表名」下拉（排除 bi_ 系统表，与 Agent 的 list_tables 口径一致）
async function loadTables(dsId) {
  if (dsId == null) {
    tables.value = []
    return
  }
  tableLoading.value = true
  try {
    const list = await listTables(dsId)
    tables.value = (Array.isArray(list) ? list : [])
      .filter((t) => !t.tableName || !t.tableName.toLowerCase().startsWith('bi_'))
  } catch {
    // 表列表加载失败不阻塞页面，仅留空让用户手填
    tables.value = []
  } finally {
    tableLoading.value = false
  }
}

// 进入页面即拉取数据源列表，并默认选中第一个，避免用户漏填导致后端 400
onMounted(async () => {
  try {
    const list = await listDatasources()
    datasources.value = Array.isArray(list) ? list : []
    if (datasources.value.length > 0) {
      datasourceId.value = datasources.value[0].id
      await loadTables(datasourceId.value)
    }
  } catch {
    // 下拉加载失败不阻塞页面，仅留空让用户手动处理
    datasources.value = []
  }
})

// 数据源切换时联动：清空已选表名并重新拉取该数据源的表列表
watch(datasourceId, (id) => {
  tableName.value = ''
  loadTables(id)
})

// data 是 List<JSONObject>，axios 解析后已是普通对象数组，直接喂给 el-table
const tableData = computed(() => {
  const d = result.value?.data
  if (!Array.isArray(d)) return []
  return d
})

// echartsOption 是后端直接生成的完整 ECharts 配置；克隆一份避免响应式副作用
const chartOption = computed(() => {
  const opt = result.value?.echartsOption
  if (!opt) return null
  try {
    return JSON.parse(JSON.stringify(opt))
  } catch {
    return null
  }
})

async function run() {
  const q = query.value.trim()
  if (!q) {
    ElMessage.warning('请输入分析需求')
    return
  }
  if (datasourceId.value == null) {
    ElMessage.warning('请先选择数据源')
    return
  }
  loading.value = true
  error.value = ''
  result.value = null
  try {
    const req = { query: q, datasourceId: datasourceId.value }
    if (tableName.value.trim()) req.tableName = tableName.value.trim()
    result.value = await nl2sql(req)
  } catch (e) {
    error.value = e?.response?.data?.msg || e.message || '查询失败'
  } finally {
    loading.value = false
  }
}

function reset() {
  query.value = ''
  tableName.value = ''
  result.value = null
  error.value = ''
  // 恢复默认选中的第一个数据源
  datasourceId.value = datasources.value.length > 0 ? datasources.value[0].id : null
}
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.toolbar {
  padding: 16px 18px;
}
.tb-title {
  font-size: 16px;
  font-weight: 700;
  margin-bottom: 12px;
}
.tb-form {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.tb-row {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}
.w200 {
  width: 200px;
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
  gap: 10px;
}
.tag {
  font-size: 12px;
  font-weight: 500;
  color: var(--primary);
  background: var(--primary-soft);
  padding: 2px 8px;
  border-radius: 999px;
}
.interpret {
  font-size: 14px;
  line-height: 1.75;
  color: var(--text);
  white-space: pre-wrap;
}
.err {
  padding: 14px 16px;
  color: var(--error);
  font-size: 13.5px;
}
.probe-loading {
  padding: 18px 20px;
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--text);
  font-size: 14px;
}
.probe-loading .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--primary);
  animation: pulse 1s ease-in-out infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 0.3; transform: scale(0.8); }
  50% { opacity: 1; transform: scale(1.2); }
}
.probe-summary {
  font-size: 13.5px;
  line-height: 1.8;
  color: var(--text);
  white-space: pre-wrap;
}
.zero-row .zero-tip {
  font-size: 13.5px;
  line-height: 1.75;
  color: var(--text-dim);
  margin: 0;
}
.zero-row .zero-tip b {
  color: var(--primary);
}
.empty {
  padding: 50px 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  color: var(--text-dim);
  text-align: center;
}
.ph-icon {
  font-size: 48px;
}
</style>
