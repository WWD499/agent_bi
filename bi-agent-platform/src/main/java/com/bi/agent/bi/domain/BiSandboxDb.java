package com.bi.agent.bi.domain;

/**
 * 数据沙箱库（逻辑命名空间）实体（映射 bi_sandbox_db）。
 *
 * <p>项目未引入 Lombok，手写 getter/setter，与 BiSandboxTable 保持一致风格。
 * 时间字段用 String 承载（Mapper 以 to_char 取出的 'YYYY-MM-DD HH24:MI:SS' 文本）。
 *
 * <p>db_key 是物理前缀键（英文/数字/下划线），物理表名 = db_key || '__' || table_name，
 * 落在 sandbox schema 下（sandbox."{db_key}__{table_name}"）。name 为展示名，可中文。
 */
public class BiSandboxDb {

    private Long id;
    private String dbKey;
    private String name;
    private String owner;
    private String remark;
    private String createTime;
    private String updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDbKey() {
        return dbKey;
    }

    public void setDbKey(String dbKey) {
        this.dbKey = dbKey;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
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
