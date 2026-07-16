package com.bi.agent.bi.vo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据探查结果 VO（NL2SQL 数据探查前置 / DataProfile）。
 *
 * <p>由 {@code DataProbeService} 在 NL2SQL 生成前对候选业务表做轻量元数据探查后产出，
 * 注入 Prompt，指引 LLM 基于「真实数据覆盖」生成 SQL（根治「上季度」被硬锁为
 * CURRENT_DATE 推算区间、与种子数据不重叠导致 rowCount=0 的根因）。
 *
 * <p>字段命名全项目统一（见架构设计「共享知识 §1」）：
 * datasourceId / tableName / rowCount / timeColumns / enumColumns /
 * probed / probeSkipped / skipReason / costMillis / probedAt。
 *
 * <p>项目未引入 Lombok，手写 getter/setter 以保持风格一致。
 */
public class DataProfile {

    /** 数据源 ID */
    private Long datasourceId;

    /** 表名 */
    private String tableName;

    /** 行数（精确 COUNT(*)） */
    private long rowCount;

    /** 时间列覆盖区间：列名 -> TimeRange（包含 min/max/最新可用季度） */
    private Map<String, TimeRange> timeColumns = new LinkedHashMap<>();

    /** 枚举列取值与计数：列名 -> 取值列表（按计数降序，最多 TopN） */
    private Map<String, List<EnumValue>> enumColumns = new LinkedHashMap<>();

    /** 是否已成功探查（true=成功产出本对象） */
    private boolean probed = true;

    /** 是否因超时/异常被跳过（降级） */
    private boolean probeSkipped = false;

    /** 跳过原因（probeSkipped=true 时填写，用于日志与前端友好提示） */
    private String skipReason;

    /** 探查耗时（毫秒） */
    private long costMillis;

    /** 探查完成时间（ISO 字符串，如 2026-07-16T14:30:00） */
    private String probedAt;

    // ===================== getter / setter =====================

    public Long getDatasourceId() { return datasourceId; }
    public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public long getRowCount() { return rowCount; }
    public void setRowCount(long rowCount) { this.rowCount = rowCount; }

    public Map<String, TimeRange> getTimeColumns() { return timeColumns; }
    public void setTimeColumns(Map<String, TimeRange> timeColumns) {
        this.timeColumns = timeColumns != null ? timeColumns : new LinkedHashMap<>();
    }

    public Map<String, List<EnumValue>> getEnumColumns() { return enumColumns; }
    public void setEnumColumns(Map<String, List<EnumValue>> enumColumns) {
        this.enumColumns = enumColumns != null ? enumColumns : new LinkedHashMap<>();
    }

    public boolean isProbed() { return probed; }
    public void setProbed(boolean probed) { this.probed = probed; }

    public boolean isProbeSkipped() { return probeSkipped; }
    public void setProbeSkipped(boolean probeSkipped) { this.probeSkipped = probeSkipped; }

    public String getSkipReason() { return skipReason; }
    public void setSkipReason(String skipReason) { this.skipReason = skipReason; }

    public long getCostMillis() { return costMillis; }
    public void setCostMillis(long costMillis) { this.costMillis = costMillis; }

    public String getProbedAt() { return probedAt; }
    public void setProbedAt(String probedAt) { this.probedAt = probedAt; }

    // ===================== 嵌套：时间列覆盖区间 =====================

    /**
     * 时间列覆盖区间。
     * column 为列名；min/max 为实际数据最小/最大值（字符串，来自 JDBC）；
     * latestQuarter 为 max 所在季度（如 2025-12 -> "2025-Q4"），供 LLM 映射「上季度」等相对时间。
     */
    public static class TimeRange {
        private String column;
        private String min;
        private String max;
        private String latestQuarter;

        public TimeRange() {}

        public TimeRange(String column, String min, String max, String latestQuarter) {
            this.column = column;
            this.min = min;
            this.max = max;
            this.latestQuarter = latestQuarter;
        }

        public String getColumn() { return column; }
        public void setColumn(String column) { this.column = column; }

        public String getMin() { return min; }
        public void setMin(String min) { this.min = min; }

        public String getMax() { return max; }
        public void setMax(String max) { this.max = max; }

        public String getLatestQuarter() { return latestQuarter; }
        public void setLatestQuarter(String latestQuarter) { this.latestQuarter = latestQuarter; }
    }

    // ===================== 嵌套：枚举取值与计数 =====================

    /** 枚举列的一个取值及其出现次数 */
    public static class EnumValue {
        private String value;
        private long count;

        public EnumValue() {}

        public EnumValue(String value, long count) {
            this.value = value;
            this.count = count;
        }

        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }

        public long getCount() { return count; }
        public void setCount(long count) { this.count = count; }
    }

    // ===================== 友好摘要（前端直接展示） =====================

    /**
     * 生成供前端直接展示的友好摘要（覆盖区间、关键枚举取值、行数）。
     * 返回多行字符串；若本表被跳过（probeSkipped=true），则仅返回跳过说明。
     */
    public String toSummary() {
        if (probeSkipped) {
            return "数据探查已跳过（" + (skipReason != null ? skipReason : "未知原因")
                    + "），未注入真实数据覆盖，LLM 按默认常识生成 SQL。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("已探查 ").append(tableName != null ? tableName : "未知表")
                .append("：共 ").append(rowCount).append(" 行。");

        if (timeColumns != null && !timeColumns.isEmpty()) {
            for (Map.Entry<String, TimeRange> e : timeColumns.entrySet()) {
                TimeRange tr = e.getValue();
                sb.append("\n时间列 ").append(e.getKey()).append("：")
                        .append(tr.getMin()).append(" ~ ").append(tr.getMax());
                if (tr.getLatestQuarter() != null) {
                    sb.append("（最新可用季度 ").append(tr.getLatestQuarter()).append("）");
                }
            }
        }

        if (enumColumns != null && !enumColumns.isEmpty()) {
            for (Map.Entry<String, List<EnumValue>> e : enumColumns.entrySet()) {
                List<EnumValue> vals = e.getValue();
                if (vals == null || vals.isEmpty()) {
                    continue;
                }
                sb.append("\n枚举列 ").append(e.getKey()).append("（取值 TOP ").append(vals.size()).append("）：");
                List<String> parts = new ArrayList<>();
                for (EnumValue v : vals) {
                    parts.add(v.getValue() + " " + v.getCount());
                }
                sb.append(String.join("、", parts));
            }
        }

        return sb.toString();
    }
}
