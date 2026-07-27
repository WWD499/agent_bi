package com.bi.agent.bi.domain;

import com.bi.agent.common.BaseEntity;
import org.apache.commons.lang3.builder.ToStringBuilder;

/**
 * BI 数据源配置表 bi_datasource
 */
public class BiDatasource extends BaseEntity {
    private static final long serialVersionUID = 1L;

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

    /** 用户名 */
    private String username;

    /** 密码（加密存储） */
    private String password;

    /** JDBC 连接串（自动生成） */
    private String jdbcUrl;

    /** 状态：0-停用，1-启用 */
    private Integer status;

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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("id", getId())
                .append("name", getName())
                .append("type", getType())
                .append("host", getHost())
                .append("port", getPort())
                .append("databaseName", getDatabaseName())
                .append("status", getStatus())
                .toString();
    }
}
