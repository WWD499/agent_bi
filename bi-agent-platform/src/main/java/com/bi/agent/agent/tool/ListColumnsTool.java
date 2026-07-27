package com.bi.agent.agent.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.service.IBiDatasourceService;
import com.bi.agent.bi.vo.DbColumnVo;

import java.util.List;

/**
 * Agent 工具：列出指定表的所有字段（手写 ReAct 版）
 *
 * <p>包装现有 {@link IBiDatasourceService#listColumns(Long, String)}。
 * 让 Agent 在拼 SQL 前确认字段名 / 类型 / 注释，避免编造列。
 */
public class ListColumnsTool implements AgentTool {

    private final IBiDatasourceService datasourceService;
    /** 用户在前端显式选择的数据源 ID（可 null）。非 null 时拥有最高优先级，模型参数不得覆盖 */
    private final Long userDsId;

    public ListColumnsTool(IBiDatasourceService datasourceService, Long userDsId) {
        this.datasourceService = datasourceService;
        this.userDsId = userDsId;
    }

    @Override
    public String name() {
        return "list_columns";
    }

    @Override
    public String description() {
        return "列出指定数据源中某张表的所有字段（列名、类型、注释）。"
                + "在拼 SQL 前确认字段名/类型时使用。";
    }

    @Override
    public String jsonSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"datasourceId\":{\"type\":\"integer\",\"description\":\"数据源ID，整数；可省略，默认使用用户已选择的数据源\"},"
                + "\"tableName\":{\"type\":\"string\",\"description\":\"表名\"}},"
                + "\"required\":[\"datasourceId\",\"tableName\"]}";
    }

    @Override
    public String call(String argsJson) {
        // 用户已选数据源 → 最高优先级，忽略模型参数覆盖
        long dsId = (userDsId != null) ? userDsId : 1L;
        if (userDsId == null) {
            try {
                JSONObject a = JSON.parseObject(argsJson);
                if (a.containsKey("datasourceId")) {
                    dsId = a.getLongValue("datasourceId");
                }
            } catch (Exception ignore) {
            }
        }
        String table = null;
        try {
            JSONObject a = JSON.parseObject(argsJson);
            table = a.getString("tableName");
        } catch (Exception e) {
            return "参数解析失败：" + e.getMessage();
        }
        if (table == null || table.isBlank()) {
            return "缺少 tableName 参数";
        }
        try {
            List<DbColumnVo> cols = datasourceService.listColumns(dsId, table);
            JSONArray arr = new JSONArray();
            for (DbColumnVo c : cols) {
                JSONObject o = new JSONObject();
                o.put("columnName", c.getColumnName());
                o.put("dataType", c.getDataType());
                o.put("remarks", c.getRemarks());
                arr.add(o);
            }
            JSONObject out = new JSONObject();
            out.put("tableName", table);
            out.put("columnCount", cols.size());
            out.put("columns", arr);
            return out.toJSONString();
        } catch (Exception e) {
            return "查询字段失败：" + e.getMessage();
        }
    }
}
