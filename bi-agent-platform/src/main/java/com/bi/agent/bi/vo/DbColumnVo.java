package com.bi.agent.bi.vo;

/**
 * 数据源字段信息（供前端下拉选择）
 */
public class DbColumnVo {
    /** 字段名 */
    private String columnName;

    /** 字段类型 */
    private String dataType;

    /** 字段中文标签（导入时的原始表头，如「序号」；物理列名为 ASCII，label 仅用于展示与 NL2SQL 中文→物理映射） */
    private String label;

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

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }
}
