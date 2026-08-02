package com.bi.agent.agent.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.service.SandboxQueryService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 沙箱写工具：在沙箱内新建一张空表（显式列定义）。
 *
 * <p>危险写操作，{@link #requiresConfirmation()} 返回 true。底层经 SandboxQueryService 校验列名
 * （合法标识符）与列类型（白名单：BIGINT / NUMERIC(18,2) / VARCHAR(n) / TEXT / DATE 等），
 * 杜绝 DDL 注入。
 */
public class SandboxCreateTableTool implements AgentTool {

    private final SandboxQueryService sandboxQueryService;
    private final Long scopeDbId;
    private final String operator;

    public SandboxCreateTableTool(SandboxQueryService sandboxQueryService, Long scopeDbId, String operator) {
        this.sandboxQueryService = sandboxQueryService;
        this.scopeDbId = scopeDbId;
        this.operator = operator;
    }

    @Override
    public String name() {
        return "create_table";
    }

    @Override
    public String description() {
        return "在沙箱内新建一张空表（显式指定列名与列类型）。"
                + "适用场景：用户要求『建一张新表，字段是 id/名称/金额...』。"
                + "列类型须为白名单：BIGINT / INTEGER / NUMERIC(18,2) / VARCHAR(50) / TEXT / DATE / TIMESTAMP / BOOLEAN。"
                + "此操作会修改沙箱结构，执行前需要用户确认。";
    }

    @Override
    public String jsonSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"tableName\":{\"type\":\"string\",\"description\":\"新建表短名（英文/数字/下划线，全沙箱唯一，如 monthly_sales）\"},"
                + "\"columns\":{\"type\":\"array\",\"description\":\"列定义数组，每项 {name:列名, type:列类型}\","
                + "\"items\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"type\":{\"type\":\"string\"}}}},\n"
                + "\"dbId\":{\"type\":\"integer\",\"description\":\"可选，目标沙箱库 id；不传则落入 Agent 当前锁定的沙箱库\"}},"
                + "\"required\":[\"tableName\",\"columns\"]}";
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public String call(String argsJson) {
        String tableName = null;
        List<Map<String, String>> columns = new ArrayList<>();
        Long dbId = scopeDbId;
        try {
            JSONObject a = JSON.parseObject(argsJson);
            tableName = a.getString("tableName");
            JSONArray cols = a.getJSONArray("columns");
            if (cols != null) {
                for (int i = 0; i < cols.size(); i++) {
                    JSONObject c = cols.getJSONObject(i);
                    Map<String, String> m = new java.util.HashMap<>();
                    m.put("name", c.getString("name"));
                    m.put("type", c.getString("type"));
                    columns.add(m);
                }
            }
            if (a.containsKey("dbId") && a.get("dbId") != null) {
                dbId = a.getLong("dbId");
            }
        } catch (Exception ignore) {
            // 解析失败则用默认
        }
        if (tableName == null || tableName.isBlank()) {
            return "缺少 tableName 参数";
        }
        if (columns.isEmpty()) {
            return "缺少 columns 参数";
        }
        try {
            JSONObject res = sandboxQueryService.createSandboxTable(tableName, columns, dbId, operator);
            return res.toJSONString();
        } catch (Exception e) {
            return "建表失败：" + e.getMessage();
        }
    }
}
