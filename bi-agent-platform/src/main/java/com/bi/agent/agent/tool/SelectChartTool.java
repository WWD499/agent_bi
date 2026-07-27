package com.bi.agent.agent.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.service.BiQueryService;
import com.bi.agent.bi.service.sql.ChartSelector;
import com.bi.agent.bi.vo.QueryResultVo;

/**
 * Agent 工具：为指定只读 SQL 结果智能选图并生成 ECharts 配置（手写 ReAct 版）
 *
 * <p>包装 {@link BiQueryService#runReadOnlySql}（只读校验 + 取数）+ {@link ChartSelector}，
 * 返回推荐图表类型与可直接渲染的 ECharts option JSON，
 * 让 Agent 在给出数据结论的同时，连同可视化方案一并返回用户。
 */
public class SelectChartTool implements AgentTool {

    private final BiQueryService queryService;
    private final ChartSelector chartSelector;
    /** 用户在前端显式选择的数据源 ID（可 null）。非 null 时拥有最高优先级，模型参数不得覆盖 */
    private final Long userDsId;
    /** 用户的原始自然语言问题，透传给 ChartSelector 做意图识别（"趋势"→折线、"占比"→饼图），避免退化成纯数据形状兜底 */
    private final String userQuery;

    public SelectChartTool(BiQueryService queryService, ChartSelector chartSelector, Long userDsId, String userQuery) {
        this.queryService = queryService;
        this.chartSelector = chartSelector;
        this.userDsId = userDsId;
        this.userQuery = userQuery;
    }

    @Override
    public String name() {
        return "select_chart";
    }

    @Override
    public String description() {
        return "为一条【只读】SQL 的查询结果智能推荐图表类型，并生成可直接渲染的 ECharts 配置。"
                + "当用户希望『把结果画成图』『用哪种图展示』『生成可视化』时调用。"
                + "若用户明确指定了图表类型（如'我要折线图'），可通过 chartType 参数强制指定为 line/bar/pie/scatter/radar/table；"
                + "未指定时系统按数据特征与你的查询意图自动选择。"
                + "datasourceId 可省略，默认使用用户已选择的数据源。";
    }

    @Override
    public String jsonSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"datasourceId\":{\"type\":\"integer\",\"description\":\"数据源ID，整数；可省略，默认使用用户已选择的数据源\"},"
                + "\"sql\":{\"type\":\"string\",\"description\":\"要执行的只读 SQL（仅 SELECT/WITH）\"},"
                + "\"chartType\":{\"type\":\"string\",\"enum\":[\"line\",\"bar\",\"pie\",\"scatter\",\"radar\",\"table\"],\"description\":\"（可选）强制指定图表类型。仅当用户明确说'折线图/柱状图/饼图'等时才填，未指定则留空由系统自动选择\"}},"
                + "\"required\":[\"sql\"]}";
    }

    @Override
    public String call(String argsJson) {
        long dsId = (userDsId != null) ? userDsId : 1L;
        String sql = null;
        ChartSelector.ChartType preferredType = null;
        try {
            JSONObject a = JSON.parseObject(argsJson);
            if (userDsId == null && a.containsKey("datasourceId")) {
                dsId = a.getLongValue("datasourceId");
            }
            sql = a.getString("sql");
            String chartTypeParam = a.getString("chartType");
            if (chartTypeParam != null && !chartTypeParam.isBlank()) {
                preferredType = parseChartType(chartTypeParam.trim().toLowerCase());
            }
        } catch (Exception e) {
            return "参数解析失败：" + e.getMessage();
        }
        if (sql == null || sql.isBlank()) {
            return "缺少 sql 参数";
        }
        try {
            QueryResultVo vo = queryService.runReadOnlySql(dsId, sql);
            if (vo.getColumns() == null || vo.getColumns().isEmpty()
                    || vo.getData() == null || vo.getData().isEmpty()) {
                return "{\"note\":\"查询结果为空，无法选图\"}";
            }
            ChartSelector.ChartType ct = chartSelector.selectChart(
                    vo.getColumns(), vo.getData(), userQuery, preferredType);
            JSONObject option = chartSelector.generateEChartsOption(ct, vo.getColumns(), vo.getData());
            JSONObject out = new JSONObject();
            out.put("chartType", ct.getType());
            out.put("chartName", ct.getName());
            out.put("rowCount", vo.getRowCount());
            out.put("echartsOption", option);
            return out.toJSONString();
        } catch (Exception e) {
            return "选图失败：" + e.getMessage();
        }
    }

    /**
     * 把模型传入的 chartType 字符串映射为 ChartSelector 枚举；无法识别时返回 null（走自动选择）。
     */
    private ChartSelector.ChartType parseChartType(String type) {
        switch (type) {
            case "line": return ChartSelector.ChartType.LINE;
            case "bar": return ChartSelector.ChartType.BAR;
            case "pie": return ChartSelector.ChartType.PIE;
            case "scatter": return ChartSelector.ChartType.SCATTER;
            case "radar": return ChartSelector.ChartType.RADAR;
            case "table": return ChartSelector.ChartType.TABLE;
            default: return null;
        }
    }
}
