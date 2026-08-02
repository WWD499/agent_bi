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
            <div class="bubble-text">
              <template v-if="hasChartPlaceholder(m.content)">
                <template v-for="(part, pi) in parseAnswer(m.content)" :key="pi">
                  <template v-if="part.type === 'text'">
                    <template v-for="(seg, si) in renderTextSegments(part.text)" :key="si">
                      <template v-if="seg.type === 'plain'">{{ seg.text }}</template>
                      <a v-else href="javascript:void(0)" class="chat-link" @click.prevent="openLink(seg.href)">{{ seg.text }}</a>
                    </template>
                  </template>
                  <ChartBlock
                    v-else-if="part.type === 'chart' && chartByIndexInMessage(m.charts, part.index)"
                    :option="chartByIndexInMessage(m.charts, part.index)"
                    class="inline-chart"
                  />
                  <div v-else-if="part.type === 'chart'" class="inline-chart-missing">
                    [图表未生成]
                  </div>
                </template>
              </template>
              <template v-else>
                <template v-for="(seg, si) in renderTextSegments(m.content)" :key="si">
                  <template v-if="seg.type === 'plain'">{{ seg.text }}</template>
                  <a v-else href="javascript:void(0)" class="chat-link" @click.prevent="openLink(seg.href)">{{ seg.text }}</a>
                </template>
              </template>
            </div>
            <ChartBlock v-if="!hasChartPlaceholder(m.content)" v-for="(c, ci) in (m.charts || [])" :key="'c' + ci" :option="c" />
          </div>
          <div class="answer-text">
            <template v-if="answerParts.length">
              <template v-for="(part, pi) in answerParts" :key="pi">
                <template v-if="part.type === 'text'">
                  <template v-for="(seg, si) in renderTextSegments(part.text)" :key="si">
                    <template v-if="seg.type === 'plain'">{{ seg.text }}</template>
                    <a v-else href="javascript:void(0)" class="chat-link" @click.prevent="openLink(seg.href)">{{ seg.text }}</a>
                  </template>
                </template>
                <ChartBlock
                  v-else-if="part.type === 'chart' && chartByIndex[part.index]"
                  :option="chartByIndex[part.index]"
                  class="inline-chart"
                />
                <div v-else-if="part.type === 'chart'" class="inline-chart-missing">
                  [图表未生成]
                </div>
              </template>
            </template>
            <span v-else-if="running" class="caret">▌</span>
          </div>
          <!-- 模型若未按约定插入 {{chart:*}} 占位符，兜底把图表渲染在文字下方 -->
          <ChartBlock
            v-for="(c, ci) in orphanCharts"
            :key="'cc' + ci"
            :option="c"
            class="current-chart"
          />
          <transition-group name="fade" tag="div" class="trace-wrap">
            <TracePanel v-for="(t, i) in trace" :key="i" :item="t" />
          </transition-group>
        </div>
      </section>

      <section class="input glass">
        <div class="ds-row">
          <el-select v-model="datasourceId" placeholder="选择沙箱库" filterable :disabled="running" style="width:240px">
            <el-option v-for="ds in datasources" :key="ds.id" :label="`${ds.name}（${ds.type} / ${ds.databaseName}）`" :value="ds.id" />
          </el-select>
        </div>
        <el-input
          v-model="query"
          type="textarea"
          :rows="2"
          resize="none"
          :disabled="running || awaitingConfirm"
          placeholder="用自然语言描述你的分析需求…"
          @keydown.enter.exact.prevent="send"
        />
        <div class="send-row">
          <div class="switch-group">
            <el-tooltip content="开启后智能体可在沙箱内建表/改显示名/导入/落表/删表（仅沙箱模式生效）" placement="top">
              <span class="sw-item" :class="{ disabled: !isSandbox }">
                <el-switch
                  v-model="allowWrite"
                  :disabled="running || awaitingConfirm || !isSandbox"
                  inline-prompt
                  active-text="写"
                  inactive-text="读"
                />
                <span class="sw-label">允许写库</span>
              </span>
            </el-tooltip>
            <el-tooltip content="开启后写操作直接执行、不再弹确认框（需先开启允许写库，谨慎使用）" placement="top">
              <span class="sw-item" :class="{ disabled: !isSandbox || !allowWrite }">
                <el-switch
                  v-model="skipConfirm"
                  :disabled="running || awaitingConfirm || !allowWrite || !isSandbox"
                  inline-prompt
                  active-text="免确认"
                  inactive-text="需确认"
                />
                <span class="sw-label">跳过确认</span>
              </span>
            </el-tooltip>
          </div>
          <span class="hint">
            <template v-if="awaitingConfirm">⚠️ 等待您确认写操作…</template>
            <template v-else>Enter 发送 · Shift+Enter 换行</template>
          </span>
          <el-button v-if="!running && !awaitingConfirm" type="primary" :icon="Promotion" @click="send">发送</el-button>
          <el-button v-else-if="awaitingConfirm" type="warning" disabled>等待确认…</el-button>
          <el-button v-else type="danger" :icon="VideoPause" @click="interrupt">中断</el-button>
        </div>
      </section>
    </main>

    <HistoryDrawer v-model="drawerVisible" :active-sid="sessionId" @open-session="onOpenSession" />
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Promotion, VideoPause, Moon, Sunny } from '@element-plus/icons-vue'
import { streamChat, getHistory, confirmAgent } from '@/api/agent'
import { listSandboxDbs } from '@/api/sandbox'
import TracePanel from '@/components/TracePanel.vue'
import HistoryDrawer from '@/components/HistoryDrawer.vue'
import ChartBlock from '@/components/ChartBlock.vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const router = useRouter()

const query = ref('')
const answer = ref('')
/** 当前流式回答伴随的图表（由后端 charts 事件推送），done 后并入 historyList */
const currentCharts = ref([])
const trace = ref([])
const running = ref(false)
/** 是否正在等待用户对写操作的确认（M2）：此时禁用输入框，避免并发 */
const awaitingConfirm = ref(false)
const sessionId = ref(genSid())
const answerBox = ref(null)
const historyList = ref([])
const drawerVisible = ref(false)
/** 数据源下拉（复用 QueryView 的数据源列表接口），跨会话保留 */
const datasourceId = ref(null)
const datasources = ref([])
/** 双开关：允许写库（主开关）/ 跳过确认（子开关），仅沙箱模式生效 */
const allowWrite = ref(false)
const skipConfirm = ref(false)

/** 当前流式答案中 {{chart:N}} 占位符解析后的片段列表（文字与图表交错） */
const answerParts = computed(() => parseAnswer(answer.value))
/** 按 _chartIndex 建立当前图表索引，供占位符查找 */
const chartByIndex = computed(() => {
  const map = {}
  for (const c of currentCharts.value) {
    const idx = c && c._chartIndex
    if (idx !== undefined && idx !== null) map[idx] = c
  }
  return map
})
/** 没有被占位符引用的图表，兜底渲染在文字下方 */
const orphanCharts = computed(() => {
  const used = new Set(answerParts.value.filter((p) => p.type === 'chart').map((p) => p.index))
  return currentCharts.value.filter((c) => {
    const idx = c && c._chartIndex
    return idx === undefined || idx === null || !used.has(idx)
  })
})

function hasChartPlaceholder(text) {
  return typeof text === 'string' && /\{\{chart:\d+\}\}/.test(text)
}
function parseAnswer(text) {
  if (!text) return []
  const parts = []
  const regex = /\{\{chart:(\d+)\}\}/g
  let last = 0
  let m
  while ((m = regex.exec(text)) !== null) {
    if (m.index > last) parts.push({ type: 'text', text: text.slice(last, m.index) })
    parts.push({ type: 'chart', index: Number(m[1]) })
    last = m.index + m[0].length
  }
  if (last < text.length) parts.push({ type: 'text', text: text.slice(last) })
  return parts
}
function chartByIndexInMessage(charts, index) {
  return (charts || []).find((c) => c && c._chartIndex === index)
}
/** 把文本中的 Markdown 链接 [文本](链接) 拆分为可渲染片段 */
function renderTextSegments(text) {
  if (!text) return [{ type: 'plain', text: '' }]
  const regex = /\[([^\]]+)\]\(([^)]+)\)/g
  const segments = []
  let last = 0
  let m
  while ((m = regex.exec(text)) !== null) {
    if (m.index > last) segments.push({ type: 'plain', text: text.slice(last, m.index) })
    segments.push({ type: 'link', text: m[1], href: m[2] })
    last = m.index + m[0].length
  }
  if (last < text.length) segments.push({ type: 'plain', text: text.slice(last) })
  return segments
}
function openLink(href) {
  if (!href) return
  if (href.startsWith('/')) {
    // 站内相对路径走 Vue Router，避免整页刷新
    router.push(href)
  } else {
    // 绝对路径或外部链接新开标签页
    window.open(href, '_blank')
  }
}
/** 是否处于沙箱模式（datasourceId=0 全部沙箱 / 负数锁定具体沙箱库）；业务库模式无写工具，开关无意义 */
const isSandbox = computed(() => datasourceId.value !== null
  && (datasourceId.value === 0 || datasourceId.value < 0))
// 主开关关闭时，子开关强制归位（避免「未开写库却记忆着免确认」的脏状态）
watch(allowWrite, (v) => {
  localStorage.setItem(LS_WRITE, v ? '1' : '0')
  if (!v) skipConfirm.value = false
})
watch(skipConfirm, (v) => {
  if (allowWrite.value) localStorage.setItem(LS_SKIP, v ? '1' : '0')
})
/** localStorage 中持久化的「最后活跃会话」key，刷新后恢复 */
const LS_SID = 'bi_last_sid'
/** localStorage 中持久化的「当前数据源」key，刷新后恢复 */
const LS_DS = 'bi_ds_id'
/** localStorage 中持久化的双开关状态 key，刷新后恢复 */
const LS_WRITE = 'bi_allow_write'
const LS_SKIP = 'bi_skip_confirm'
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
  // 多次尝试：nextTick 处理同步渲染，requestAnimationFrame / 延时兜底异步图表、图片等布局变化，
  // 确保进入会话或加载历史后稳稳落在最新消息（最底部）。
  const doScroll = () => {
    const el = answerBox.value
    if (el) el.scrollTop = el.scrollHeight
  }
  nextTick(doScroll)
  requestAnimationFrame(doScroll)
  setTimeout(doScroll, 80)
}

function handleEvent(ev) {
  const { event, data } = ev
  if (event === 'token') {
    answer.value += data
  } else if (event === 'confirm') {
    // M2：后端在即将执行写工具（建表/落表/CTAS/删表）前弹出的确认事件，
    // 此时后端 ReAct 循环已阻塞在 confirmFuture 上，必须用户明确同意/拒绝后才继续。
    handleConfirmEvent(data)
    return
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
  } else if (event === 'charts') {
    // 后端在流式文本结束后，把本轮最终图表一次性推过来；实时渲染在 answer 区下方
    let parsed
    try {
      parsed = JSON.parse(data)
    } catch {
      parsed = []
    }
    currentCharts.value = Array.isArray(parsed) ? parsed : []
  } else if (event === 'done') {
    // 本轮结束：把流式最终答案固化成气泡，并合并后端推送的图表
    const charts = currentCharts.value.length
      ? currentCharts.value
      : trace.value
          .filter((t) => t.type === 'tool_result' && t.chartOption)
          .map((t) => t.chartOption)
    if (answer.value || charts.length) {
      historyList.value.push({ role: 'assistant', content: answer.value || '', charts })
    }
    answer.value = ''
    currentCharts.value = []
    trace.value = []
    running.value = false
  }
  scrollDown()
}

/**
 * 处理后端弹出的「写操作确认」事件（M2）。
 * 解析 payload（title / detail），弹出确认框；用户同意→调 /agent/confirm{approved:true}，
 * 拒绝/关闭→调 /agent/confirm{approved:false}，唤醒后端挂起的确认 future。
 * 注意：后端有 120s 超时兜底（超时按拒绝处理），前端应尽量及时响应。
 */
async function handleConfirmEvent(data) {
  let payload = {}
  try {
    payload = JSON.parse(data || '{}')
  } catch {
    payload = {}
  }
  const title = payload.title || '写操作确认'
  const detail = payload.detail || 'Agent 即将在沙箱中执行写操作，是否允许？'
  awaitingConfirm.value = true
  // 在推理轨迹里先留一个提示，避免对话框未弹出时用户无感知
  trace.value.push({ type: 'reasoning', text: '⚠️ ' + title + '：已弹出确认对话框，请查看页面中央…' })
  scrollDown()
  // 若用户切到别的标签页，闪烁标题提醒
  let titleFlash = null
  if (document.hidden) {
    const originalTitle = document.title
    let flash = true
    titleFlash = setInterval(() => {
      document.title = flash ? '【等待确认】' + originalTitle : originalTitle
      flash = !flash
    }, 800)
  }
  try {
    await ElMessageBox.confirm(detail, title, {
      type: 'warning',
      confirmButtonText: '同意并执行',
      cancelButtonText: '拒绝',
      distinguishCancelAndClose: true,
      closeOnClickModal: false,
      closeOnPressEscape: false,
      showClose: false
    })
    try {
      await confirmAgent(sessionId.value, true)
      trace.value.push({ type: 'reasoning', text: '✅ 已同意，Agent 正在执行写操作…' })
    } catch (e) {
      // 网络/服务端异常不影响主流程，后端超时兜底会按拒绝处理
      ElMessage.warning('确认请求发送失败，后端将超时自动取消')
    }
  } catch (e) {
    // 用户点「拒绝」或关闭对话框 → 通知后端取消
    try {
      await confirmAgent(sessionId.value, false)
    } catch {
      /* ignore */
    }
    trace.value.push({ type: 'reasoning', text: '⛔ 已拒绝写操作，Agent 将改用只读分析方案。' })
  } finally {
    awaitingConfirm.value = false
    if (titleFlash) {
      clearInterval(titleFlash)
      document.title = document.title.replace(/^【等待确认】/, '')
    }
    scrollDown()
  }
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
      allowWrite: allowWrite.value,
      skipConfirm: skipConfirm.value,
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
  currentCharts.value = []
  trace.value = []
  historyList.value = []
}

/** 从历史抽屉加载某会话：切换 sid + 渲染历史气泡，后续可继续对话 */
function onOpenSession({ sessionId: sid, messages }) {
  sessionId.value = sid
  localStorage.setItem(LS_SID, sid)
  historyList.value = (messages || []).map((m) => ({ role: m.role, content: m.content, charts: m.charts || [] }))
  answer.value = ''
  currentCharts.value = []
  trace.value = []
  scrollDown()
}

onMounted(async () => {
  document.documentElement.dataset.theme = theme.value
  // 刷新后恢复上次选择的数据源（跨会话保留，允许负值：负 id 表示具体沙箱库）
  const dsStored = localStorage.getItem(LS_DS)
  if (dsStored) {
    const n = Number(dsStored)
    if (Number.isInteger(n)) {
      // 旧版存的是「源数据库正数 id」，现在只支持沙箱：正数统一回落为「全部沙箱」(0)
      datasourceId.value = n > 0 ? 0 : n
    } else {
      // 脏值：清除并让后续自动选中逻辑接管
      console.warn('[BI] localStorage bi_ds_id 有脏值:', dsStored, '已清除')
      localStorage.removeItem(LS_DS)
    }
  }
  // 刷新后恢复双开关状态（仅沙箱模式有意义；主开关关时子开关强制关）
  if (localStorage.getItem(LS_WRITE) === '1') {
    allowWrite.value = true
    if (localStorage.getItem(LS_SKIP) === '1') {
      skipConfirm.value = true
    }
  }
  // 拉取沙箱数据源列表（仅沙箱库可选，源数据库不在此出现）
  try {
    // 沙箱虚拟数据源：
    //  - id=0 表示「全部沙箱」（后端识别为 sandbox 模式，作用域为整个 sandbox schema）
    //  - id=-dbId 表示锁定某个具体沙箱库（作用域收敛到该库，便于「按库分析」）
    const sandboxOptions = [
      { id: 0, name: '数据沙箱（全部）', type: 'sandbox', databaseName: 'sandbox' }
    ]
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
    datasources.value = sandboxOptions
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
      scrollDown()
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
.current-chart {
  margin-top: 12px;
  height: 340px;
}
.chat-link {
  color: var(--el-color-primary, #409eff);
  text-decoration: underline;
  cursor: pointer;
}
.chat-link:hover {
  color: var(--el-color-primary-light-3, #66b1ff);
}
.inline-chart {
  margin: 12px 0;
  height: 340px;
}
.inline-chart-missing {
  margin: 12px 0;
  height: 120px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-dim, #999);
  background: var(--code-bg, #f5f7fa);
  border: 1px dashed var(--border, #dcdfe6);
  border-radius: 10px;
  font-size: 14px;
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
.switch-group {
  display: flex;
  align-items: center;
  gap: 14px;
}
.sw-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.sw-item.disabled {
  opacity: 0.45;
}
.sw-label {
  font-size: 12px;
  color: var(--text-dim);
  user-select: none;
}
.ds-row {
  margin-bottom: 8px;
}
</style>
