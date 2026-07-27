package com.bi.agent.bi.vo;

/**
 * 数据源表信息（供前端下拉选择）
 */
public class DbTableVo {
    /** 表名 */
    private String tableName;

    /** 表注释 */
    private String remarks;

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
}
