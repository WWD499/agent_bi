package com.bi.agent.bi.domain;

/**
 * 数据沙箱表元数据实体（映射 bi_sandbox_table）。
 *
 * <p>项目未引入 Lombok，手写 getter/setter，与 BiDatasource 等实体保持一致风格。
 * 时间字段用 String 承载（Mapper 以 to_char 取出的 'YYYY-MM-DD HH24:MI:SS' 文本），
 * 避免 PG TIMESTAMP 与 MyBatis 默认类型处理器之间的映射差异。
 */
public class BiSandboxTable {

    private Long id;
    private Long dbId;
    private String tableName;
    private String physicalName;
    private String displayName;
    private String owner;
    private String columnsJson;
    private Integer rowCount;
    private String sourceType;
    private String remark;
    private String createTime;
    private String updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public Long getDbId() {
        return dbId;
    }

    public void setDbId(Long dbId) {
        this.dbId = dbId;
    }

    public String getPhysicalName() {
        return physicalName;
    }

    public void setPhysicalName(String physicalName) {
        this.physicalName = physicalName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getColumnsJson() {
        return columnsJson;
    }

    public void setColumnsJson(String columnsJson) {
        this.columnsJson = columnsJson;
    }

    public Integer getRowCount() {
        return rowCount;
    }

    public void setRowCount(Integer rowCount) {
        this.rowCount = rowCount;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }
}
