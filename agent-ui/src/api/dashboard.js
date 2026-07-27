import http from './http'

// ==================== 大屏 CRUD ====================

// 大屏列表：GET /bi/dashboard/list?name=&status= → List<BiDashboard>（不含 configJson/thumbnail 大字段）
export function listDashboards(params) {
  return http.get('/bi/dashboard/list', { params }).then((r) => r.data.data || [])
}

// 大屏详情：GET /bi/dashboard/detail?id=xx → BiDashboard（含 configJson/thumbnail）
export function getDashboard(id) {
  return http.get('/bi/dashboard/detail', { params: { id } }).then((r) => r.data.data)
}

// 新建大屏：POST /bi/dashboard/save（body = {name, description, configJson, status, isPublic}）→ Result{data:id}
export function createDashboard(body) {
  return http.post('/bi/dashboard/save', body).then((r) => r.data)
}

// 编辑大屏：PUT /bi/dashboard/save（body 含 id；未传 configJson/thumbnail 时后端保留旧值）→ Result
export function updateDashboard(body) {
  return http.put('/bi/dashboard/save', body).then((r) => r.data)
}

// 批量删除：DELETE /bi/dashboard/remove?ids=1,2,3 → Result
export function deleteDashboards(ids) {
  const arr = Array.isArray(ids) ? ids : [ids]
  return http.delete('/bi/dashboard/remove', { params: { ids: arr.join(',') } }).then((r) => r.data)
}

// 复制大屏：POST /bi/dashboard/copy?id=xx → Result{data:newId}
export function copyDashboard(id) {
  return http.post('/bi/dashboard/copy', null, { params: { id } }).then((r) => r.data)
}

// ==================== 公开分享（免登录） ====================

// 分享页获取大屏配置：GET /bi/dashboard/share?token=xx → BiDashboard（含 configJson）
export function shareDashboard(token) {
  return http.get('/bi/dashboard/share', { params: { token } }).then((r) => r.data.data)
}

// 分享页取数：POST /bi/dashboard/share-query?token=xx&widgetIndex=n → QueryResultVo
// 后端只按「已保存」的 widget 配置取数，忽略请求体，杜绝越权执行任意 SQL。
export function shareQuery(token, widgetIndex) {
  return http
    .post(`/bi/dashboard/share-query?token=${encodeURIComponent(token)}&widgetIndex=${widgetIndex}`, {})
    .then((r) => r.data.data)
}

// ==================== 出图查询（原有能力） ====================

// 大屏图表查询：自动根据入参选择路由
// - 若 req.sql 存在（旧面板/手写 SQL 兼容）：POST /bi/dashboard/query
// - 否则（可视化配置面板）：POST /bi/dashboard/query-by-config
// 返回字段：sql / columns / data / chartType / chartName / echartsOption / rowCount
// 注意：单个面板 SQL 失败会触发全局错误提示，调用方需 try/catch 隔离，避免整屏崩溃
export function runDashboardQuery(req) {
  const url = req && req.sql ? '/bi/dashboard/query' : '/bi/dashboard/query-by-config'
  return http.post(url, req).then((r) => r.data.data)
}
