package com.bi.agent.agent.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.service.SandboxQueryService;
import com.bi.agent.bi.vo.DbColumnVo;

import java.util.List;

/**
 * Agent 沙箱工具：列出沙箱某表的字段结构（替代业务库版 ListColumnsTool）。
 */
public class SandboxListColumnsTool implements AgentTool {

    private final SandboxQueryService sandboxQueryService;
    private final Long scopeDbId;

    public SandboxListColumnsTool(SandboxQueryService sandboxQueryService, Long scopeDbId) {
        this.sandboxQueryService = sandboxQueryService;
        this.scopeDbId = scopeDbId;
    }

    @Override
    public String name() {
        return "list_columns";
    }

    @Override
    public String description() {
        return "列出数据沙箱某张表的字段（列名、类型）。调用前先用 list_tables 确认表名，"
                + "传入的 tableName 必须是 list_tables 返回的 tableName（短名，如 sales，即 sandbox.\"sales\"）。";
    }

    @Override
    public String jsonSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"tableName\":{\"type\":\"string\",\"description\":\"沙箱表名（短名），例如 sales（来自 list_tables 的返回，对应 sandbox.\\\"sales\\\"）\"}},"
                + "\"required\":[\"tableName\"]}";
    }

    @Override
    public String call(String argsJson) {
        String tableName = null;
        try {
            JSONObject a = JSON.parseObject(argsJson);
            tableName = a.getString("tableName");
        } catch (Exception ignore) {
            // 参数解析失败则用默认
        }
        if (tableName == null || tableName.isBlank()) {
            return "缺少 tableName 参数";
        }
        try {
            List<DbColumnVo> cols = sandboxQueryService.listSandboxColumns(scopeDbId, tableName);
            JSONArray arr = new JSONArray();
            for (DbColumnVo c : cols) {
                JSONObject o = new JSONObject();
                o.put("columnName", c.getColumnName());
                if (c.getLabel() != null) {
                    o.put("label", c.getLabel());
                }
                o.put("dataType", c.getDataType());
                arr.add(o);
            }
            JSONObject out = new JSONObject();
            out.put("tableName", tableName);
            out.put("columnCount", cols.size());
            out.put("columns", arr);
            return out.toJSONString();
        } catch (Exception e) {
            return "查询沙箱字段失败：" + e.getMessage();
        }
    }
}
