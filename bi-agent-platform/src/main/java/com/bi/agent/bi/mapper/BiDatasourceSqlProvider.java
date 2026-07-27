package com.bi.agent.bi.mapper;

import com.bi.agent.bi.domain.BiDatasource;

/**
 * BiDatasourceMapper 动态 SQL 提供器（替代原 XML 中的 &lt;if&gt; 条件拼接）。
 */
public class BiDatasourceSqlProvider {

    public String selectList(BiDatasource ds) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, name, type, host, port, database_name, username, password, "
                        + "jdbc_url, status, remark, create_time, update_time FROM bi_datasource");
        StringBuilder where = new StringBuilder();
        if (ds != null) {
            if (ds.getName() != null && !ds.getName().isEmpty()) {
                where.append(" AND name LIKE CONCAT('%', #{name}, '%')");
            }
            if (ds.getType() != null && !ds.getType().isEmpty()) {
                where.append(" AND type = #{type}");
            }
            if (ds.getStatus() != null) {
                where.append(" AND status = #{status}");
            }
        }
        if (where.length() > 0) {
            // 去掉多余的 " AND " 前缀，拼成 WHERE 子句
            sql.append(" WHERE ").append(where.substring(5));
        }
        sql.append(" ORDER BY create_time DESC");
        return sql.toString();
    }
}
