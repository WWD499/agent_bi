package com.bi.agent.agent.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.service.SandboxQueryService;

/**
 * Agent 沙箱写工具：修改已有沙箱表的显示名（中文别名）。
 *
 * <p>参考 Quick BI 等 BI 产品的「数据集显示名」概念：物理表名保持为英文标识符，
 * 但用户可给表设置一个中文显示名（如把 emp 改名为「员工表」），仅改元数据
 * bi_sandbox_table.display_name，不影响物理表名与 SQL。
 *
 * <p>属于低风险元数据写操作，但为了与 create_table / drop_table 等写工具保持一致，
 * 仍要求用户确认后再执行。
 */
public class SandboxUpdateTableTool implements AgentTool {

    private final SandboxQueryService sandboxQueryService;
    /** 作用域沙箱库 id（Agent 当前锁定的沙箱库，辅助定位） */
    private final Long scopeDbId;
    /** 操作人（审计留痕） */
    private final String operator;

    public SandboxUpdateTableTool(SandboxQueryService sandboxQueryService, Long scopeDbId, String operator) {
        this.sandboxQueryService = sandboxQueryService;
        this.scopeDbId = scopeDbId;
        this.operator = operator;
    }

    @Override
    public String name() {
        return "update_table";
    }

    @Override
    public String description() {
        return "修改一张已有沙箱表的显示名（中文别名）。"
                + "适用场景：用户说『把 XXX 表改名为 XXX / 设置 XXX 表的显示名为 XXX』。"
                + "仅更新元数据 display_name，不影响物理表名与 SQL。"
                + "参数 tableName 是 list_tables 返回的短名（如 emp）。";
    }

    @Override
    public String jsonSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"tableName\":{\"type\":\"string\",\"description\":\"目标沙箱表短名（来自 list_tables 的 tableName，如 emp）\"},"
                + "\"displayName\":{\"type\":\"string\",\"description\":\"新的显示名（可中文，如 员工表；留空则回退到短名）\"},"
                + "\"dbId\":{\"type\":\"integer\",\"description\":\"可选，目标沙箱库 id；不传则使用 Agent 当前锁定的沙箱库\"}},"
                + "\"required\":[\"tableName\",\"displayName\"]}";
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public String call(String argsJson) {
        String tableName = null;
        String displayName = null;
        Long dbId = scopeDbId;
        try {
            JSONObject a = JSON.parseObject(argsJson);
            tableName = a.getString("tableName");
            displayName = a.getString("displayName");
            if (a.containsKey("dbId") && a.get("dbId") != null) {
                dbId = a.getLong("dbId");
            }
        } catch (Exception ignore) {
            // 解析失败则用默认
        }
        if (tableName == null || tableName.isBlank()) {
            return "缺少 tableName 参数";
        }
        if (displayName == null) {
            return "缺少 displayName 参数";
        }
        try {
            sandboxQueryService.renameSandboxTableDisplay(dbId, tableName, displayName);
            JSONObject out = new JSONObject();
            out.put("success", true);
            out.put("tableName", tableName);
            out.put("displayName", displayName);
            return out.toJSONString();
        } catch (Exception e) {
            return "修改显示名失败：" + e.getMessage();
        }
    }
}
