import http from './http'

// NL2SQL 自然语言查询：POST /bi/query/nl2sql
// 入参 BiQueryReq{query, datasourceId, tableName?} → QueryResultVo
// 返回体含 sql / columns / data / chartType / chartName / echartsOption / interpretation / rowCount
// 注意：此接口包含 LLM 调用（5~15s）+ 数据探查（~100ms），必须覆盖全局 10s 超时
export function nl2sql(req) {
  return http.post('/bi/query/nl2sql', req, { timeout: 60000 }).then((r) => r.data.data)
}
