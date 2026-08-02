package com.bi.agent.agent.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.service.SandboxQueryService;

/**
 * Agent 沙箱写工具：删除一张沙箱表（DDL + 元数据）。
 *
 * <p>危险写操作，{@link #requiresConfirmation()} 返回 true，删除前必须用户确认。
 * 支持两种定位方式（按可靠程度排序）：
 * <ol>
 *   <li>dbId + tableName（短名）：最可靠，避免模型误吃 physicalName 中的双下划线；</li>
 *   <li>physicalName：物理表名，必须完整保留双下划线（如 sales_dm__products）。</li>
 * </ol>
 * 工具内部会做兜底解析，找不到对应表时返回失败，杜绝「表名错误却报成功」的假删除。
 */
public class SandboxDropTableTool implements AgentTool {

    private final SandboxQueryService sandboxQueryService;
    private final String operator;

    public SandboxDropTableTool(SandboxQueryService sandboxQueryService, String operator) {
        this.sandboxQueryService = sandboxQueryService;
        this.operator = operator;
    }

    @Override
    public String name() {
        return "drop_table";
    }

    @Override
    public String description() {
        return "删除一张沙箱表（同时删除其物理表与元数据）。"
                + "适用场景：用户明确要求『删掉某张沙箱表』。"
                + "优先传入 dbId（沙箱库 id）和 tableName（短名，即 list_tables 返回的 tableName，如 products）；"
                + "沙箱内物理名与短名已统一为同一名字，直接传 tableName 即可，无需任何拼接。"
                + "此操作不可恢复，执行前需要用户确认。";
    }

    @Override
    public String jsonSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"dbId\":{\"type\":\"integer\",\"description\":\"沙箱库 id（优先配合 tableName 使用）\"},"
                + "\"tableName\":{\"type\":\"string\",\"description\":\"待删除表的短名（如 products），来自 list_tables 的返回\"},"
                + "\"physicalName\":{\"type\":\"string\",\"description\":\"可选，表的物理名（新表与短名相同），与 tableName 二选一即可\"}},"
                + "\"required\":[]}";
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public String call(String argsJson) {
        Long dbId = null;
        String tableName = null;
        String physicalName = null;
        try {
            JSONObject a = JSON.parseObject(argsJson);
            if (a.containsKey("dbId") && a.get("dbId") != null) {
                dbId = a.getLong("dbId");
            }
            tableName = a.getString("tableName");
            physicalName = a.getString("physicalName");
        } catch (Exception ignore) {
            // 解析失败则用默认
        }
        if ((tableName == null || tableName.isBlank()) && (physicalName == null || physicalName.isBlank())) {
            return "缺少表名参数：请提供 dbId+tableName 或 physicalName";
        }
        try {
            sandboxQueryService.dropSandboxTable(dbId, tableName, physicalName, operator);
            JSONObject out = new JSONObject();
            out.put("success", true);
            out.put("dropped", physicalName != null && !physicalName.isBlank() ? physicalName : tableName);
            return out.toJSONString();
        } catch (Exception e) {
            return "删表失败：" + e.getMessage();
        }
    }
}
