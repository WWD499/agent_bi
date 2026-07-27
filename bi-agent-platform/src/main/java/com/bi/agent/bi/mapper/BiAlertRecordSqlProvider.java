package com.bi.agent.bi.mapper;

import com.bi.agent.bi.domain.BiAlertRecord;

/**
 * BiAlertRecordMapper 动态 SQL 提供器（替代原 XML 中的 &lt;if&gt; 条件拼接）。
 */
public class BiAlertRecordSqlProvider {

    private static final String COLUMNS =
            "id, rule_id, rule_name, datasource_id, table_name, check_sql, "
            + "threshold_value, actual_value, comparison_operator, alert_message, analysis_result, "
            + "alert_level, alert_time, status, handled_by, handled_time, handled_remark";

    public String selectList(BiAlertRecord r) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM bi_alert_record");
        StringBuilder where = new StringBuilder();
        if (r != null) {
            if (r.getRuleId() != null) {
                where.append(" AND rule_id = #{ruleId}");
            }
            if (r.getAlertLevel() != null && !r.getAlertLevel().isEmpty()) {
                where.append(" AND alert_level = #{alertLevel}");
            }
            if (r.getStatus() != null && !r.getStatus().isEmpty()) {
                where.append(" AND status = #{status}");
            }
            if (r.getRuleName() != null && !r.getRuleName().isEmpty()) {
                where.append(" AND rule_name LIKE CONCAT('%', #{ruleName}, '%')");
            }
        }
        if (where.length() > 0) {
            sql.append(" WHERE ").append(where.substring(5));
        }
        sql.append(" ORDER BY alert_time DESC");
        return sql.toString();
    }

    public String insert(BiAlertRecord r) {
        StringBuilder cols = new StringBuilder("INSERT INTO bi_alert_record (");
        StringBuilder vals = new StringBuilder("VALUES (");
        if (r.getRuleId() != null) {
            cols.append("rule_id, "); vals.append("#{ruleId}, ");
        }
        if (r.getRuleName() != null && !r.getRuleName().isEmpty()) {
            cols.append("rule_name, "); vals.append("#{ruleName}, ");
        }
        if (r.getDatasourceId() != null) {
            cols.append("datasource_id, "); vals.append("#{datasourceId}, ");
        }
        if (r.getTableName() != null && !r.getTableName().isEmpty()) {
            cols.append("table_name, "); vals.append("#{tableName}, ");
        }
        if (r.getCheckSql() != null && !r.getCheckSql().isEmpty()) {
            cols.append("check_sql, "); vals.append("#{checkSql}, ");
        }
        if (r.getThresholdValue() != null) {
            cols.append("threshold_value, "); vals.append("#{thresholdValue}, ");
        }
        if (r.getActualValue() != null) {
            cols.append("actual_value, "); vals.append("#{actualValue}, ");
        }
        if (r.getComparisonOperator() != null && !r.getComparisonOperator().isEmpty()) {
            cols.append("comparison_operator, "); vals.append("#{comparisonOperator}, ");
        }
        if (r.getAlertMessage() != null && !r.getAlertMessage().isEmpty()) {
            cols.append("alert_message, "); vals.append("#{alertMessage}, ");
        }
        if (r.getAnalysisResult() != null && !r.getAnalysisResult().isEmpty()) {
            cols.append("analysis_result, "); vals.append("#{analysisResult}, ");
        }
        if (r.getAlertLevel() != null && !r.getAlertLevel().isEmpty()) {
            cols.append("alert_level, "); vals.append("#{alertLevel}, ");
        }
        if (r.getStatus() != null && !r.getStatus().isEmpty()) {
            cols.append("status, "); vals.append("#{status}, ");
        }
        cols.append("alert_time) ");
        vals.append("NOW())");
        return cols.toString() + vals.toString();
    }

    public String update(BiAlertRecord r) {
        StringBuilder sql = new StringBuilder("UPDATE bi_alert_record SET ");
        if (r.getStatus() != null && !r.getStatus().isEmpty()) {
            sql.append("status = #{status}, ");
        }
        if (r.getHandledBy() != null && !r.getHandledBy().isEmpty()) {
            sql.append("handled_by = #{handledBy}, ");
        }
        if (r.getHandledRemark() != null && !r.getHandledRemark().isEmpty()) {
            sql.append("handled_remark = #{handledRemark}, ");
        }
        // 状态变为已确认/已解决时自动写入处理时间
        if (r.getStatus() != null
                && ("resolved".equals(r.getStatus()) || "confirmed".equals(r.getStatus()))) {
            sql.append("handled_time = NOW(), ");
        }
        sql.append("update_time = NOW() WHERE id = #{id}");
        // 去掉 update_time 前的尾逗号（若前面无任何 SET 项）
        return sql.toString().replace("UPDATE bi_alert_record SET  WHERE",
                "UPDATE bi_alert_record SET update_time = NOW() WHERE");
    }
}
