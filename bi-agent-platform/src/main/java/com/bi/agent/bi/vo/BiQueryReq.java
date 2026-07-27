package com.bi.agent.bi.vo;

/**
 * NL2SQL 查询请求体（HTTP 入参）。
 *
 * <p>项目未引入 Lombok，手写 getter/setter，与 QueryResultVo 保持一致风格。
 *
 * @author agent-bi
 */
public class BiQueryReq {

    /** 用户自然语言问题，如「bi_datasource 一共有多少条记录？」 */
    private String query;

    /** 数据源 ID（对应 bi_datasource 表主键） */
    private Long datasourceId;

    /** 目标表名（可选；不传则交由 LLM 从所有可用表中自行判断） */
    private String tableName;

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public Long getDatasourceId() {
        return datasourceId;
    }

    public void setDatasourceId(Long datasourceId) {
        this.datasourceId = datasourceId;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }
}
