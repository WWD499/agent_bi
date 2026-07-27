package com.bi.agent.agent.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.service.BiQueryService;
import com.bi.agent.bi.vo.QueryResultVo;

import java.util.List;

/**
 * Agent 工具：自然语言 → SQL 并执行取数（手写 ReAct 版）
 *
 * <p>包装现有 {@link BiQueryService#naturalLanguageQuery(String, Long, String)}，
 * 把返回的 SQL / 字段 / 数据行 / 推荐图表 / 数据解读 压缩成紧凑 JSON 回传，
 * 供模型综合成最终答案。数据行只回传前 20 行，避免撑爆上下文。
 */
public class Nl2SqlTool implements AgentTool {

    private final BiQueryService queryService;
    /** 用户在前端显式选择的数据源 ID（可 null）。非 null 时拥有最高优先级，模型参数不得覆盖 */
    private final Long userDsId;

    public Nl2SqlTool(BiQueryService queryService, Long userDsId) {
        this.queryService = queryService;
        this.userDsId = userDsId;
    }

    @Override
    public String name() {
        return "nl2sql";
    }

    @Override
    public String description() {
        return "将自然语言问题转换为 SQL 并在业务数据库执行，返回 SQL、字段、数据行、"
                + "推荐图表类型与数据解读。用于回答『各区域销售额』『某表各部门人数分布』等数据查询类问题。";
    }

    @Override
    public String jsonSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"question\":{\"type\":\"string\",\"description\":\"用户的自然语言问题，例如『统计 demo_employee 各部门的人数』\"},"
                + "\"datasourceId\":{\"type\":\"integer\",\"description\":\"数据源ID，整数；可省略，默认使用用户已选择的数据源\"}},"
                + "\"required\":[\"question\"]}";
    }

    @Override
    public String call(String argsJson) {
        String question = null;
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
        try {
            JSONObject a = JSON.parseObject(argsJson);
            question = a.getString("question");
        } catch (Exception e) {
            return "参数解析失败：" + e.getMessage();
        }
        if (question == null || question.isBlank()) {
            return "缺少 question 参数";
        }
        try {
            QueryResultVo vo = queryService.naturalLanguageQuery(question, dsId, null);
            JSONObject out = new JSONObject();
            out.put("sql", vo.getSql());
            out.put("columns", vo.getColumns());
            out.put("rowCount", vo.getRowCount());
            out.put("chartType", vo.getChartType());
            out.put("chartName", vo.getChartName());
            List<JSONObject> rows = vo.getData();
            int cap = Math.min(rows == null ? 0 : rows.size(), 20);
            JSONArray sample = new JSONArray();
            if (rows != null) {
                for (int i = 0; i < cap; i++) {
                    sample.add(rows.get(i));
                }
            }
            out.put("data", sample);
            out.put("interpretation", vo.getInterpretation());
            return out.toJSONString();
        } catch (Exception e) {
            return "执行查询失败：" + e.getMessage();
        }
    }
}
