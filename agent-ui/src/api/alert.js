import http from './http'

// BI 预警中心接口（基路径 /bi/alert）
const BASE = '/bi/alert'

// 规则列表
export function listRules() {
  return http.get(`${BASE}/rules`).then((r) => r.data.data)
}

// 单条规则
export function getRule(id) {
  return http.get(`${BASE}/rules/${id}`).then((r) => r.data.data)
}

// 新增规则
export function addRule(rule) {
  return http.post(`${BASE}/rules`, rule).then((r) => r.data.data)
}

// 更新规则
export function updateRule(rule) {
  return http.put(`${BASE}/rules`, rule).then((r) => r.data.data)
}

// 批量删除规则（ids: number[] → 路径变量 Long[]）
export function deleteRules(ids) {
  return http.delete(`${BASE}/rules/${ids.join(',')}`).then((r) => r.data.data)
}

// 立即触发一次检查
export function checkAlert() {
  return http.post(`${BASE}/check`).then((r) => r.data.data)
}

// 预警记录列表
export function listRecords() {
  return http.get(`${BASE}/records`).then((r) => r.data.data)
}

// 单条记录详情
export function getRecord(id) {
  return http.get(`${BASE}/records/${id}`).then((r) => r.data.data)
}

// 处理记录：POST /records/{id}/handle → AlertHandleReq{status, handledBy, handledRemark}
export function handleRecord(id, req) {
  return http.post(`${BASE}/records/${id}/handle`, req).then((r) => r.data.data)
}

// 批量删除预警记录（ids: number[] → 路径变量 Long[]，支持单条）
export function deleteRecords(ids) {
  return http.delete(`${BASE}/records/${ids.join(',')}`).then((r) => r.data.data)
}
