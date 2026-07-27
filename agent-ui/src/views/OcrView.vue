<template>
  <div class="ocr-page">
    <div class="ocr-head">
      <h2>📄 OCR 智能识别</h2>
      <div class="head-actions">
        <span class="tip">引擎：本地 PaddleOCR（默认 http://localhost:8866）</span>
        <el-button size="small" @click="openHistory">历史记录</el-button>
      </div>
    </div>

    <el-row :gutter="16">
      <el-col :span="14">
        <div class="glass">
          <div class="card-h">上传图片识别</div>
          <el-upload
            class="uploader"
            drag
            action="#"
            :auto-upload="false"
            :show-file-list="false"
            :on-change="onFileChange"
            accept="image/*"
          >
            <el-icon class="up-icon"><UploadFilled /></el-icon>
            <div class="el-upload__text">拖入图片，或 <em>点击上传</em></div>
          </el-upload>
          <div v-if="imageUrl" class="img-preview">
            <img :src="imageUrl" alt="预览" />
          </div>
          <div v-if="ocrLoading" class="loading"><span class="spin big">⚙</span> 识别中…</div>
          <div v-if="ocrError" class="ocr-err">
            <div class="err-title">⚠️ 识别失败：{{ ocrError }}</div>
            <div class="err-hint">
              多为「本机未启动 PaddleOCR 服务」所致。请先启动本地服务（默认 http://localhost:8866）：
              <br />① 进入项目 <code>ocr-service/</code> 目录 ② <code>pip install -r requirements.txt</code> ③ <code>python ocr_server.py</code>
              <br />启动后访问 <code>http://localhost:8866/health</code> 返回 <code>{"status":"ok"}</code> 即正常，再重传图片即可识别。
            </div>
          </div>
        </div>

        <div class="glass" v-if="result">
          <div class="card-h">
            识别结果
            <span class="tag">{{ (result.blocks || []).length }} 个文本块</span>
          </div>
          <el-input type="textarea" :rows="6" v-model="result.text" readonly />
          <div class="block-list">
            <div v-for="(b, i) in (result.blocks || [])" :key="i" class="block-item">
              <span class="bk-idx">{{ i + 1 }}</span>
              <span class="bk-text">{{ b.text }}</span>
              <span class="bk-conf" :class="confClass(b.confidence)">{{ Math.round((b.confidence || 0) * 100) }}%</span>
            </div>
          </div>
        </div>
      </el-col>

      <el-col :span="10">
        <div class="glass">
          <div class="card-h">
            结构化抽取
            <el-button size="small" type="primary" @click="doExtract" :loading="extractLoading">抽取</el-button>
          </div>
          <el-input
            v-model="extractSchema"
            placeholder="可选：抽取的字段说明，如「发票号、金额、开票日期、销售方」"
            style="margin-bottom:10px"
          />
          <el-input v-if="structured" type="textarea" :rows="10" v-model="structured" />
          <div v-else class="empty-hint">先识别（或粘贴文本）后点击「抽取」，由大模型输出 JSON</div>
          <div class="ops" v-if="(result && result.text) || structured">
            <el-button size="small" @click="saveRecord">保存记录</el-button>
            <el-button size="small" type="success" @click="toKnowledge">入知识库</el-button>
          </div>
        </div>
      </el-col>
    </el-row>

    <el-drawer v-model="historyVisible" title="OCR 历史记录" size="440px">
      <el-input v-model="historyKeyword" placeholder="搜索识别文本" @input="loadHistory" style="margin-bottom:10px" />
      <div v-for="h in history" :key="h.id" class="hist-item" @click="openHistoryItem(h)">
        <div class="hist-text">{{ truncate(h.rawText) }}</div>
        <div class="hist-meta">
          <span>{{ h.createTime }} · {{ h.source }}</span>
          <el-button size="small" type="danger" link @click.stop="delHistory(h)">删除</el-button>
        </div>
      </div>
      <div v-if="!history.length" class="empty-hint">暂无历史记录</div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import {
  uploadOcr,
  extractOcr,
  ocrToKnowledge,
  addOcrRecord,
  listOcrRecords,
  deleteOcrRecord
} from '@/api/ocr'

const imageUrl = ref('')
const currentFile = ref(null)
const ocrLoading = ref(false)
const ocrError = ref('')
const result = ref(null)
const extractSchema = ref('')
const structured = ref('')
const extractLoading = ref(false)

const historyVisible = ref(false)
const history = ref([])
const historyKeyword = ref('')

function onFileChange(file) {
  if (!file || !file.raw) return
  currentFile.value = file.raw
  imageUrl.value = URL.createObjectURL(file.raw)
  result.value = null
  structured.value = ''
  ocrError.value = ''
  runOcr()
}

async function runOcr() {
  if (!currentFile.value) return
  ocrLoading.value = true
  ocrError.value = ''
  try {
    result.value = await uploadOcr(currentFile.value)
  } catch (e) {
    const msg = (e && e.response && e.response.data && e.response.data.msg) || e.message || '识别失败'
    ocrError.value = msg
    ElMessage.error(msg)
  } finally {
    ocrLoading.value = false
  }
}

async function doExtract() {
  const text = result.value ? result.value.text : ''
  if (!text) {
    ElMessage.warning('请先识别或粘贴文本')
    return
  }
  extractLoading.value = true
  try {
    structured.value = await extractOcr(text, extractSchema.value)
  } catch (e) {
    ElMessage.error((e && e.response && e.response.data && e.response.data.msg) || e.message || '抽取失败')
  } finally {
    extractLoading.value = false
  }
}

async function saveRecord() {
  const raw = result.value ? result.value.text : ''
  if (!raw && !structured.value) {
    ElMessage.warning('没有可保存的内容')
    return
  }
  try {
    await addOcrRecord({ rawText: raw, structuredJson: structured.value, source: 'upload' })
    ElMessage.success('已保存记录')
  } catch (e) {
    ElMessage.error((e && e.response && e.response.data && e.response.data.msg) || e.message || '保存失败')
  }
}

async function toKnowledge() {
  const content = structured.value || (result.value ? result.value.text : '')
  if (!content) {
    ElMessage.warning('没有可入库的内容')
    return
  }
  const title = 'OCR 识别 ' + new Date().toLocaleString()
  try {
    await ocrToKnowledge(null, title, content)
    ElMessage.success('已写入知识库（自动向量化）')
  } catch (e) {
    ElMessage.error((e && e.response && e.response.data && e.response.data.msg) || e.message || '写入失败')
  }
}

async function openHistory() {
  historyVisible.value = true
  await loadHistory()
}

async function loadHistory() {
  try {
    history.value = await listOcrRecords({ keyword: historyKeyword.value, page: 1, size: 50 })
  } catch (e) {
    history.value = []
  }
}

async function delHistory(h) {
  try {
    await deleteOcrRecord(h.id)
    ElMessage.success('已删除')
    await loadHistory()
  } catch (e) {
    ElMessage.error('删除失败')
  }
}

function openHistoryItem(h) {
  result.value = { text: h.rawText || '', blocks: [], raw: '' }
  structured.value = h.structuredJson || ''
  historyVisible.value = false
}

function confClass(c) {
  if (c == null) return 'low'
  if (c >= 0.9) return 'high'
  if (c >= 0.7) return 'mid'
  return 'low'
}

function truncate(t) {
  if (!t) return ''
  return t.length > 50 ? t.slice(0, 50) + '…' : t
}
</script>

<style scoped>
.ocr-page { padding: 16px; }
.ocr-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.ocr-head h2 { margin: 0; color: #dceaff; font-size: 20px; }
.head-actions { display: flex; align-items: center; gap: 10px; }
.tip { color: #8aa0c0; font-size: 12px; }
.glass {
  background: rgba(12, 28, 52, 0.6);
  border: 1px solid rgba(120, 180, 255, 0.18);
  border-radius: 14px;
  padding: 14px 16px;
  margin-bottom: 14px;
  box-shadow: 0 6px 24px rgba(0, 20, 50, 0.38);
}
.card-h { display: flex; align-items: center; gap: 8px; font-weight: 600; color: #dceaff; margin-bottom: 10px; }
.tag {
  font-size: 12px; color: #8ad6ff;
  background: rgba(90, 200, 255, 0.12);
  border: 1px solid rgba(90, 200, 255, 0.3);
  border-radius: 10px; padding: 1px 8px;
}
.uploader { width: 100%; }
.up-icon { font-size: 48px; color: #5ec8ff; margin-bottom: 8px; }
.img-preview { margin-top: 10px; text-align: center; }
.img-preview img { max-width: 100%; max-height: 260px; border-radius: 8px; }
.block-list { margin-top: 10px; max-height: 240px; overflow: auto; }
.block-item { display: flex; align-items: center; gap: 8px; padding: 6px 8px; border-bottom: 1px solid rgba(120, 180, 255, 0.1); font-size: 13px; }
.bk-idx { width: 22px; height: 22px; border-radius: 50%; background: rgba(90, 200, 255, 0.15); color: #8ad6ff; display: flex; align-items: center; justify-content: center; flex: none; }
.bk-text { flex: 1; color: #cfe4ff; word-break: break-all; }
.bk-conf { font-size: 12px; flex: none; }
.bk-conf.high { color: #7cffb2; }
.bk-conf.mid { color: #ffd479; }
.bk-conf.low { color: #ff9a9a; }
.ops { margin-top: 10px; display: flex; gap: 8px; }
.empty-hint { color: #8aa0c0; font-size: 13px; padding: 8px 0; }
.loading { color: #8ad6ff; padding: 8px 0; }
.ocr-err { margin-top: 10px; padding: 10px 12px; border: 1px solid rgba(255, 120, 120, 0.4); background: rgba(255, 90, 90, 0.12); border-radius: 10px; }
.err-title { color: #ff9a9a; font-weight: 600; font-size: 13px; margin-bottom: 6px; }
.err-hint { color: #c9b6b6; font-size: 12px; line-height: 1.7; }
.err-hint code { background: rgba(0,0,0,0.3); color: #ffd479; padding: 1px 5px; border-radius: 5px; font-size: 11px; }
.hist-item { padding: 10px; border: 1px solid rgba(120, 180, 255, 0.14); border-radius: 10px; margin-bottom: 10px; cursor: pointer; }
.hist-item:hover { border-color: rgba(90, 200, 255, 0.4); }
.hist-text { color: #cfe4ff; font-size: 13px; word-break: break-all; }
.hist-meta { color: #8aa0c0; font-size: 12px; margin-top: 4px; display: flex; justify-content: space-between; align-items: center; }
</style>
