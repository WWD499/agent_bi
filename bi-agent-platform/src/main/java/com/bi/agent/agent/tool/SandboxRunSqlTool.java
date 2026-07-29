package com.bi.agent.agent.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.service.SandboxQueryService;
import com.bi.agent.bi.vo.QueryResultVo;

import java.util.List;

/**
 * Agent 沙箱工具：在沙箱内执行【只读】SQL（替代业务库版 RunSqlTool）。
 *
 * <p>表名必须以 {@code sandbox.} 全限定（如 sandbox.sales_2025）；底层经 SqlValidator 只读校验
 * + sandbox 边界校验，杜绝越权访问 public 业务表或 bi_* 系统表。绝不可用于写操作（M1 不开放写）。
 */
public class SandboxRunSqlTool implements AgentTool {

    private final SandboxQueryService sandboxQueryService;

    public SandboxRunSqlTool(SandboxQueryService sandboxQueryService) {
        this.sandboxQueryService = sandboxQueryService;
    }

    @Override
    public String name() {
        return "run_sql";
    }

    @Override
    public String description() {
        return "在数据沙箱内执行一条【只读】SQL（仅 SELECT/WITH），表名必须用 sandbox. 全限定"
                + "（如 sandbox.sales_2025）。返回字段与数据行。绝不可用于任何写操作。";
    }

    @Override
    public String jsonSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"sql\":{\"type\":\"string\",\"description\":\"只读 SQL，表名用 sandbox. 前缀\"}},"
                + "\"required\":[\"sql\"]}";
    }

    @Override
    public String call(String argsJson) {
        String sql = null;
        try {
            JSONObject a = JSON.parseObject(argsJson);
            sql = a.getString("sql");
        } catch (Exception ignore) {
            // 解析失败则用默认
        }
        if (sql == null || sql.isBlank()) {
            return "缺少 sql 参数";
        }
        try {
            QueryResultVo vo = sandboxQueryService.runSandboxReadOnlySql(sql);
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
