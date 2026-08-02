package com.bi.agent.agent.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.service.SandboxImportService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 沙箱写工具：向已有沙箱表导入（追加或覆盖）数据行。
 *
 * <p>解决「Agent 建空表后无法导入数据」的问题：用户让 Agent 创建表结构后，
 * 可继续通过本工具把结构化数据写入该表，支持追加（append）与覆盖（replace）两种模式。
 * 数据格式为 JSON 对象数组，每项 {列名: 值}，列名大小写不敏感。
 *
 * <p>属于写操作，执行前需用户确认。
 */
public class SandboxImportDataTool implements AgentTool {

    private final SandboxImportService sandboxImportService;
    /** 作用域沙箱库 id（Agent 当前锁定的沙箱库，辅助定位） */
    private final Long scopeDbId;
    /** 操作人（审计留痕） */
    private final String operator;

    public SandboxImportDataTool(SandboxImportService sandboxImportService, Long scopeDbId, String operator) {
        this.sandboxImportService = sandboxImportService;
        this.scopeDbId = scopeDbId;
        this.operator = operator;
    }

    @Override
    public String name() {
        return "import_data";
    }

    @Override
    public String description() {
        return "向一张已有的沙箱表导入数据行（追加或覆盖）。"
                + "适用场景：用户已建好空表，或想往已有表追加/更新数据。"
                + "数据以 JSON 对象数组传入，每项 {列名: 值}；列名大小写不敏感，缺失列自动补 null。"
                + "mode=append（默认）表示追加；mode=replace 会先清空表再写入。"
                + "此操作会修改沙箱数据，执行前需要用户确认。";
    }

    @Override
    public String jsonSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"tableName\":{\"type\":\"string\",\"description\":\"目标沙箱表短名（来自 list_tables 的 tableName，如 emp）\"},"
                + "\"rows\":{\"type\":\"array\",\"description\":\"数据行，每项为 {列名: 值} 的对象数组。例如 [{\\\"name\\\":\\\"张三\\\",\\\"age\\\":30},...]\","
                + "\"items\":{\"type\":\"object\"}},"
                + "\"mode\":{\"type\":\"string\",\"description\":\"append（追加，默认）或 replace（先清空再插入）\"},"
                + "\"dbId\":{\"type\":\"integer\",\"description\":\"可选，目标沙箱库 id；不传则使用 Agent 当前锁定的沙箱库\"}},"
                + "\"required\":[\"tableName\",\"rows\"]}";
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public String call(String argsJson) {
        String tableName = null;
        List<Map<String, Object>> rows = new ArrayList<>();
        String mode = "append";
        Long dbId = scopeDbId;
        try {
            JSONObject a = JSON.parseObject(argsJson);
            tableName = a.getString("tableName");
            JSONArray arr = a.getJSONArray("rows");
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject row = arr.getJSONObject(i);
                    if (row == null) {
                        continue;
                    }
                    Map<String, Object> m = new HashMap<>();
                    for (Map.Entry<String, Object> e : row.entrySet()) {
                        m.put(e.getKey(), e.getValue());
                    }
                    rows.add(m);
                }
            }
            if (a.containsKey("mode") && a.get("mode") != null) {
                mode = a.getString("mode");
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
        if (rows.isEmpty()) {
            return "缺少 rows 参数或数据行为空";
        }
        try {
            JSONObject res = sandboxImportService.importDataIntoTable(tableName, rows, dbId, mode, operator);
            return res.toJSONString();
        } catch (Exception e) {
            return "导入数据失败：" + e.getMessage();
        }
    }
}
