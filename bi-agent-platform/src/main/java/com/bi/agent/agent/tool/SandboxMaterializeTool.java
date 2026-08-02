package com.bi.agent.agent.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.service.SandboxQueryService;

import java.util.List;
import java.util.Map;

/**
 * Agent 沙箱写工具：把一条只读 SELECT 的结果「落表」（CTAS，CREATE TABLE ... AS SELECT）。
 *
 * <p>属于危险写操作，{@link #requiresConfirmation()} 返回 true，Agent 真正执行前会先推 confirm 事件
 * 阻塞等待用户在前端「同意 / 拒绝」。底层经 SandboxQueryService 强制：SELECT 部分只读校验 +
 * sandbox 边界校验（只可读取 sandbox schema 内的表），目标表物理名强制落在 sandbox schema。
 */
public class SandboxMaterializeTool implements AgentTool {

    private final SandboxQueryService sandboxQueryService;
    /** 作用域沙箱库 id（Agent 当前锁定的沙箱库；新表落在此库下） */
    private final Long scopeDbId;
    /** 操作人（用于审计留痕） */
    private final String operator;

    public SandboxMaterializeTool(SandboxQueryService sandboxQueryService, Long scopeDbId, String operator) {
        this.sandboxQueryService = sandboxQueryService;
        this.scopeDbId = scopeDbId;
        this.operator = operator;
    }

    @Override
    public String name() {
        return "materialize_table";
    }

    @Override
    public String description() {
        return "把一个只读 SELECT 查询的结果落为一张新的沙箱表（CTAS）。"
                + "适用场景：用户要求『把某查询结果存成一张表 / 物化视图 / 派生表』以便后续分析。"
                + "sql 必须是只读 SELECT，表名用 sandbox. 全限定（如 sandbox.sales）。"
                + "此操作会修改沙箱结构，执行前需要用户确认。";
    }

    @Override
    public String jsonSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"sql\":{\"type\":\"string\",\"description\":\"只读 SELECT，表名用 sandbox. 前缀全限定\"},"
                + "\"targetTableName\":{\"type\":\"string\",\"description\":\"新建的沙箱表短名（英文/数字/下划线）\"},"
                + "\"dbId\":{\"type\":\"integer\",\"description\":\"可选，目标沙箱库 id；不传则落入 Agent 当前锁定的沙箱库\"}},"
                + "\"required\":[\"sql\",\"targetTableName\"]}";
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public String call(String argsJson) {
        String sql = null;
        String targetTableName = null;
        Long dbId = scopeDbId;
        try {
            JSONObject a = JSON.parseObject(argsJson);
            sql = a.getString("sql");
            targetTableName = a.getString("targetTableName");
            if (a.containsKey("dbId") && a.get("dbId") != null) {
                dbId = a.getLong("dbId");
            }
        } catch (Exception ignore) {
            // 解析失败则用默认
        }
        if (sql == null || sql.isBlank()) {
            return "缺少 sql 参数";
        }
        if (targetTableName == null || targetTableName.isBlank()) {
            return "缺少 targetTableName 参数";
        }
        try {
            JSONObject res = sandboxQueryService.materializeTable(sql, targetTableName, dbId, operator);
            return res.toJSONString();
        } catch (Exception e) {
            return "落表失败：" + e.getMessage();
        }
    }
}
