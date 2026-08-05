package com.bi.agent.bi.domain;

import java.time.LocalDateTime;

/**
 * NL2SQL 查询历史记录（落库后可在「查询历史」页回看）。
 *
 * <p>与 BiOcrRecord 风格一致：独立实体（不继承 BaseEntity），手写 getter/setter，无 Lombok。
 * 一条记录对应一次自然语言查询的完整生命周期：问题 → 生成 SQL → 执行 → 结果/失败原因。
 */
public class BiQueryHistory {

    private Long id;

    /** 发起查询的用户（Sa-Token 登录 id，即用户名） */
    private String userId;

    /** 数据源 id（沙箱查询时为 0 / 负数，与 BiQueryService 路由约定一致） */
    private Long datasourceId;

    /** 用户自然语言问题 */
    private String query;

    /** LLM 生成的 SQL */
    private String sql;

    /** 返回行数 */
    private Integer rowCount;

    /** 端到端耗时（毫秒） */
    private Long durationMs;

    /** 状态：success / failed */
    private String status;

    /** 失败原因（成功时为 null，落库前截断避免超长） */
    private String errorMsg;

    /** 创建时间 */
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Long getDatasourceId() {
        return datasourceId;
    }

    public void setDatasourceId(Long datasourceId) {
        this.datasourceId = datasourceId;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public Integer getRowCount() {
        return rowCount;
    }

    public void setRowCount(Integer rowCount) {
        this.rowCount = rowCount;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
