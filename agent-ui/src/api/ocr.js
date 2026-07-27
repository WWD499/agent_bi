import http from './http'

// 大模型相关调用较慢，单独放宽超时（覆盖全局 30s 默认值）
const LLM_TIMEOUT = 120000   // 抽取 / 向量化：同步等 deepseek-v3 返回，长文本可能需 60s+
const OCR_TIMEOUT = 60000    // 本地 PaddleOCR：首次加载模型可能耗时较久

// 上传图片 OCR 识别：POST /api/bi/ocr (multipart: file)
export function uploadOcr(file) {
  const form = new FormData()
  form.append('file', file)
  return http.post('/bi/ocr', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: OCR_TIMEOUT
  }).then((r) => r.data.data)
}

// 结构化抽取：POST /api/bi/ocr/extract { text, schema? }
export function extractOcr(text, schema) {
  return http.post('/bi/ocr/extract', { text, schema }, { timeout: LLM_TIMEOUT }).then((r) => r.data.data)
}

// 写入 RAG 知识库：POST /api/bi/ocr/to-knowledge { recordId?, title, content }
export function ocrToKnowledge(recordId, title, content) {
  return http.post('/bi/ocr/to-knowledge', { recordId, title, content }, { timeout: LLM_TIMEOUT }).then((r) => r.data.data)
}

// 新增历史记录：POST /api/bi/ocr/record
export function addOcrRecord(rec) {
  return http.post('/bi/ocr/record', rec).then((r) => r.data.data)
}

// 历史列表：GET /api/bi/ocr/record/list
export function listOcrRecords(params) {
  return http.get('/bi/ocr/record/list', { params }).then((r) => r.data.data || [])
}

// 历史详情：GET /api/bi/ocr/record/{id}
export function getOcrRecord(id) {
  return http.get('/bi/ocr/record/' + id).then((r) => r.data.data)
}

// 删除历史：DELETE /api/bi/ocr/record/{id}
export function deleteOcrRecord(id) {
  return http.delete('/bi/ocr/record/' + id).then((r) => r.data.data)
}
