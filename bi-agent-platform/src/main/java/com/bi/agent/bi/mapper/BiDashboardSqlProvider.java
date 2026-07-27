package com.bi.agent.bi.mapper;

import com.bi.agent.bi.domain.BiDashboard;

/**
 * BiDashboardMapper 动态 SQL 提供器。
 * <p>列表查询不返回 config_json / thumbnail 大字段，避免列表页传输膨胀。</p>
 */
public class BiDashboardSqlProvider {

    public String selectList(BiDashboard dashboard) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, name, description, status, is_public, access_token, "
                        + "create_time, update_time FROM bi_dashboard");
        StringBuilder where = new StringBuilder();
        if (dashboard != null) {
            if (dashboard.getName() != null && !dashboard.getName().isEmpty()) {
                where.append(" AND name LIKE CONCAT('%', #{name}, '%')");
            }
            if (dashboard.getStatus() != null && !dashboard.getStatus().isEmpty()) {
                where.append(" AND status = #{status}");
            }
        }
        if (where.length() > 0) {
            sql.append(" WHERE ").append(where.substring(5));
        }
        sql.append(" ORDER BY create_time DESC");
        return sql.toString();
    }
}
