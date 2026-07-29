<template>
  <div class="chat">
    <div class="chat-bar">
      <span class="cb-title">智能对话</span>
      <span class="sid" :title="sessionId">会话：{{ sessionId }}</span>
      <el-button text @click="drawerVisible = true">历史记录</el-button>
      <el-button text @click="newSession">新会话</el-button>
      <el-button text :icon="theme === 'dark' ? Sunny : Moon" @click="toggleTheme" title="切换主题" />
    </div>

    <main class="body">
      <section ref="answerBox" class="answer glass">
        <div v-if="!historyList.length && !answer && !trace.length && !running" class="placeholder">
          <div class="ph-icon">🤖</div>
          <p>试着问：<b>“分析上季度各区域销售额趋势，并解释异常”</b></p>
        </div>
        <div v-else class="answer-inner">
          <div v-for="(m, i) in historyList" :key="'h' + i" class="bubble" :class="m.role">
            <div class="bubble-role">{{ m.role === 'user' ? '我' : 'AI' }}</div>
            <div class="bubble-text">{{ m.content }}</div>
            <ChartBlock v-for="(c, ci) in (m.charts || [])" :key="'c' + ci" :option="c" />
          </div>
          <div class="answer-text">
            <span v-if="answer">{{ answer }}</span>
            <span v-else-if="running" class="caret">▌</span>
          </div>
          <transition-group name="fade" tag="div" class="trace-wrap">
            <TracePanel v-for="(t, i) in trace" :key="i" :item="t" />
          </transition-group>
        </div>
      </section>

      <section class="input glass">
        <div class="ds-row">
          <el-select v-model="datasourceId" placeholder="选择数据源" filterable :disabled="running" style="width:240px">
            <el-option v-for="ds in datasources" :key="ds.id" :label="`${ds.name}（${ds.type} / ${ds.databaseName}）`" :value="ds.id" />
          </el-select>
        </div>
        <el-input
          v-model="query"
          type="textarea"
          :rows="2"
          resize="none"
          :disabled="running"
          placeholder="用自然语言描述你的分析需求…"
          @keydown.enter.exact.prevent="send"
        />
        <div class="send-row">
          <span class="hint">Enter 发送 · Shift+Enter 换行</span>
          <el-button v-if="!running" type="primary" :icon="Promotion" @click="send">发送</el-button>
          <el-button v-else type="danger" :icon="VideoPause" @click="interrupt">中断</el-button>
        </div>
      </section>
    </main>

    <HistoryDrawer v-model="drawerVisible" :active-sid="sessionId" @open-session="onOpenSession" />
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { Promotion, VideoPause, Moon, Sunny } from '@element-plus/icons-vue'
import { streamChat, getHistory } from '@/api/agent'
import { listDatasources } from '@/api/datasource'
import { listSandboxDbs } from '@/api/sandbox'
import TracePanel from '@/components/TracePanel.vue'
import HistoryDrawer from '@/components/HistoryDrawer.vue'
import ChartBlock from '@/components/ChartBlock.vue'

const query = ref('')
const answer = ref('')
const trace = ref([])
const running = ref(false)
const sessionId = ref(genSid())
const answerBox = ref(null)
const historyList = ref([])
const drawerVisible = ref(false)
/** 数据源下拉（复用 QueryView 的数据源列表接口），跨会话保留 */
const datasourceId = ref(null)
const datasources = ref([])
/** localStorage 中持久化的「最后活跃会话」key，刷新后恢复 */
const LS_SID = 'bi_last_sid'
/** localStorage 中持久化的「当前数据源」key，刷新后恢复 */
const LS_DS = 'bi_ds_id'
let controller = null

const theme = ref(localStorage.getItem('bi_theme') || 'light')

function genSid() {
  return 'sess-' + Math.random().toString(36).slice(2, 10)
}
function toggleTheme() {
  theme.value = theme.value === 'dark' ? 'light' : 'dark'
  document.documentElement.dataset.theme = theme.value
  localStorage.setItem('bi_theme', theme.value)
}
function scrollDown() {
  nextTick(() => {
    const el = answerBox.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function handleEvent(ev) {
  const { event, data } = ev
  if (event === 'token') {
    answer.value += data
  } else if (event === 'tool_call') {
    let parsed
    try {
      parsed = JSON.parse(data)
    } catch {
      parsed = { tool: '?', args: data }
    }
    let args = parsed.args
    try {
      args = JSON.parse(args)
    } catch {
      /* 保持原字符串 */
    }
    trace.value.push({ type: 'tool_call', tool: parsed.tool, args, open: true })
  } else if (event === 'tool_result') {
    let parsed
    try {
      parsed = JSON.parse(data)
    } catch {
      parsed = { tool: '?', result: data }
    }
    let result = parsed.result
    let chartOption = null
    try {
      const r = JSON.parse(result)
      result = r
      // 后端 Agent 工具（select_chart）把 ECharts 配置放在 echartsOption 字段（与 QueryView 的 nl2sql 契约一致）
      if (r && r.echartsOption) chartOption = r.echartsOption
      else if (r && r.option) chartOption = r.option
      else if (r && (r.series || r.xAxis)) chartOption = r
    } catch {
      /* 保持原字符串 */
    }
    trace.value.push({ type: 'tool_result', tool: parsed.tool, result, chartOption })
  } else if (event === 'reasoning') {
    trace.value.push({ type: 'reasoning', text: data })
  } else if (event === 'error') {
    trace.value.push({ type: 'error', text: data })
  } else if (event === 'done') {
    // 本轮结束：把流式最终答案固化成气泡，并把本轮推理中 select_chart 生成的
    // 图表一起带进最终汇总，避免「图在推理过程中渲染、汇总时却消失」
    const charts = trace.value
      .filter((t) => t.type === 'tool_result' && t.chartOption)
      .map((t) => t.chartOption)
    if (answer.value || charts.length) {
      historyList.value.push({ role: 'assistant', content: answer.value || '', charts })
    }
    answer.value = ''
    trace.value = []
    running.value = false
  }
  scrollDown()
}

async function send() {
  const q = query.value.trim()
  if (!q || running.value) return
  running.value = true
  answer.value = ''
  trace.value = []
  query.value = ''
  // 持久化当前 sid 与所选数据源，并把用户提问先渲染成气泡（后续可追溯、刷新不丢）
  localStorage.setItem(LS_SID, sessionId.value)
  localStorage.setItem(LS_DS, datasourceId.value)
  if (datasourceId.value == null || Number.isNaN(datasourceId.value)) {
    console.warn('[BI] datasourceId 异常，当前值:', datasourceId.value, '将导致后端回退到默认数据源 1')
  }
  historyList.value.push({ role: 'user', content: q })
  controller = new AbortController()
  try {
    await streamChat({
      query: q,
      sessionId: sessionId.value,
      datasourceId: datasourceId.value,
      token: localStorage.getItem('bi_token') || '',
      signal: controller.signal,
      onEvent: handleEvent
    })
  } catch (e) {
    if (e.name !== 'AbortError') {
      trace.value.push({ type: 'error', text: e.message || String(e) })
    }
  } finally {
    running.value = false
    controller = null
    scrollDown()
  }
}

function interrupt() {
  if (controller) controller.abort()
}
function newSession() {
  if (running.value) return
  sessionId.value = genSid()
  localStorage.setItem(LS_SID, sessionId.value)
  answer.value = ''
  trace.value = []
  historyList.value = []
}

/** 从历史抽屉加载某会话：切换 sid + 渲染历史气泡，后续可继续对话 */
function   onOpenSession({ sessionId: sid, messages }) {
  sessionId.value = sid
  localStorage.setItem(LS_SID, sid)
  historyList.value = (messages || []).map((m) => ({ role: m.role, content: m.content, charts: m.charts || [] }))
  answer.value = ''
  trace.value = []
}

onMounted(async () => {
  document.documentElement.dataset.theme = theme.value
  // 刷新后恢复上次选择的数据源（跨会话保留，允许负值：负 id 表示具体沙箱库）
  const dsStored = localStorage.getItem(LS_DS)
  if (dsStored) {
    const n = Number(dsStored)
    if (Number.isInteger(n)) {
      datasourceId.value = n
    } else {
      // 脏值：清除并让后续自动选中逻辑接管
      console.warn('[BI] localStorage bi_ds_id 有脏值:', dsStored, '已清除')
      localStorage.removeItem(LS_DS)
    }
  }
  // 拉取数据源列表（失败不阻塞页面）
  try {
    const list = await listDatasources()
    datasources.value = Array.isArray(list) ? list : []
    // 追加沙箱虚拟数据源：
    //  - id=0 表示「全部沙箱」（后端 BiAgentService 识别为 sandbox 模式，作用域为整个 sandbox schema）
    //  - id=-dbId 表示锁定某个具体沙箱库（作用域收敛到该库，便于「按库分析」）
    const sandboxOptions = [
      { id: 0, name: '数据沙箱（全部）', type: 'sandbox', databaseName: 'sandbox' }
    ]
    try {
      const dbs = await listSandboxDbs()
      if (Array.isArray(dbs)) {
        for (const db of dbs) {
          sandboxOptions.push({
            id: -db.id,
            name: '沙箱·' + db.name,
            type: 'sandbox',
            databaseName: db.dbKey
          })
        }
      }
    } catch (e) {
      // 沙箱库列表拉取失败不影响其余功能
    }
    datasources.value = datasources.value.concat(sandboxOptions)
    if (datasourceId.value == null && datasources.value.length > 0) {
      datasourceId.value = datasources.value[0].id
    }
  } catch (e) {
    // 拉取失败不影响对话功能
  }
  // 刷新后恢复上次会话及其内容（sid 持久化保证）
  const stored = localStorage.getItem(LS_SID)
  if (stored) {
    sessionId.value = stored
    try {
      const detail = await getHistory(stored)
      historyList.value = ((detail && detail.messages) || []).map((m) => ({
        role: m.role,
        content: m.content,
        charts: m.charts || []
      }))
    } catch (e) {
      // 未登录或网络异常：仅保留 sid，不渲染历史
    }
  }
})
</script>

<style scoped>
.chat {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.chat-bar {
  display: flex;
  align-items: center;
  gap: 10px;
}
.cb-title {
  font-size: 16px;
  font-weight: 700;
}
.sid {
  font-size: 12px;
  color: var(--text-dim);
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.body {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 0;
}
.answer {
  flex: 1;
  padding: 18px 20px;
  overflow: auto;
  min-height: 0;
}
.placeholder {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--text-dim);
  gap: 10px;
}
.ph-icon {
  font-size: 46px;
}
.answer-text {
  font-size: 15px;
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
}
.bubble {
  margin-bottom: 12px;
  max-width: 92%;
  padding: 10px 12px;
  border-radius: 10px;
  font-size: 15px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
.bubble.user {
  margin-left: auto;
  background: var(--el-color-primary-light-9, #ecf5ff);
  border: 1px solid var(--el-color-primary-light-7, #c6e2ff);
}
.bubble.assistant {
  margin-right: auto;
  background: var(--glass-bg, rgba(255, 255, 255, 0.04));
  border: 1px solid var(--border, #e5e7eb);
}
.bubble-role {
  font-size: 12px;
  color: var(--text-dim);
  margin-bottom: 4px;
}
.bubble :deep(.chart-box) {
  margin-top: 10px;
}
.caret {
  display: inline-block;
  animation: blink 1s steps(2) infinite;
}
@keyframes blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0;
  }
}
.trace-wrap {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
}
.input {
  padding: 12px 14px;
}
.send-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}
.hint {
  font-size: 12px;
  color: var(--text-dim);
}
.ds-row {
  margin-bottom: 8px;
}
</style>
