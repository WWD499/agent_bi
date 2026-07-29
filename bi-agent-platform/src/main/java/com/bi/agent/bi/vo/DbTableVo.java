package com.bi.agent.bi.vo;

/**
 * 数据源表信息（供前端下拉选择）
 */
public class DbTableVo {
    /** 表名（沙箱场景下为物理名 physical_name，可直接用于 SQL 与 API） */
    private String tableName;

    /** 表注释 */
    private String remarks;

    /** 沙箱场景：物理表名 = db_key || '__' || table_name（sandbox."physicalName"） */
    private String physicalName;

    /** 沙箱场景：所属沙箱库 id */
    private Long dbId;

    /** 沙箱场景：所属沙箱库前缀键 */
    private String dbKey;

    /** 沙箱场景：用户友好短名（同 physicalName 区别开） */
    private String displayName;

    /** 沙箱场景：短名（不含 db_key 前缀，如 emp），展示回退用 */
    private String shortName;

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public String getPhysicalName() {
        return physicalName;
    }

    public void setPhysicalName(String physicalName) {
        this.physicalName = physicalName;
    }

    public Long getDbId() {
        return dbId;
    }

    public void setDbId(Long dbId) {
        this.dbId = dbId;
    }

    public String getDbKey() {
        return dbKey;
    }

    public void setDbKey(String dbKey) {
        this.dbKey = dbKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getShortName() {
        return shortName;
    }

    public void setShortName(String shortName) {
        this.shortName = shortName;
    }
}
