import http from './http'

// 数据源列表：GET /bi/datasource/list → List<BiDatasourceVO>
// 返回字段：id / name / type / host / port / databaseName / status（不含密码）
export function listDatasources() {
  return http.get('/bi/datasource/list').then((r) => r.data.data || [])
}

// 某数据源的表列表：GET /bi/datasource/tables?datasourceId=xx → List<DbTableVo>
// 返回字段：tableName / remarks（与后端 Agent 的 list_tables 同源，供 NL2SQL 表单联动「目标表名」下拉）
export function listTables(datasourceId) {
  return http.get('/bi/datasource/tables', { params: { datasourceId } }).then((r) => r.data.data || [])
}

// 某数据源指定表的字段列表：GET /bi/datasource/columns?datasourceId=xx&tableName=yy → List<DbColumnVo>
// 返回字段：columnName / dataType / remarks（供大屏可视化配置器「字段」下拉联动）
export function listColumns(datasourceId, tableName) {
  return http.get('/bi/datasource/columns', { params: { datasourceId, tableName } }).then((r) => r.data.data || [])
}

// 数据源详情：GET /bi/datasource/detail?id=xx → BiDatasourceVO（屏蔽密码/jdbcUrl）
export function getDatasourceDetail(id) {
  return http.get('/bi/datasource/detail', { params: { id } }).then((r) => r.data)
}

// 新建数据源：POST /bi/datasource（body = BiDatasource）→ Result{code,msg,data:id}
export function createDatasource(body) {
  return http.post('/bi/datasource', body).then((r) => r.data)
}

// 编辑数据源：PUT /bi/datasource（body = BiDatasource，密码留空则保留原值）→ Result
export function updateDatasource(body) {
  return http.put('/bi/datasource', body).then((r) => r.data)
}

// 删除单个数据源：DELETE /bi/datasource?id=xx → Result
export function deleteDatasource(id) {
  return http.delete('/bi/datasource', { params: { id } }).then((r) => r.data)
}

// 批量删除：DELETE /bi/datasource/batch?ids=1,2,3 → Result
export function batchDeleteDatasources(ids) {
  return http.delete('/bi/datasource/batch', { params: { ids: ids.join(',') } }).then((r) => r.data)
}

// 测试连接：POST /bi/datasource/test（body = BiDatasource）→ Result{data:{success,message}}
export function testDatasource(body) {
  return http.post('/bi/datasource/test', body).then((r) => r.data)
}

