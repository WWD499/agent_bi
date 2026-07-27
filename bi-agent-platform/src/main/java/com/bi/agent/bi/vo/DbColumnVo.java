package com.bi.agent.bi.vo;

/**
 * 数据源字段信息（供前端下拉选择）
 */
public class DbColumnVo {
    /** 字段名 */
    private String columnName;

    /** 字段类型 */
    private String dataType;

    /** 字段注释 */
    private String remarks;

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }
}
