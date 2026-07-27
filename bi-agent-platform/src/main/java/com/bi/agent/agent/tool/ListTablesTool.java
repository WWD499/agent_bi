package com.bi.agent.agent.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.service.IBiDatasourceService;
import com.bi.agent.bi.vo.DbTableVo;

import java.util.List;

/**
 * Agent 工具：列出指定数据源的所有表（手写 ReAct 版，不依赖 Spring AI）
 *
 * <p>包装现有 {@link IBiDatasourceService#listTables(Long)}。
 * 让 Agent 在写 SQL 前先「看一眼」库里有哪些表，避免编造表名。
 */
public class ListTablesTool implements AgentTool {

    private final IBiDatasourceService datasourceService;
    /** 用户在前端显式选择的数据源 ID（可 null）。非 null 时拥有最高优先级，模型参数不得覆盖 */
    private final Long userDsId;

    public ListTablesTool(IBiDatasourceService datasourceService, Long userDsId) {
        this.datasourceService = datasourceService;
        this.userDsId = userDsId;
    }

    @Override
    public String name() {
        return "list_tables";
    }

    @Override
    public String description() {
        return "列出指定数据源当前数据库中的所有表（含表注释），用于了解有哪些数据表可查询。"
                + "调用前若不知道数据源ID，默认用用户已选择的数据源。";
    }

    @Override
    public String jsonSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"datasourceId\":{\"type\":\"integer\",\"description\":\"数据源ID，整数；可省略，默认使用用户已选择的数据源\"}},"
                + "\"required\":[\"datasourceId\"]}";
    }

    @Override
    public String call(String argsJson) {
        // 用户已选数据源 → 最高优先级，忽略模型参数覆盖
        long dsId = (userDsId != null) ? userDsId : 1L;
        if (userDsId == null && argsJson != null && !argsJson.isBlank()) {
            try {
                JSONObject a = JSON.parseObject(argsJson);
                if (a.containsKey("datasourceId")) {
                    dsId = a.getLongValue("datasourceId");
                }
            } catch (Exception ignore) {
                // 参数解析失败则用默认数据源
            }
        }
        try {
            List<DbTableVo> tables = datasourceService.listTables(dsId);
            JSONArray arr = new JSONArray();
            for (DbTableVo t : tables) {
                JSONObject o = new JSONObject();
                o.put("tableName", t.getTableName());
                o.put("remarks", t.getRemarks());
                arr.add(o);
            }
            JSONObject out = new JSONObject();
            out.put("datasourceId", dsId);
            out.put("tableCount", tables.size());
            out.put("tables", arr);
            return out.toJSONString();
        } catch (Exception e) {
            return "查询表列表失败：" + e.getMessage();
        }
    }
}
