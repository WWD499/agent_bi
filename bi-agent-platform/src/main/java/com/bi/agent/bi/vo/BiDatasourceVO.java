package com.bi.agent.bi.vo;

/**
 * 数据源对外视图对象（屏蔽 password / jdbcUrl 等敏感字段）。
 *
 * <p>前端 NL2SQL / 预警规则等表单仅需展示名称、类型、库名与连通状态，
 * 绝不回传密码或完整 JDBC 串。
 */
public class BiDatasourceVO {

    /** 数据源ID */
    private Long id;

    /** 数据源名称 */
    private String name;

    /** 类型：mysql、postgresql、oracle、sqlserver */
    private String type;

    /** 主机地址 */
    private String host;

    /** 端口 */
    private Integer port;

    /** 数据库名 */
    private String databaseName;

    /** 状态：0-停用，1-启用 */
    private Integer status;

    /** 创建时间 */
    private java.time.LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public java.time.LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(java.time.LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
