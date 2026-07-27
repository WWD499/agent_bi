<template>
  <!-- ==================== 列表模式 ==================== -->
  <div v-if="viewMode === 'list'" class="dash-list-page">
    <div class="list-head">
      <div class="list-title">
        <el-icon><DataAnalysis /></el-icon>
        <span>BI 数据大屏</span>
      </div>
      <div class="list-search">
        <el-input
          v-model="query.name"
          placeholder="按名称搜索"
          clearable
          style="width: 220px"
          @keyup.enter="fetchList"
          @clear="fetchList"
        />
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 120px" @change="fetchList">
          <el-option label="启用" value="1" />
          <el-option label="停用" value="0" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="fetchList">搜索</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建大屏</el-button>
        <el-button type="danger" :icon="Delete" :disabled="!selectedIds.length" @click="batchRemove">
          批量删除
        </el-button>
      </div>
    </div>

    <el-table
      v-loading="listLoading"
      :data="dashList"
      stripe
      style="width: 100%"
      @selection-change="onSelectionChange"
    >
      <el-table-column type="selection" width="48" />
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="大屏名称" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          <el-link type="primary" @click="openDesigner(row, 'preview')">{{ row.name }}</el-link>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === '1' ? 'success' : 'info'">
            {{ row.status === '1' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column prop="updateTime" label="更新时间" width="170">
        <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="300" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" text :icon="EditPen" @click="openDesigner(row, 'edit')">设计</el-button>
          <el-button size="small" type="success" text :icon="View" @click="openDesigner(row, 'preview')">预览</el-button>
          <el-button size="small" text :icon="Setting" @click="openInfoEdit(row)">信息</el-button>
          <el-button size="small" text :icon="CopyDocument" @click="copyOne(row)">复制</el-button>
          <el-button size="small" type="danger" text :icon="Delete" @click="removeOne(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="还没有大屏，点击「新建大屏」开始搭建" />
      </template>
    </el-table>
  </div>

  <!-- ==================== 编辑器 / 预览模式 ==================== -->
  <div v-else ref="screen" class="screen" :class="{ 'is-edit': viewMode === 'edit' }">
    <header class="topbar">
      <div class="title">
        <el-button v-if="!isShare" text :icon="Back" class="back-btn" @click="backToList">返回</el-button>
        <el-icon class="title-icon"><DataAnalysis /></el-icon>
        <span>{{ current.name || 'BI 数据大屏' }}</span>
        <el-tag v-if="viewMode === 'edit'" type="warning" size="small" class="mode-tag">编辑中</el-tag>
        <el-tag v-if="isShare" type="info" size="small" class="mode-tag">分享只读</el-tag>
      </div>
      <div class="top-right">
        <span class="clock">{{ clock }}</span>
        <el-select
          v-if="viewMode === 'edit'"
          v-model="datasourceId"
          placeholder="选择数据源"
          class="ds-select"
          :disabled="loading"
          @change="refreshAll"
        >
          <el-option v-for="d in datasources" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>

        <template v-if="viewMode === 'edit'">
          <el-button type="primary" :icon="Plus" @click="openAddChart">添加图表</el-button>
          <el-button :icon="Picture" @click="openAddImage">添加图片</el-button>
          <el-button type="success" :icon="Check" :loading="saving" @click="saveDashboard">保存</el-button>
          <el-button :icon="View" @click="switchMode('preview')">预览</el-button>
        </template>
        <template v-else>
          <el-switch
            v-model="autoRefresh"
            inline-prompt
            active-text="自动刷新"
            inactive-text="手动"
            @change="onAutoRefreshChange"
          />
          <el-button :icon="Refresh" :loading="loading" @click="refreshAll">刷新</el-button>
          <el-button v-if="!isShare" :icon="EditPen" @click="switchMode('edit')">编辑</el-button>
          <el-button :icon="FullScreen" @click="toggleFullscreen">全屏</el-button>
          <el-divider direction="vertical" />
          <el-button :icon="Download" :loading="exporting" @click="exportImage">保存图片</el-button>
          <el-button :icon="Document" :loading="exporting" @click="exportPdf">保存 PDF</el-button>
        </template>
      </div>
    </header>

    <div v-if="viewMode === 'edit'" class="edit-toolbar">
      <span class="hint">拖动卡片可移动位置，拖右下角可缩放；「保存」后布局与图表配置写入服务器</span>
    </div>

    <!-- grid-layout 拖拽网格 -->
    <GridLayout
      v-model:layout="layout"
      :col-num="12"
      :row-height="34"
      :margin="[14, 14]"
      :is-draggable="viewMode === 'edit'"
      :is-resizable="viewMode === 'edit'"
      :vertical-compact="true"
      :use-css-transforms="true"
      class="grid-wrap"
    >
      <GridItem
        v-for="item in layout"
        :key="item.i"
        :i="item.i"
        :x="item.x"
        :y="item.y"
        :w="item.w"
        :h="item.h"
        :min-w="2"
        :min-h="2"
        @resized="onItemResized"
      >
        <!-- 图片 Widget -->
        <div v-if="widgetOf(item.i).type === 'image'" class="widget image-widget">
          <img
            v-if="widgetOf(item.i).image && widgetOf(item.i).image.src"
            :src="widgetOf(item.i).image.src"
            :style="{ objectFit: widgetOf(item.i).image.fit || 'contain' }"
            alt=""
          />
          <el-empty v-else description="未设置图片" :image-size="60" />
          <div v-if="viewMode === 'edit'" class="widget-actions">
            <el-button size="small" text :icon="EditPen" @click="openEditImage(item.i)" />
            <el-button size="small" text type="danger" :icon="Delete" @click="removeWidget(item.i)" />
          </div>
        </div>

        <!-- KPI 数字卡片 Widget -->
        <div v-else-if="widgetOf(item.i).chartType === 'stat'" class="widget kpi-card">
          <template v-if="runtimeOf(item.i).loading">
            <el-icon class="spin"><Loading /></el-icon>
          </template>
          <template v-else-if="runtimeOf(item.i).error">
            <el-icon class="err"><Warning /></el-icon>
            <div class="kpi-err">查询失败</div>
          </template>
          <template v-else>
            <div class="kpi-label">{{ widgetOf(item.i).title }}</div>
            <div class="kpi-value">{{ formatNumber(statValue(item.i)) }}</div>
            <div class="kpi-sub">{{ statSub(item.i) }}</div>
          </template>
          <div v-if="viewMode === 'edit'" class="widget-actions">
            <el-button size="small" text :icon="EditPen" @click="openEditChart(item.i)" />
            <el-button size="small" text type="danger" :icon="Delete" @click="removeWidget(item.i)" />
          </div>
        </div>

        <!-- 图表 Widget -->
        <div v-else class="widget chart-card">
          <div class="chart-head">
            <span class="chart-title">{{ widgetOf(item.i).title }}</span>
            <span class="chart-type-tag">{{ chartTypeLabel(widgetOf(item.i).chartType) }}</span>
            <div v-if="viewMode === 'edit'" class="chart-head-actions">
              <el-button size="small" text :icon="EditPen" @click="openEditChart(item.i)" />
              <el-button size="small" text type="danger" :icon="Delete" @click="removeWidget(item.i)" />
            </div>
          </div>
          <div class="chart-body">
            <el-icon v-if="runtimeOf(item.i).loading" class="spin big"><Loading /></el-icon>
            <el-alert
              v-else-if="runtimeOf(item.i).error"
              :title="'查询失败：' + runtimeOf(item.i).error"
              type="error"
              :closable="false"
              class="panel-error"
            />
            <ChartBlock
              v-else-if="runtimeOf(item.i).result && runtimeOf(item.i).result.echartsOption"
              :option="runtimeOf(item.i).result.echartsOption"
            />
            <el-empty v-else description="暂无数据" :image-size="60" />
          </div>
        </div>
      </GridItem>
    </GridLayout>

    <div v-if="!layout.length" class="empty-state">
      <el-empty
        :description="viewMode === 'edit' ? '空白大屏，点击「添加图表」或「添加图片」开始搭建' : '该大屏还没有内容，点击「编辑」开始搭建'"
      />
    </div>
  </div>

  <!-- ==================== 基本信息弹窗（新建 / 编辑信息） ==================== -->
  <el-dialog v-model="infoDialogVisible" :title="infoForm.id ? '编辑大屏信息' : '新建大屏'" width="520px">
    <el-form label-width="90px">
      <el-form-item label="大屏名称" required>
        <el-input v-model="infoForm.name" placeholder="如：销售数据总览" maxlength="100" />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="infoForm.description" type="textarea" :rows="3" maxlength="500" placeholder="可选" />
      </el-form-item>
      <el-form-item label="状态">
        <el-switch v-model="infoForm.status" active-value="1" inactive-value="0" active-text="启用" inactive-text="停用" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="infoDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="saveInfo">{{ infoForm.id ? '保存' : '创建并设计' }}</el-button>
    </template>
  </el-dialog>

  <!-- ==================== 图表配置弹窗 ==================== -->
  <el-dialog v-model="chartDialogVisible" :title="editingWidgetId ? '编辑图表' : '添加图表'" width="720px">
    <el-form label-width="96px">
      <el-form-item label="标题">
        <el-input v-model="form.title" placeholder="如：各区域销售额" />
      </el-form-item>

      <el-form-item label="图表类型">
        <el-select v-model="form.chartType" placeholder="选择类型" style="width: 100%">
          <el-option label="自动（按数据智能选）" value="" />
          <el-option label="数字卡片（KPI）" value="stat" />
          <el-option label="折线图" value="line" />
          <el-option label="柱状图" value="bar" />
          <el-option label="饼图" value="pie" />
          <el-option label="散点图" value="scatter" />
          <el-option label="雷达图" value="radar" />
          <el-option label="热力图" value="heatmap" />
          <el-option label="表格" value="table" />
        </el-select>
      </el-form-item>

      <el-form-item label="配置方式">
        <el-radio-group v-model="form.mode">
          <el-radio value="config">可视化配置</el-radio>
          <el-radio value="sql">手写 SQL</el-radio>
        </el-radio-group>
      </el-form-item>

      <template v-if="form.mode === 'config'">
        <el-divider content-position="left">数据配置（选表 / 字段 / 聚合，无需写 SQL）</el-divider>

        <el-form-item label="数据表">
          <el-select
            v-model="form.config.tableName"
            placeholder="选择表"
            style="width: 100%"
            :loading="loadingTables"
            @change="onTableChange"
          >
            <el-option
              v-for="t in tableOptions"
              :key="t.tableName"
              :label="t.remarks ? t.tableName + '（' + t.remarks + '）' : t.tableName"
              :value="t.tableName"
            />
          </el-select>
        </el-form-item>

        <el-form-item v-if="form.chartType !== 'stat'" label="维度字段">
          <el-select
            v-model="form.config.dimensions"
            multiple
            collapse-tags
            placeholder="选择分组维度（如区域、月份）"
            style="width: 100%"
            :loading="loadingCols"
          >
            <el-option
              v-for="c in columnOptions"
              :key="c.columnName"
              :label="c.remarks ? c.columnName + '（' + c.remarks + '）' : c.columnName"
              :value="c.columnName"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="数值指标">
          <div class="metrics-editor">
            <div v-for="(m, idx) in form.config.metrics" :key="idx" class="metric-row">
              <el-select v-model="m.column" placeholder="字段" style="width: 42%" :loading="loadingCols" @change="onMetricColumnChange(m)">
                <el-option
                  v-for="c in columnOptions"
                  :key="c.columnName"
                  :label="c.remarks ? c.columnName + '（' + c.remarks + '）' : c.columnName"
                  :value="c.columnName"
                />
              </el-select>
              <el-select v-model="m.agg" placeholder="聚合" style="width: 30%">
                <el-option label="求和 SUM" value="SUM" />
                <el-option label="计数 COUNT" value="COUNT" />
                <el-option label="平均 AVG" value="AVG" />
                <el-option label="最小 MIN" value="MIN" />
                <el-option label="最大 MAX" value="MAX" />
              </el-select>
              <el-input v-model="m.alias" placeholder="别名(可选)" style="width: 20%" />
              <el-button size="small" type="danger" text :icon="Delete" @click="removeMetric(idx)" />
            </div>
            <el-button size="small" :icon="Plus" @click="addMetric">添加指标</el-button>
          </div>
        </el-form-item>

        <el-form-item v-if="form.chartType !== 'stat'" label="排序字段">
          <el-select v-model="form.config.orderBy" clearable placeholder="可选，按某字段排序" style="width: 55%" :loading="loadingCols">
            <el-option v-for="c in columnOptions" :key="c.columnName" :label="c.columnName" :value="c.columnName" />
          </el-select>
          <el-select v-model="form.config.orderDir" style="width: 40%; margin-left: 5%">
            <el-option label="升序 ASC" value="ASC" />
            <el-option label="降序 DESC" value="DESC" />
          </el-select>
        </el-form-item>

        <el-form-item v-if="form.chartType !== 'stat'" label="行数上限">
          <el-input-number v-model="form.config.limit" :min="1" :max="1000" :step="10" />
        </el-form-item>

        <el-alert
          v-if="previewSql"
          :title="'将执行的 SQL：' + previewSql"
          type="info"
          :closable="false"
          class="sql-preview"
        />
      </template>

      <template v-if="form.mode === 'sql'">
        <el-form-item label="SQL 语句">
          <el-input
            v-model="form.sql"
            type="textarea"
            :rows="6"
            placeholder="SELECT region, SUM(amount) AS 销售额 FROM fact_sales_order GROUP BY region"
          />
        </el-form-item>
        <el-alert
          type="warning"
          :closable="false"
          title="手写 SQL 将直接执行（仅允许 SELECT/WITH，表必须存在于当前数据源）。"
        />
      </template>
    </el-form>
    <template #footer>
      <el-button @click="chartDialogVisible = false">取消</el-button>
      <el-button :loading="testing" :icon="Refresh" @click="testWidget">试跑预览</el-button>
      <el-button type="primary" @click="saveChartWidget">确定</el-button>
    </template>
  </el-dialog>

  <!-- ==================== 图片配置弹窗 ==================== -->
  <el-dialog v-model="imageDialogVisible" :title="editingWidgetId ? '编辑图片' : '添加图片'" width="520px">
    <el-form label-width="90px">
      <el-form-item label="上传图片">
        <input ref="imgFileInput" type="file" accept="image/*" style="display: none" @change="onImageFile" />
        <el-button :icon="Upload" @click="imgFileInput && imgFileInput.click()">选择本地图片</el-button>
        <span class="img-hint">支持 png / jpg / gif，≤ 2MB，以 base64 保存进大屏配置</span>
      </el-form-item>
      <el-form-item label="或图片URL">
        <el-input v-model="imageForm.url" placeholder="https://... 网络图片地址" clearable />
      </el-form-item>
      <el-form-item label="填充方式">
        <el-select v-model="imageForm.fit" style="width: 100%">
          <el-option label="完整显示（contain）" value="contain" />
          <el-option label="裁剪铺满（cover）" value="cover" />
          <el-option label="拉伸填充（fill）" value="fill" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="imagePreviewSrc" label="预览">
        <img :src="imagePreviewSrc" class="img-preview" alt="" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="imageDialogVisible = false">取消</el-button>
      <el-button type="primary" @click="saveImageWidget">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import {
  DataAnalysis, Refresh, FullScreen, EditPen, Plus, Delete, Loading, Warning,
  Download, Document, Search, View, Setting, CopyDocument, Back, Check, Picture, Upload
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { GridLayout, GridItem } from 'grid-layout-plus'
import { listDatasources, listTables, listColumns } from '@/api/datasource'
import {
  listDashboards, getDashboard, createDashboard, updateDashboard,
  deleteDashboards, copyDashboard, runDashboardQuery, shareDashboard, shareQuery
} from '@/api/dashboard'
import ChartBlock from '@/components/ChartBlock.vue'
import html2canvas from 'html2canvas'
import { jsPDF } from 'jspdf'

// ==================== 视图状态 ====================
// list：大屏列表；edit：拖拽编辑器；preview：预览大屏
const viewMode = ref('list')
// 是否为「分享只读」模式（?token= 进入，免登录）
const route = useRoute()
const isShare = ref(false)

// ==================== 列表模式 ====================
const listLoading = ref(false)
const dashList = ref([])
const query = reactive({ name: '', status: '' })
const selectedIds = ref([])

async function fetchList() {
  listLoading.value = true
  try {
    dashList.value = await listDashboards({
      name: query.name || undefined,
      status: query.status || undefined
    })
  } catch (e) {
    dashList.value = []
  } finally {
    listLoading.value = false
  }
}

function onSelectionChange(rows) {
  selectedIds.value = rows.map((r) => r.id)
}

function formatTime(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 19)
}

async function removeOne(row) {
  try {
    await ElMessageBox.confirm(`确定删除大屏「${row.name}」？删除后不可恢复。`, '删除确认', { type: 'warning' })
  } catch (e) { return }
  const res = await deleteDashboards([row.id])
  if (res && res.code === 200) {
    ElMessage.success('已删除')
    fetchList()
  } else {
    ElMessage.error((res && res.msg) || '删除失败')
  }
}

async function batchRemove() {
  if (!selectedIds.value.length) return
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${selectedIds.value.length} 个大屏？删除后不可恢复。`, '批量删除', { type: 'warning' })
  } catch (e) { return }
  const res = await deleteDashboards(selectedIds.value)
  if (res && res.code === 200) {
    ElMessage.success('已删除')
    fetchList()
  } else {
    ElMessage.error((res && res.msg) || '删除失败')
  }
}

async function copyOne(row) {
  const res = await copyDashboard(row.id)
  if (res && res.code === 200) {
    ElMessage.success('已复制为「' + row.name + '_副本」')
    fetchList()
  } else {
    ElMessage.error((res && res.msg) || '复制失败')
  }
}

// ==================== 基本信息弹窗 ====================
const infoDialogVisible = ref(false)
const saving = ref(false)
const infoForm = reactive({ id: null, name: '', description: '', status: '1' })

function openCreate() {
  infoForm.id = null
  infoForm.name = ''
  infoForm.description = ''
  infoForm.status = '1'
  infoDialogVisible.value = true
}

function openInfoEdit(row) {
  infoForm.id = row.id
  infoForm.name = row.name
  infoForm.description = row.description || ''
  infoForm.status = row.status || '1'
  infoDialogVisible.value = true
}

async function saveInfo() {
  if (!infoForm.name.trim()) { ElMessage.warning('请填写大屏名称'); return }
  saving.value = true
  try {
    if (infoForm.id) {
      const res = await updateDashboard({
        id: infoForm.id,
        name: infoForm.name.trim(),
        description: infoForm.description,
        status: infoForm.status
      })
      if (res && res.code === 200) {
        ElMessage.success('已保存')
        infoDialogVisible.value = false
        fetchList()
      } else {
        ElMessage.error((res && res.msg) || '保存失败')
      }
    } else {
      const res = await createDashboard({
        name: infoForm.name.trim(),
        description: infoForm.description,
        status: infoForm.status,
        configJson: JSON.stringify({ datasourceId: null, widgets: [] })
      })
      if (res && res.code === 200) {
        ElMessage.success('创建成功，进入设计模式')
        infoDialogVisible.value = false
        await fetchList()
        // 直接进入设计器
        openDesigner({ id: res.data, name: infoForm.name.trim() }, 'edit')
      } else {
        ElMessage.error((res && res.msg) || '创建失败')
      }
    }
  } finally {
    saving.value = false
  }
}

// ==================== 编辑器 / 预览 ====================
const screen = ref(null)
const clock = ref('')
const datasourceId = ref(null)
const datasources = ref([])
const loading = ref(false)
const autoRefresh = ref(false)
const exporting = ref(false)

// 当前打开的大屏
const current = reactive({ id: null, name: '', description: '', status: '1' })

// grid 布局（仅坐标）与 widget 配置（业务），i 关联两者
const layout = ref([])
const widgets = ref([])
// 运行时状态（loading / error / result），不持久化
const runtime = reactive({})

let refreshTimer = null
let clockTimer = null

function nid() {
  return 'w_' + Math.random().toString(36).slice(2, 9)
}

function widgetOf(i) {
  return widgets.value.find((w) => w.i === i) || {}
}

function runtimeOf(i) {
  if (!runtime[i]) runtime[i] = { loading: false, error: '', result: null }
  return runtime[i]
}

async function openDesigner(row, mode) {
  viewMode.value = mode
  current.id = row.id
  current.name = row.name
  layout.value = []
  widgets.value = []
  Object.keys(runtime).forEach((k) => delete runtime[k])
  try {
    const detail = await getDashboard(row.id)
    current.name = detail.name
    current.description = detail.description
    current.status = detail.status
    if (detail.configJson) {
      const cfg = JSON.parse(detail.configJson)
      datasourceId.value = cfg.datasourceId || datasourceId.value
      const ws = cfg.widgets || []
      widgets.value = ws.map((w) => ({ ...w }))
      layout.value = ws.map((w) => ({ i: w.i, x: w.x ?? 0, y: w.y ?? 0, w: w.w ?? 4, h: w.h ?? 8 }))
    }
  } catch (e) {
    ElMessage.error('加载大屏配置失败')
  }
  // 默认数据源兜底
  if (!datasourceId.value && datasources.value.length) {
    datasourceId.value = datasources.value[0].id
  }
  refreshAll()
}

function backToList() {
  if (refreshTimer) { clearInterval(refreshTimer); refreshTimer = null }
  autoRefresh.value = false
  viewMode.value = 'list'
  fetchList()
}

function switchMode(mode) {
  viewMode.value = mode
  if (mode === 'preview') {
    nextTick(() => window.dispatchEvent(new Event('resize')))
  }
}

// 把 layout 坐标同步回 widgets 并序列化保存
async function saveDashboard() {
  if (!current.id) return
  saving.value = true
  try {
    const merged = widgets.value.map((w) => {
      const pos = layout.value.find((l) => l.i === w.i)
      return { ...w, x: pos ? pos.x : 0, y: pos ? pos.y : 0, w: pos ? pos.w : 4, h: pos ? pos.h : 8 }
    })
    const configJson = JSON.stringify({ datasourceId: datasourceId.value, widgets: merged })
    const res = await updateDashboard({ id: current.id, name: current.name, description: current.description, status: current.status, configJson })
    if (res && res.code === 200) {
      ElMessage.success('大屏已保存')
    } else {
      ElMessage.error((res && res.msg) || '保存失败')
    }
  } finally {
    saving.value = false
  }
}

function onItemResized() {
  window.dispatchEvent(new Event('resize'))
}

function removeWidget(i) {
  widgets.value = widgets.value.filter((w) => w.i !== i)
  layout.value = layout.value.filter((l) => l.i !== i)
  delete runtime[i]
}

// ==================== 取数 ====================
function buildReq(w) {
  const base = { datasourceId: datasourceId.value }
  if (w.mode !== 'sql' && w.config && w.config.tableName) {
    const metrics = (w.config.metrics || [])
      .filter((m) => m && m.column)
      .map((m) => ({ column: m.column, agg: (m.agg || 'SUM').toUpperCase(), alias: m.alias || '' }))
    base.tableName = w.config.tableName
    base.dimensions = w.config.dimensions || []
    base.metrics = metrics
    if (w.config.orderBy) { base.orderBy = w.config.orderBy; base.orderDir = w.config.orderDir || 'DESC' }
    if (w.config.limit) base.limit = w.config.limit
    if (w.chartType && w.chartType !== 'stat') base.chartType = w.chartType.toUpperCase()
  } else if (w.sql) {
    base.sql = w.sql
    if (w.chartType && w.chartType !== 'stat') base.chartType = w.chartType.toUpperCase()
  }
  return base
}

async function loadWidget(w, idx) {
  if (w.type === 'image') return
  const rt = runtimeOf(w.i)
  rt.loading = true
  rt.error = ''
  try {
    if (isShare.value) {
      // 分享只读：后端只按「已保存」的 widget 配置取数（忽略请求体），杜绝越权
      rt.result = await shareQuery(route.query.token, idx)
    } else {
      rt.result = await runDashboardQuery(buildReq(w))
    }
  } catch (e) {
    rt.error = (e && e.response && e.response.data && e.response.data.msg) || e.message || '查询异常'
  } finally {
    rt.loading = false
  }
}

async function refreshAll() {
  if (!isShare.value && !datasourceId.value) return
  loading.value = true
  await Promise.all(widgets.value.map((w, idx) => loadWidget(w, idx)))
  loading.value = false
}

// 分享只读模式：按 token 拉取已公开大屏配置并渲染（无编辑能力）
async function loadShare(token) {
  loading.value = true
  try {
    const d = await shareDashboard(token)
    current.name = d.name || '分享大屏'
    current.description = d.description || ''
    current.status = d.status || '1'
    if (d.configJson) {
      const cfg = JSON.parse(d.configJson)
      const ws = cfg.widgets || []
      widgets.value = ws.map((w) => ({ ...w }))
      layout.value = ws.map((w) => ({ i: w.i, x: w.x ?? 0, y: w.y ?? 0, w: w.w ?? 4, h: w.h ?? 8 }))
    }
    autoRefresh.value = true
    await refreshAll()
  } catch (e) {
    ElMessage.error('加载分享大屏失败：' + ((e && e.response && e.response.data && e.response.data.msg) || e.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

function onAutoRefreshChange(val) {
  if (refreshTimer) { clearInterval(refreshTimer); refreshTimer = null }
  if (val) {
    refreshTimer = setInterval(refreshAll, 30000)
    refreshAll()
  }
}

// ==================== 图表 Widget 配置 ====================
const chartDialogVisible = ref(false)
const editingWidgetId = ref(null)
const testing = ref(false)
const loadingTables = ref(false)
const loadingCols = ref(false)
const tableOptions = ref([])
const columnOptions = ref([])

function emptyConfig() {
  return { tableName: '', dimensions: [], metrics: [{ column: '', agg: 'SUM', alias: '' }], orderBy: '', orderDir: 'DESC', limit: 100 }
}
const form = ref({ title: '', chartType: '', mode: 'config', config: emptyConfig(), sql: '' })

const previewSql = computed(() => {
  const c = form.value.config
  if (form.value.mode !== 'config' || !c.tableName) return ''
  const items = []
  ;(c.dimensions || []).forEach((d) => items.push(d))
  ;(c.metrics || []).filter((m) => m.column).forEach((m) => {
    const agg = (m.agg || 'SUM').toUpperCase()
    const alias = m.alias ? ' AS ' + m.alias : ''
    items.push(agg + '(' + m.column + ')' + alias)
  })
  if (!items.length) return ''
  let sql = 'SELECT ' + items.join(', ') + ' FROM ' + c.tableName
  if (c.dimensions && c.dimensions.length) sql += ' GROUP BY ' + c.dimensions.join(', ')
  if (c.orderBy) sql += ' ORDER BY ' + c.orderBy + ' ' + (c.orderDir || 'DESC')
  if (c.limit) sql += ' LIMIT ' + c.limit
  return sql
})

function openAddChart() {
  editingWidgetId.value = null
  form.value = { title: '', chartType: '', mode: 'config', config: emptyConfig(), sql: '' }
  tableOptions.value = []
  columnOptions.value = []
  loadTableOptions()
  chartDialogVisible.value = true
}

function openEditChart(i) {
  const w = widgetOf(i)
  editingWidgetId.value = i
  form.value = {
    title: w.title || '',
    chartType: w.chartType || '',
    mode: w.mode === 'sql' ? 'sql' : 'config',
    config: w.config ? JSON.parse(JSON.stringify(w.config)) : emptyConfig(),
    sql: w.sql || ''
  }
  loadTableOptions().then(() => {
    if (form.value.config && form.value.config.tableName) {
      loadColumnOptions(form.value.config.tableName)
    }
  })
  chartDialogVisible.value = true
}

async function loadTableOptions() {
  if (!datasourceId.value) { ElMessage.warning('请先选择数据源'); return }
  loadingTables.value = true
  try {
    tableOptions.value = await listTables(datasourceId.value)
  } catch (e) {
    tableOptions.value = []
  } finally {
    loadingTables.value = false
  }
}

async function loadColumnOptions(tableName) {
  if (!datasourceId.value || !tableName) { columnOptions.value = []; return }
  loadingCols.value = true
  try {
    columnOptions.value = await listColumns(datasourceId.value, tableName)
  } catch (e) {
    columnOptions.value = []
  } finally {
    loadingCols.value = false
  }
}

async function onTableChange(tbl) {
  form.value.config.dimensions = []
  form.value.config.metrics = [{ column: '', agg: 'SUM', alias: '' }]
  form.value.config.orderBy = ''
  await loadColumnOptions(tbl)
}

function addMetric() {
  form.value.config.metrics.push({ column: '', agg: 'SUM', alias: '' })
}
function removeMetric(idx) {
  form.value.config.metrics.splice(idx, 1)
}
function onMetricColumnChange(m) {
  if (!m.alias) m.alias = m.column
}

async function testWidget() {
  if (!datasourceId.value) { ElMessage.warning('请先选择数据源'); return }
  const w = { chartType: form.value.chartType, mode: form.value.mode, config: form.value.config, sql: form.value.sql ? form.value.sql.trim() : '' }
  if (form.value.mode === 'sql' && !w.sql) { ElMessage.warning('请填写 SQL'); return }
  if (form.value.mode === 'config') {
    if (!w.config.tableName) { ElMessage.warning('请选择数据表'); return }
    if (!w.config.metrics || !w.config.metrics.filter((m) => m.column).length) { ElMessage.warning('请至少配置一个数值指标'); return }
  }
  testing.value = true
  try {
    const vo = await runDashboardQuery(buildReq(w))
    ElMessage.success('试跑成功，返回 ' + (vo.rowCount ?? 0) + ' 行')
  } catch (e) {
    ElMessage.error((e && e.response && e.response.data && e.response.data.msg) || e.message || '试跑失败')
  } finally {
    testing.value = false
  }
}

function saveChartWidget() {
  if (!form.value.title.trim()) { ElMessage.warning('请填写标题'); return }
  if (form.value.mode === 'sql') {
    if (!form.value.sql.trim()) { ElMessage.warning('请填写 SQL'); return }
  } else {
    const c = form.value.config
    if (!c.tableName) { ElMessage.warning('请选择数据表'); return }
    if (!c.metrics || !c.metrics.filter((m) => m.column).length) { ElMessage.warning('请至少配置一个数值指标'); return }
  }

  if (editingWidgetId.value) {
    const w = widgets.value.find((x) => x.i === editingWidgetId.value)
    if (w) {
      w.title = form.value.title.trim()
      w.chartType = form.value.chartType
      w.mode = form.value.mode
      w.config = JSON.parse(JSON.stringify(form.value.config))
      w.sql = form.value.sql ? form.value.sql.trim() : ''
      loadWidget(w)
    }
  } else {
    const i = nid()
    const isStat = form.value.chartType === 'stat'
    const w = {
      i,
      type: 'chart',
      title: form.value.title.trim(),
      chartType: form.value.chartType,
      mode: form.value.mode,
      config: JSON.parse(JSON.stringify(form.value.config)),
      sql: form.value.sql ? form.value.sql.trim() : ''
    }
    widgets.value.push(w)
    layout.value.push({ i, x: (layout.value.length * 4) % 12, y: 1000, w: isStat ? 3 : 6, h: isStat ? 4 : 10 })
    loadWidget(w)
  }
  chartDialogVisible.value = false
}

// ==================== 图片 Widget 配置 ====================
const imageDialogVisible = ref(false)
const imgFileInput = ref(null)
const imageForm = reactive({ base64: '', url: '', fit: 'contain' })

const imagePreviewSrc = computed(() => imageForm.base64 || imageForm.url || '')

function openAddImage() {
  editingWidgetId.value = null
  imageForm.base64 = ''
  imageForm.url = ''
  imageForm.fit = 'contain'
  imageDialogVisible.value = true
}

function openEditImage(i) {
  const w = widgetOf(i)
  editingWidgetId.value = i
  const src = (w.image && w.image.src) || ''
  imageForm.base64 = src.startsWith('data:') ? src : ''
  imageForm.url = src.startsWith('data:') ? '' : src
  imageForm.fit = (w.image && w.image.fit) || 'contain'
  imageDialogVisible.value = true
}

function onImageFile(e) {
  const file = e.target.files && e.target.files[0]
  e.target.value = ''
  if (!file) return
  if (file.size > 2 * 1024 * 1024) { ElMessage.warning('图片不能超过 2MB'); return }
  const reader = new FileReader()
  reader.onload = () => {
    imageForm.base64 = reader.result
    imageForm.url = ''
  }
  reader.readAsDataURL(file)
}

function saveImageWidget() {
  const src = imageForm.base64 || (imageForm.url ? imageForm.url.trim() : '')
  if (!src) { ElMessage.warning('请上传图片或填写图片 URL'); return }
  if (editingWidgetId.value) {
    const w = widgets.value.find((x) => x.i === editingWidgetId.value)
    if (w) w.image = { src, fit: imageForm.fit }
  } else {
    const i = nid()
    widgets.value.push({ i, type: 'image', title: '图片', image: { src, fit: imageForm.fit } })
    layout.value.push({ i, x: (layout.value.length * 4) % 12, y: 1000, w: 4, h: 8 })
  }
  imageDialogVisible.value = false
}

// ==================== KPI / 工具函数 ====================
function statValue(i) {
  const rt = runtimeOf(i)
  if (!rt.result || !rt.result.data || !rt.result.data.length) return null
  const row = rt.result.data[0]
  const col = rt.result.columns ? rt.result.columns[0] : Object.keys(row)[0]
  return row[col]
}
function statSub(i) {
  const rt = runtimeOf(i)
  if (!rt.result || !rt.result.columns) return ''
  return rt.result.columns[0] || ''
}
function formatNumber(v) {
  if (v === null || v === undefined || v === '') return '—'
  const n = Number(v)
  if (isNaN(n)) return String(v)
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}
function chartTypeLabel(t) {
  const map = { stat: '数字卡片', line: '折线图', bar: '柱状图', pie: '饼图', scatter: '散点图', radar: '雷达图', heatmap: '热力图', table: '表格', '': '自动' }
  return map[t] || t || '自动'
}

function toggleFullscreen() {
  if (!document.fullscreenElement) {
    screen.value && screen.value.requestFullscreen && screen.value.requestFullscreen()
  } else {
    document.exitFullscreen && document.exitFullscreen()
  }
}

// ==================== 导出图片 / PDF ====================
function pad2(n) { return String(n).padStart(2, '0') }
function ts() {
  const d = new Date()
  return `${d.getFullYear()}${pad2(d.getMonth() + 1)}${pad2(d.getDate())}_${pad2(d.getHours())}${pad2(d.getMinutes())}${pad2(d.getSeconds())}`
}

async function prepareScreen() {
  if (!screen.value) return
  screen.value.classList.add('exporting')
  window.dispatchEvent(new Event('resize'))
  await nextTick()
  await new Promise((r) => setTimeout(r, 350))
}
function restoreScreen() {
  if (screen.value) screen.value.classList.remove('exporting')
}
async function captureCanvas() {
  return html2canvas(screen.value, { backgroundColor: '#071021', scale: 2, useCORS: true, logging: false })
}

async function exportImage() {
  if (!screen.value || exporting.value) return
  exporting.value = true
  try {
    await prepareScreen()
    const canvas = await captureCanvas()
    const a = document.createElement('a')
    a.href = canvas.toDataURL('image/png')
    a.download = `${current.name || 'BI大屏'}_${ts()}.png`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    ElMessage.success('大屏图片已保存')
  } catch (e) {
    console.error(e)
    ElMessage.error('导出图片失败：' + (e && e.message ? e.message : e))
  } finally {
    restoreScreen()
    exporting.value = false
  }
}

async function exportPdf() {
  if (!screen.value || exporting.value) return
  exporting.value = true
  try {
    await prepareScreen()
    const canvas = await captureCanvas()
    const imgData = canvas.toDataURL('image/png')
    const pdf = new jsPDF('p', 'mm', 'a4')
    const pageW = pdf.internal.pageSize.getWidth()
    const pageH = pdf.internal.pageSize.getHeight()
    const imgW = pageW
    const imgH = (canvas.height * imgW) / canvas.width
    let heightLeft = imgH
    let position = 0
    pdf.addImage(imgData, 'PNG', 0, position, imgW, imgH)
    heightLeft -= pageH
    while (heightLeft > 0) {
      position -= pageH
      pdf.addPage()
      pdf.addImage(imgData, 'PNG', 0, position, imgW, imgH)
      heightLeft -= pageH
    }
    pdf.save(`${current.name || 'BI大屏'}_${ts()}.pdf`)
    ElMessage.success('大屏 PDF 已保存')
  } catch (e) {
    console.error(e)
    ElMessage.error('导出 PDF 失败：' + (e && e.message ? e.message : e))
  } finally {
    restoreScreen()
    exporting.value = false
  }
}

function tickClock() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  clock.value = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

onMounted(async () => {
  tickClock()
  clockTimer = setInterval(tickClock, 1000)
  if (route.query.token) {
    // 分享只读模式：免登录，按令牌渲染已公开大屏
    isShare.value = true
    viewMode.value = 'preview'
    await loadShare(route.query.token)
  } else {
    fetchList()
    try {
      const list = await listDatasources()
      datasources.value = Array.isArray(list) ? list : []
      if (datasources.value.length && !datasourceId.value) {
        datasourceId.value = datasources.value[0].id
      }
    } catch (e) {
      datasources.value = []
    }
  }
})
onBeforeUnmount(() => {
  if (clockTimer) clearInterval(clockTimer)
  if (refreshTimer) clearInterval(refreshTimer)
})
</script>

<style scoped>
/* ==================== 列表模式 ==================== */
.dash-list-page {
  padding: 20px 24px;
}
.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}
.list-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 20px;
  font-weight: 700;
}
.list-search {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* ==================== 大屏 ==================== */
.screen {
  min-height: 100vh;
  padding: 16px 20px 28px;
  background: radial-gradient(1200px 600px at 20% -10%, #15315e 0%, transparent 60%),
              radial-gradient(1000px 500px at 100% 0%, #103a4d 0%, transparent 55%),
              linear-gradient(135deg, #071021 0%, #0a1830 100%);
  color: #e6f0ff;
}
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 14px;
  border-bottom: 1px solid rgba(120, 180, 255, 0.18);
}
.title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 2px;
  color: #8ad6ff;
}
.back-btn { color: #9fc4ff; }
.mode-tag { letter-spacing: 0; }
.title-icon { font-size: 26px; color: #5ec8ff; }
.top-right { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.clock { font-variant-numeric: tabular-nums; color: #9fc4ff; font-size: 15px; }
.ds-select { width: 180px; }
:deep(.ds-select .el-input__wrapper) { background: rgba(20, 40, 70, 0.7); }

.edit-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 12px 0 4px;
}
.edit-toolbar .hint { color: #7e9dcc; font-size: 13px; }

.grid-wrap { margin-top: 10px; }

/* ==================== Widget 通用 ==================== */
.widget {
  position: relative;
  width: 100%;
  height: 100%;
  border-radius: 14px;
  overflow: hidden;
}
.widget-actions {
  position: absolute;
  top: 6px;
  right: 8px;
  display: flex;
  gap: 2px;
  z-index: 6;
  opacity: 0.9;
}

/* 图片 Widget */
.image-widget {
  background: rgba(12, 28, 52, 0.4);
  border: 1px solid rgba(120, 180, 255, 0.14);
  display: flex;
  align-items: center;
  justify-content: center;
}
.image-widget img { width: 100%; height: 100%; display: block; }

/* KPI 卡片 */
.kpi-card {
  padding: 18px 20px;
  background: linear-gradient(135deg, rgba(30, 60, 105, 0.55), rgba(15, 35, 65, 0.35));
  border: 1px solid rgba(120, 180, 255, 0.22);
  box-shadow: 0 6px 24px rgba(0, 20, 50, 0.4);
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.kpi-label { color: #9fc4ff; font-size: 14px; }
.kpi-value {
  margin-top: 8px;
  font-size: 32px;
  font-weight: 800;
  color: #5ee0c8;
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
}
.kpi-sub { margin-top: 4px; font-size: 12px; color: #7e9dcc; }
.kpi-err { color: #ff9a9a; font-size: 13px; margin-top: 6px; }

/* 图表卡片 */
.chart-card {
  background: rgba(12, 28, 52, 0.6);
  border: 1px solid rgba(120, 180, 255, 0.18);
  box-shadow: 0 6px 24px rgba(0, 20, 50, 0.38);
  display: flex;
  flex-direction: column;
}
.chart-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid rgba(120, 180, 255, 0.12);
  flex-shrink: 0;
}
.chart-title { font-size: 15px; font-weight: 600; color: #dceaff; }
.chart-type-tag {
  font-size: 12px;
  color: #8ad6ff;
  background: rgba(90, 200, 255, 0.12);
  border: 1px solid rgba(90, 200, 255, 0.3);
  border-radius: 10px;
  padding: 1px 8px;
}
.chart-head-actions { margin-left: auto; display: flex; gap: 4px; }
.chart-body {
  flex: 1;
  min-height: 0;
  padding: 8px 12px 12px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.chart-card :deep(.chart-box) { min-height: 0; width: 100%; height: 100%; }

.panel-error { width: 100%; }
.empty-state { padding: 60px 0; }

.spin { animation: spin 1s linear infinite; color: #5ec8ff; }
.spin.big { font-size: 34px; }
.err { color: #ff9a9a; font-size: 22px; }
@keyframes spin { from { transform: rotate(0); } to { transform: rotate(360deg); } }

.metrics-editor { width: 100%; }
.metric-row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.sql-preview { white-space: normal; word-break: break-all; font-family: monospace; }

.img-hint { margin-left: 10px; color: #999; font-size: 12px; }
.img-preview { max-width: 100%; max-height: 160px; border-radius: 6px; border: 1px solid #eee; }

/* grid-layout-plus 编辑态视觉反馈 */
.is-edit :deep(.vgl-item) { cursor: move; }
.is-edit :deep(.vgl-item--resizing),
.is-edit :deep(.vgl-item--dragging) { opacity: 0.85; }
:deep(.vgl-item__resizer) { z-index: 7; }

/* 导出时的「干净模式」：规避 html2canvas 渲染坑 */
.screen.exporting {
  background: #071021 !important;
}
.exporting .edit-toolbar,
.exporting .widget-actions,
.exporting .chart-head-actions {
  display: none !important;
}
</style>
