import http from './http'

// 数据沙箱接口封装（M1：粘贴导入 + 只读查询；逻辑命名空间「沙箱库」）
// 后端返回结构统一为 { code, msg, data }，http 拦截器已解开为 r.data.data

// 沙箱库（逻辑命名空间）
// 列出全部沙箱库：GET /bi/sandbox/db
export function listSandboxDbs() {
  return http.get('/bi/sandbox/db').then((r) => r.data.data)
}

// 新建沙箱库：POST /bi/sandbox/db  body { name, dbKey, remark? }
export function createSandboxDb(req) {
  return http.post('/bi/sandbox/db', req).then((r) => r.data.data)
}

// 删除沙箱库（级联删表）：DELETE /bi/sandbox/db/{id}
export function dropSandboxDb(id) {
  return http.delete(`/bi/sandbox/db/${id}`).then((r) => r.data.data)
}

// 粘贴文本导入：POST /bi/sandbox/import
// req: { rawText, tableName, separator?('' 自动检测 / ',' / '\t'), dbId? }
// 返回: { tableName(物理名), shortName, rowCount, columns:[{name,type}] }
export function importSandboxText(req) {
  return http.post('/bi/sandbox/import', req).then((r) => r.data.data)
}

// 从已配置数据源批量导入：POST /bi/sandbox/import-datasource
// req: { datasourceId:Long, tables:[源表名...], dbId? }
// 返回: [ { tableName, rowCount, columns:[{name,type}] }, ... ]
export function importSandboxFromDatasource(req) {
  return http.post('/bi/sandbox/import-datasource', req).then((r) => r.data.data)
}

// 列出沙箱表：GET /bi/sandbox/tables?dbId=（不传则全部库）
// 返回: [ { tableName(物理名), physicalName, displayName, dbId, dbKey }, ... ]
export function listSandboxTables(dbId) {
  const params = dbId != null ? { dbId } : {}
  return http.get('/bi/sandbox/tables', { params }).then((r) => r.data.data)
}

// 列出某物理表字段：GET /bi/sandbox/tables/{physicalName}/columns?dbId=（按库作用域隔离解析）
export function getSandboxColumns(physicalName, dbId) {
  const params = dbId != null ? { dbId } : {}
  return http.get(`/bi/sandbox/tables/${physicalName}/columns`, { params }).then((r) => r.data.data)
}

// 预览某物理表前 N 行：GET /bi/sandbox/tables/{physicalName}/data?limit=100&dbId=（按库作用域隔离解析）
export function getSandboxData(physicalName, limit = 100, dbId) {
  const params = { limit }
  if (dbId != null) params.dbId = dbId
  return http.get(`/bi/sandbox/tables/${physicalName}/data`, { params }).then((r) => r.data.data)
}

// 在沙箱内执行只读 SQL：POST /bi/sandbox/execute
// req: { sql } -> QueryResultVo { sql, columns, data, rowCount }
export function executeSandboxSql(req) {
  return http.post('/bi/sandbox/execute', req).then((r) => r.data.data)
}

// 删除沙箱物理表：DELETE /bi/sandbox/tables/{physicalName}
export function dropSandboxTable(physicalName) {
  return http.delete(`/bi/sandbox/tables/${physicalName}`).then((r) => r.data.data)
}

// 修改沙箱表的用户显示名（可中文，如 部门表/员工表）：POST /bi/sandbox/tables/{physicalName}/display-name
// req: { displayName } -> 200 即可
export function updateSandboxDisplayName(physicalName, displayName) {
  return http.post(`/bi/sandbox/tables/${physicalName}/display-name`, { displayName }).then((r) => r.data.data)
}

// 文件上传导入（M3）：支持 Excel(.xlsx/.xls) 与 CSV(.csv)。
// 解析后自动推断列类型、建表写入沙箱并登记审计。
// form-data：file=File, tableName=表短名, dbId=目标沙箱库id(可选)
export function importSandboxFile(file, tableName, dbId) {
  const form = new FormData()
  form.append('file', file)
  form.append('tableName', tableName || '')
  if (dbId != null) form.append('dbId', dbId)
  // 文件上传不进 axios 拦截器的 JSON 处理；超时放宽到 120s（大文件解析可能较慢）
  return http
    .post('/bi/sandbox/import-file', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120000
    })
    .then((r) => r.data.data)
}

// 沙箱操作审计列表（M3）：返回最近 N 条审计记录。
// GET /bi/sandbox/audit?limit=（默认 50，最大 500）
export function listSandboxAudit(limit = 50) {
  return http.get('/bi/sandbox/audit', { params: { limit } }).then((r) => r.data.data)
}
