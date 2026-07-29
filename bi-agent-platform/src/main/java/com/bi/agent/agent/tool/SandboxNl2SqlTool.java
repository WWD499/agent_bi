package com.bi.agent.agent.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.service.SandboxQueryService;
import com.bi.agent.bi.vo.QueryResultVo;

import java.util.List;

/**
 * Agent 沙箱工具：自然语言 → SQL 并在沙箱执行（替代业务库版 Nl2SqlTool）。
 *
 * <p>包装 {@link SandboxQueryService#naturalLanguageQuerySandbox(String, Long)}，
 * 返回 SQL / 字段 / 数据行 / 推荐图表 / 数据解读，供模型综合成最终答案。
 */
public class SandboxNl2SqlTool implements AgentTool {

    private final SandboxQueryService sandboxQueryService;
    private final Long sandboxDbId;

    public SandboxNl2SqlTool(SandboxQueryService sandboxQueryService, Long sandboxDbId) {
        this.sandboxQueryService = sandboxQueryService;
        this.sandboxDbId = sandboxDbId;
    }

    @Override
    public String name() {
        return "nl2sql";
    }

    @Override
    public String description() {
        return "将自然语言问题转换为 SQL 并在数据沙箱执行，返回 SQL、字段、数据行、推荐图表类型与数据解读。"
                + "沙箱表必须用 sandbox. 前缀。用于回答沙箱内数据查询类问题。";
    }

    @Override
    public String jsonSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"question\":{\"type\":\"string\",\"description\":\"自然语言问题，例如『统计 sandbox.sales_2025 各区域销售额』\"}},"
                + "\"required\":[\"question\"]}";
    }

    @Override
    public String call(String argsJson) {
        String question = null;
        try {
            JSONObject a = JSON.parseObject(argsJson);
            question = a.getString("question");
        } catch (Exception ignore) {
            // 解析失败则用默认
        }
        if (question == null || question.isBlank()) {
            return "缺少 question 参数";
        }
        try {
            QueryResultVo vo = sandboxQueryService.naturalLanguageQuerySandbox(question, sandboxDbId);
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
            out.put("echartsOption", vo.getEchartsOption());
            out.put("interpretation", vo.getInterpretation());
            return out.toJSONString();
        } catch (Exception e) {
            return "执行查询失败：" + e.getMessage();
        }
    }
}
