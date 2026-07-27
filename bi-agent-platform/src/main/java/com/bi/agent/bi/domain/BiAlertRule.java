package com.bi.agent.bi.domain;

import java.time.LocalDateTime;

/**
 * BI 预警规则实体
 * 映射 bi_alert_config 表
 *
 * <p>独立实体（不继承通用 BaseEntity），与 BiKnowledge 风格一致，手写 getter/setter，
 * 避免对若依 BaseEntity 的强依赖。
 *
 * @author ruoyi-bi (ported to agent-bi)
 */
public class BiAlertRule {

    private Long id;
    private String name;
    private Long datasourceId;
    private String tableName;
    private String metricField;
    /** 检查SQL：用于查出当前值的 SQL 语句 */
    private String conditionSql;
    /** 阈值 */
    private Double thresholdValue;
    /** 比较运算符：> < >= <= = != */
    private String comparisonOperator;
    /** 检查间隔（分钟） */
    private Integer checkInterval;
    /** 通知方式：email / sms / wechat（逗号分隔） */
    private String notifyType;
    /** 通知目标（邮箱、手机号等） */
    private String notifyTarget;
    /** 状态：0-停用，1-启用 */
    private Integer status;
    /** 是否启用 AI 分析 */
    private Integer analysisEnabled;

    /** 上次检查时间（LocalDateTime，禁止字符串存时间） */
    private LocalDateTime lastCheckTime;
    /** 上次预警时间 */
    private LocalDateTime lastAlertTime;

    // 审计字段（与表中 create_*/update_*/remark 对齐）
    private String createBy;
    private LocalDateTime createTime;
    private String updateBy;
    private LocalDateTime updateTime;
    private String remark;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Long getDatasourceId() { return datasourceId; }
    public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public String getMetricField() { return metricField; }
    public void setMetricField(String metricField) { this.metricField = metricField; }

    public String getConditionSql() { return conditionSql; }
    public void setConditionSql(String conditionSql) { this.conditionSql = conditionSql; }

    public Double getThresholdValue() { return thresholdValue; }
    public void setThresholdValue(Double thresholdValue) { this.thresholdValue = thresholdValue; }

    public String getComparisonOperator() { return comparisonOperator; }
    public void setComparisonOperator(String comparisonOperator) { this.comparisonOperator = comparisonOperator; }

    public Integer getCheckInterval() { return checkInterval; }
    public void setCheckInterval(Integer checkInterval) { this.checkInterval = checkInterval; }

    public String getNotifyType() { return notifyType; }
    public void setNotifyType(String notifyType) { this.notifyType = notifyType; }

    public String getNotifyTarget() { return notifyTarget; }
    public void setNotifyTarget(String notifyTarget) { this.notifyTarget = notifyTarget; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Integer getAnalysisEnabled() { return analysisEnabled; }
    public void setAnalysisEnabled(Integer analysisEnabled) { this.analysisEnabled = analysisEnabled; }

    public LocalDateTime getLastCheckTime() { return lastCheckTime; }
    public void setLastCheckTime(LocalDateTime lastCheckTime) { this.lastCheckTime = lastCheckTime; }

    public LocalDateTime getLastAlertTime() { return lastAlertTime; }
    public void setLastAlertTime(LocalDateTime lastAlertTime) { this.lastAlertTime = lastAlertTime; }

    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
