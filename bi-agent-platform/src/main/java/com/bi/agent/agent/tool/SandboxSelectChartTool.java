package com.bi.agent.agent.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.service.SandboxQueryService;
import com.bi.agent.bi.service.sql.ChartSelector;
import com.bi.agent.bi.vo.QueryResultVo;

/**
 * Agent 沙箱工具：为指定只读 SQL 结果智能选图并生成 ECharts 配置。
 *
 * <p>与业务版 {@link SelectChartTool} 对应，但只在 sandbox schema 内执行。
 * 工具名保持 {@code select_chart}，使 {@link com.bi.agent.agent.BiAgentService}
 * 的图表收集逻辑无需改动即可把 ECharts option 随 SSE 推给前端渲染。
 */
public class SandboxSelectChartTool implements AgentTool {

    private final SandboxQueryService sandboxQueryService;
    private final ChartSelector chartSelector;
    /** 用户原始问题，透传给 ChartSelector 做意图识别 */
    private final String userQuery;

    public SandboxSelectChartTool(SandboxQueryService sandboxQueryService,
                                  ChartSelector chartSelector,
                                  String userQuery) {
        this.sandboxQueryService = sandboxQueryService;
        this.chartSelector = chartSelector;
        this.userQuery = userQuery;
    }

    @Override
    public String name() {
        return "select_chart";
    }

    @Override
    public String description() {
        return "为一条在数据沙箱内执行的【只读】SQL 结果智能推荐图表类型，并生成可直接渲染的 ECharts 配置。"
                + "当用户希望『把结果画成图』『用哪种图展示』『生成可视化』『生成图表』时调用。"
                + "sql 中的表名必须使用 sandbox.\"物理名\" 全限定形式（如 sandbox.\"marts__sales\"）。"
                + "若用户明确指定了图表类型（如'我要折线图'），可通过 chartType 参数强制指定为 line/bar/pie/scatter/radar/table；"
                + "未指定时系统按数据特征与你的查询意图自动选择。";
    }

    @Override
    public String jsonSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"sql\":{\"type\":\"string\",\"description\":\"要执行的只读 SQL（仅 SELECT/WITH），表名用 sandbox.\\\"物理名\\\" 全限定\"},"
                + "\"chartType\":{\"type\":\"string\",\"enum\":[\"line\",\"bar\",\"pie\",\"scatter\",\"radar\",\"table\"],\"description\":\"（可选）强制指定图表类型。仅当用户明确说'折线图/柱状图/饼图'等时才填，未指定则留空由系统自动选择\"}},"
                + "\"required\":[\"sql\"]}";
    }

    @Override
    public String call(String argsJson) {
        String sql = null;
        ChartSelector.ChartType preferredType = null;
        try {
            JSONObject a = JSON.parseObject(argsJson);
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
            QueryResultVo vo = sandboxQueryService.runSandboxReadOnlySql(sql);
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
