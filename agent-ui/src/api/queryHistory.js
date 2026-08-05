import http from './http'

// NL2SQL 查询历史接口（基路径 /bi/query/history）
const BASE = '/bi/query/history'

// 分页列表（params: { datasourceId?, keyword?, page, size }）
export function listQueryHistory(params) {
  return http.get(`${BASE}/list`, { params }).then((r) => r.data.data)
}

// 单条详情
export function getQueryHistory(id) {
  return http.get(`${BASE}/${id}`).then((r) => r.data.data)
}

// 删除单条
export function deleteQueryHistory(id) {
  return http.delete(`${BASE}/${id}`).then((r) => r.data.data)
}

// 批量删除（ids: number[]）
export function deleteQueryHistories(ids) {
  return http.delete(`${BASE}/${ids.join(',')}`).then((r) => r.data.data)
}
