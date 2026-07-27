package com.bi.agent.agent.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.service.BiQueryService;
import com.bi.agent.bi.vo.QueryResultVo;

import java.util.List;

/**
 * Agent 工具：执行只读 SQL 取数（手写 ReAct 版）
 *
 * <p>包装新增的 {@link BiQueryService#runReadOnlySql(Long, String)}（内部经
 * {@code SqlValidator} 强制只读校验：仅允许 SELECT/WITH，禁止一切写操作与
 * 多语句）。Agent 自己拼好 SQL 后取数、或核对数据时使用。
 */
public class RunSqlTool implements AgentTool {

    private final BiQueryService queryService;
    /** 用户在前端显式选择的数据源 ID（可 null）。非 null 时拥有最高优先级，模型参数不得覆盖 */
    private final Long userDsId;

    public RunSqlTool(BiQueryService queryService, Long userDsId) {
        this.queryService = queryService;
        this.userDsId = userDsId;
    }

    @Override
    public String name() {
        return "run_sql";
    }

    @Override
    public String description() {
        return "在指定数据源上执行一条【只读】SQL（仅 SELECT/WITH），返回字段与数据行。"
                + "用于 Agent 自己拼好 SQL 后取数、或核对数据。绝不可用于任何写操作。";
    }

    @Override
    public String jsonSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"datasourceId\":{\"type\":\"integer\",\"description\":\"数据源ID，整数；可省略，默认使用用户已选择的数据源\"},"
                + "\"sql\":{\"type\":\"string\",\"description\":\"要执行的只读 SQL 语句\"}},"
                + "\"required\":[\"datasourceId\",\"sql\"]}";
    }

    @Override
    public String call(String argsJson) {
        // 用户已选数据源 → 最高优先级，忽略模型参数覆盖
        long dsId = (userDsId != null) ? userDsId : 1L;
        if (userDsId == null) {
            try {
                JSONObject a = JSON.parseObject(argsJson);
                if (userDsId == null && a.containsKey("datasourceId")) {
                    dsId = a.getLongValue("datasourceId");
                }
            } catch (Exception ignore) {
            }
        }
        String sql = null;
        try {
            JSONObject a = JSON.parseObject(argsJson);
            sql = a.getString("sql");
        } catch (Exception e) {
            return "参数解析失败：" + e.getMessage();
        }
        if (sql == null || sql.isBlank()) {
            return "缺少 sql 参数";
        }
        try {
            QueryResultVo vo = queryService.runReadOnlySql(dsId, sql);
            JSONObject out = new JSONObject();
            out.put("sql", vo.getSql());
            out.put("columns", vo.getColumns());
            out.put("rowCount", vo.getRowCount());
            List<JSONObject> rows = vo.getData();
            int cap = Math.min(rows == null ? 0 : rows.size(), 20);
            JSONArray sample = new JSONArray();
            if (rows != null) {
                for (int i = 0; i < cap; i++) {
                    sample.add(rows.get(i));
                }
            }
            out.put("data", sample);
            return out.toJSONString();
        } catch (Exception e) {
            return "执行失败：" + e.getMessage();
        }
    }
}
