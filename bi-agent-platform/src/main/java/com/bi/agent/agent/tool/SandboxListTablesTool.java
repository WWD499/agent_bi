package com.bi.agent.agent.tool;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.service.SandboxQueryService;
import com.bi.agent.bi.vo.DbTableVo;

import java.util.List;

/**
 * Agent 沙箱工具：列出数据沙箱（sandbox schema）内的所有用户表。
 *
 * <p>仅在用户锁定「数据沙箱」数据源时由 {@code BiAgentService.buildTools} 注册，
 * 替代原 {@link ListTablesTool}（业务库版本），使模型在沙箱模式看到同名工具但指向 sandbox。
 */
public class SandboxListTablesTool implements AgentTool {

    private final SandboxQueryService sandboxQueryService;
    private final Long sandboxDbId;

    public SandboxListTablesTool(SandboxQueryService sandboxQueryService, Long sandboxDbId) {
        this.sandboxQueryService = sandboxQueryService;
        this.sandboxDbId = sandboxDbId;
    }

    @Override
    public String name() {
        return "list_tables";
    }

    @Override
    public String description() {
        return "列出当前作用域数据沙箱（sandbox schema）内的所有用户表，返回每张表的 tableName（短名，如 sales，"
                + "即写 SQL 时用的 sandbox.\"sales\"）、dbId（所属沙箱库 id）、displayName（显示名）。"
                + "分析/写表前先用本工具确认有哪些表及其短名。";
    }

    @Override
    public String jsonSchema() {
        return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
    }

    @Override
    public String call(String argsJson) {
        try {
            List<DbTableVo> tables = sandboxQueryService.listSandboxTables(sandboxDbId);
            JSONArray arr = new JSONArray();
            for (DbTableVo t : tables) {
                JSONObject o = new JSONObject();
                // 模型只需记住短名（tableName），配合 dbId 即可唯一定位；不再暴露拼接的物理名
                o.put("tableName", t.getTableName());
                o.put("dbId", t.getDbId());
                o.put("displayName", t.getDisplayName());
                arr.add(o);
            }
            JSONObject out = new JSONObject();
            out.put("sandbox", true);
            out.put("tableCount", tables.size());
            out.put("tables", arr);
            return out.toJSONString();
        } catch (Exception e) {
            return "查询沙箱表列表失败：" + e.getMessage();
        }
    }
}
