import http from './http'

// RAG 知识库接口（基路径 /bi/knowledge）
const BASE = '/bi/knowledge'

// 新增知识条目
export function addKnowledge(k) {
  return http.post(`${BASE}/`, k).then((r) => r.data.data)
}

// 知识条目列表
export function listKnowledge() {
  return http.get(`${BASE}/list`).then((r) => r.data.data)
}

// 语义检索（POST，参数走 query string）：q / topK(默认5) / domain(可选)
export function searchKnowledge(q, topK = 5, domain = '') {
  return http
    .post(`${BASE}/search`, null, { params: { q, topK, domain } })
    .then((r) => r.data.data)
}

// 删除知识条目
export function deleteKnowledge(id) {
  return http.delete(`${BASE}/${id}`).then((r) => r.data.data)
}
