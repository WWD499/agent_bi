package com.bi.agent.bi.mapper;

import com.bi.agent.bi.domain.BiAlertRule;

/**
 * BiAlertRuleMapper 动态 SQL 提供器（替代原 XML 中的 &lt;if&gt; 条件拼接）。
 */
public class BiAlertRuleSqlProvider {

    private static final String COLUMNS =
            "id, name, datasource_id, table_name, metric_field, condition_sql, "
            + "threshold_value, comparison_operator, check_interval, notify_type, notify_target, "
            + "status, analysis_enabled, last_check_time, last_alert_time, "
            + "create_by, create_time, update_by, update_time, remark";

    public String selectList(BiAlertRule r) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM bi_alert_config");
        StringBuilder where = new StringBuilder();
        if (r != null) {
            if (r.getName() != null && !r.getName().isEmpty()) {
                where.append(" AND name LIKE CONCAT('%', #{name}, '%')");
            }
            if (r.getTableName() != null && !r.getTableName().isEmpty()) {
                where.append(" AND table_name = #{tableName}");
            }
            if (r.getStatus() != null) {
                where.append(" AND status = #{status}");
            }
        }
        if (where.length() > 0) {
            sql.append(" WHERE ").append(where.substring(5));
        }
        sql.append(" ORDER BY create_time DESC");
        return sql.toString();
    }

    public String insert(BiAlertRule r) {
        StringBuilder cols = new StringBuilder("INSERT INTO bi_alert_config (");
        StringBuilder vals = new StringBuilder("VALUES (");
        if (r.getName() != null && !r.getName().isEmpty()) {
            cols.append("name, "); vals.append("#{name}, ");
        }
        if (r.getDatasourceId() != null) {
            cols.append("datasource_id, "); vals.append("#{datasourceId}, ");
        }
        if (r.getTableName() != null && !r.getTableName().isEmpty()) {
            cols.append("table_name, "); vals.append("#{tableName}, ");
        }
        if (r.getMetricField() != null && !r.getMetricField().isEmpty()) {
            cols.append("metric_field, "); vals.append("#{metricField}, ");
        }
        if (r.getConditionSql() != null && !r.getConditionSql().isEmpty()) {
            cols.append("condition_sql, "); vals.append("#{conditionSql}, ");
        }
        if (r.getThresholdValue() != null) {
            cols.append("threshold_value, "); vals.append("#{thresholdValue}, ");
        }
        if (r.getComparisonOperator() != null && !r.getComparisonOperator().isEmpty()) {
            cols.append("comparison_operator, "); vals.append("#{comparisonOperator}, ");
        }
        if (r.getCheckInterval() != null) {
            cols.append("check_interval, "); vals.append("#{checkInterval}, ");
        }
        if (r.getNotifyType() != null && !r.getNotifyType().isEmpty()) {
            cols.append("notify_type, "); vals.append("#{notifyType}, ");
        }
        if (r.getNotifyTarget() != null && !r.getNotifyTarget().isEmpty()) {
            cols.append("notify_target, "); vals.append("#{notifyTarget}, ");
        }
        if (r.getStatus() != null) {
            cols.append("status, "); vals.append("#{status}, ");
        }
        if (r.getAnalysisEnabled() != null) {
            cols.append("analysis_enabled, "); vals.append("#{analysisEnabled}, ");
        }
        if (r.getRemark() != null && !r.getRemark().isEmpty()) {
            cols.append("remark, "); vals.append("#{remark}, ");
        }
        if (r.getCreateBy() != null && !r.getCreateBy().isEmpty()) {
            cols.append("create_by, "); vals.append("#{createBy}, ");
        }
        cols.append("create_time) ");
        vals.append("NOW())");
        return cols.toString() + vals.toString();
    }

    public String update(BiAlertRule r) {
        StringBuilder sql = new StringBuilder("UPDATE bi_alert_config SET ");
        if (r.getName() != null && !r.getName().isEmpty()) {
            sql.append("name = #{name}, ");
        }
        if (r.getDatasourceId() != null) {
            sql.append("datasource_id = #{datasourceId}, ");
        }
        if (r.getTableName() != null && !r.getTableName().isEmpty()) {
            sql.append("table_name = #{tableName}, ");
        }
        if (r.getMetricField() != null && !r.getMetricField().isEmpty()) {
            sql.append("metric_field = #{metricField}, ");
        }
        if (r.getConditionSql() != null && !r.getConditionSql().isEmpty()) {
            sql.append("condition_sql = #{conditionSql}, ");
        }
        if (r.getThresholdValue() != null) {
            sql.append("threshold_value = #{thresholdValue}, ");
        }
        if (r.getComparisonOperator() != null && !r.getComparisonOperator().isEmpty()) {
            sql.append("comparison_operator = #{comparisonOperator}, ");
        }
        if (r.getCheckInterval() != null) {
            sql.append("check_interval = #{checkInterval}, ");
        }
        if (r.getNotifyType() != null && !r.getNotifyType().isEmpty()) {
            sql.append("notify_type = #{notifyType}, ");
        }
        if (r.getNotifyTarget() != null && !r.getNotifyTarget().isEmpty()) {
            sql.append("notify_target = #{notifyTarget}, ");
        }
        if (r.getStatus() != null) {
            sql.append("status = #{status}, ");
        }
        if (r.getAnalysisEnabled() != null) {
            sql.append("analysis_enabled = #{analysisEnabled}, ");
        }
        if (r.getRemark() != null) {
            sql.append("remark = #{remark}, ");
        }
        if (r.getUpdateBy() != null && !r.getUpdateBy().isEmpty()) {
            sql.append("update_by = #{updateBy}, ");
        }
        sql.append("update_time = NOW() WHERE id = #{id}");
        return sql.toString();
    }
}
