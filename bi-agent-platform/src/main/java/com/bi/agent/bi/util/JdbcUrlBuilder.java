package com.bi.agent.bi.util;

import com.bi.agent.bi.domain.BiDatasource;

/**
 * JDBC URL 构建工具（消除 BI 模块内 4 处重复的拼接逻辑）
 * <p>
 * 关键约定：
 * 1) MySQL 8.x 的 caching_sha2_password 在 useSSL=false 时必须带 allowPublicKeyRetrieval=true；
 * 2) PostgreSQL 的 catalog 必须为 ""（由连接绑定当前库），传 databaseName 会拿不到表结构。
 */
public final class JdbcUrlBuilder {

    private JdbcUrlBuilder() {
    }

    /** 根据数据源构建完整 JDBC URL（自动为 MySQL 补齐 allowPublicKeyRetrieval） */
    public static String build(BiDatasource ds) {
        if (ds.getJdbcUrl() != null && !ds.getJdbcUrl().trim().isEmpty()) {
            String url = ds.getJdbcUrl().trim();
            if (url.contains("mysql") && !url.contains("allowPublicKeyRetrieval")) {
                url += (url.contains("?") ? "&" : "?") + "allowPublicKeyRetrieval=true&useSSL=false";
            }
            return url;
        }
        String type = isPostgres(ds) ? "postgresql" : "mysql";
        String host = ds.getHost() != null ? ds.getHost() : "localhost";
        int port = ds.getPort() != null ? ds.getPort() : defaultPort(type);
        String db = ds.getDatabaseName() != null ? ds.getDatabaseName() : "";
        return String.format(
                "jdbc:%s://%s:%d/%s?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
                type, host, port, db);
    }

    /** 获取 DatabaseMetaData.getTables / getColumns 的 catalog 参数 */
    public static String catalog(BiDatasource ds) {
        // PG 的 catalog 传 null 表示「使用当前连接库」；若传 "" 驱动会将其视为空 catalog 名进行过滤，导致 getTables 返回空结果
        return isPostgres(ds) ? null : ds.getDatabaseName();
    }

    /**
     * 获取 DatabaseMetaData.getTables / getColumns 的 schemaPattern 参数。
     * PG 必须显式传 "public"——传 null 时驱动不会回退为「全部 schema」，
     * 而是匹配不到 public 下的表，导致返回空结果（且不抛异常，极难排查）。
     * MySQL 的 schema 即 database，已由 catalog 承载，传 null 表示不过滤。
     */
    public static String schemaPattern(BiDatasource ds) {
        return isPostgres(ds) ? "public" : null;
    }

    public static boolean isPostgres(BiDatasource ds) {
        return ds.getType() != null && "postgresql".equalsIgnoreCase(ds.getType().trim());
    }

    private static int defaultPort(String type) {
        return "postgresql".equals(type) ? 5432 : 3306;
    }
}
