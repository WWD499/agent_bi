package com.bi.agent.bi.domain;

import java.time.LocalDateTime;

/**
 * BI 预警记录实体
 * 映射 bi_alert_record 表
 *
 * <p>独立实体（与 BiKnowledge 风格一致），手写 getter/setter。
 *
 * @author ruoyi-bi (ported to agent-bi)
 */
public class BiAlertRecord {

    private Long id;
    private Long ruleId;
    private String ruleName;
    private Long datasourceId;
    private String tableName;
    private String checkSql;
    private Double thresholdValue;
    private Double actualValue;
    private String comparisonOperator;
    private String alertMessage;
    private String analysisResult;
    private String alertLevel;
    private LocalDateTime alertTime;
    private String status;
    private String handledBy;
    private LocalDateTime handledTime;
    private String handledRemark;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }

    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }

    public Long getDatasourceId() { return datasourceId; }
    public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public String getCheckSql() { return checkSql; }
    public void setCheckSql(String checkSql) { this.checkSql = checkSql; }

    public Double getThresholdValue() { return thresholdValue; }
    public void setThresholdValue(Double thresholdValue) { this.thresholdValue = thresholdValue; }

    public Double getActualValue() { return actualValue; }
    public void setActualValue(Double actualValue) { this.actualValue = actualValue; }

    public String getComparisonOperator() { return comparisonOperator; }
    public void setComparisonOperator(String comparisonOperator) { this.comparisonOperator = comparisonOperator; }

    public String getAlertMessage() { return alertMessage; }
    public void setAlertMessage(String alertMessage) { this.alertMessage = alertMessage; }

    public String getAnalysisResult() { return analysisResult; }
    public void setAnalysisResult(String analysisResult) { this.analysisResult = analysisResult; }

    public String getAlertLevel() { return alertLevel; }
    public void setAlertLevel(String alertLevel) { this.alertLevel = alertLevel; }

    public LocalDateTime getAlertTime() { return alertTime; }
    public void setAlertTime(LocalDateTime alertTime) { this.alertTime = alertTime; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getHandledBy() { return handledBy; }
    public void setHandledBy(String handledBy) { this.handledBy = handledBy; }

    public LocalDateTime getHandledTime() { return handledTime; }
    public void setHandledTime(LocalDateTime handledTime) { this.handledTime = handledTime; }

    public String getHandledRemark() { return handledRemark; }
    public void setHandledRemark(String handledRemark) { this.handledRemark = handledRemark; }
}
