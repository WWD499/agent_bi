package com.bi.agent.bi.vo;

import com.alibaba.fastjson2.JSONObject;
import java.util.List;

/**
 * 查询结果 VO
 * <p>原作为 {@code BiQueryService} 的静态内部类，按代码规范抽到独立 vo 包，
 * 与项目其余实体保持一致的手写 getter/setter 风格（项目未引入 Lombok）。
 */
public class QueryResultVo {
    private String sql;
    private List<String> columns;
    private List<JSONObject> data;
    private String chartType;
    private String chartName;
    private JSONObject echartsOption;
    private String interpretation;
    private int rowCount;

    // ===== NL2SQL 数据探查前置（DataProfile）相关字段 =====
    /** 是否因探查超时/异常而降级跳过（默认 false，非阻断） */
    private boolean probeSkipped = false;

    /** 主表探查结果（降级时为 null） */
    private DataProfile dataProfile;

    /** 探查结果友好摘要（由 DataProfile.toSummary() 生成，前端直接展示；降级时为 null） */
    private String dataProfileSummary;

    public String getSql() { return sql; }
    public void setSql(String sql) { this.sql = sql; }

    public List<String> getColumns() { return columns; }
    public void setColumns(List<String> columns) { this.columns = columns; }

    public List<JSONObject> getData() { return data; }
    public void setData(List<JSONObject> data) { this.data = data; }

    public String getChartType() { return chartType; }
    public void setChartType(String chartType) { this.chartType = chartType; }

    public String getChartName() { return chartName; }
    public void setChartName(String chartName) { this.chartName = chartName; }

    public JSONObject getEchartsOption() { return echartsOption; }
    public void setEchartsOption(JSONObject echartsOption) { this.echartsOption = echartsOption; }

    public String getInterpretation() { return interpretation; }
    public void setInterpretation(String interpretation) { this.interpretation = interpretation; }

    public int getRowCount() { return rowCount; }
    public void setRowCount(int rowCount) { this.rowCount = rowCount; }

    public boolean isProbeSkipped() { return probeSkipped; }
    public void setProbeSkipped(boolean probeSkipped) { this.probeSkipped = probeSkipped; }

    public DataProfile getDataProfile() { return dataProfile; }
    public void setDataProfile(DataProfile dataProfile) { this.dataProfile = dataProfile; }

    public String getDataProfileSummary() { return dataProfileSummary; }
    public void setDataProfileSummary(String dataProfileSummary) { this.dataProfileSummary = dataProfileSummary; }
}
