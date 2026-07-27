package com.bi.agent.bi.util;

import com.bi.agent.bi.domain.BiDatasource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态数据源连接池工厂（消除 DriverManager.getConnection 每次建连的开销）
 * <p>
 * 按数据源 id 缓存 HikariDataSource；当数据源配置（URL / 账号 / 密码）变更时，
 * 调用 {@link #invalidate(Long)} 销毁旧池以重建。
 */
@Component
public class BiDataSourceFactory {

    private final Map<Long, HikariDataSource> pool = new ConcurrentHashMap<>();

    public HikariDataSource getDataSource(BiDatasource ds) {
        return pool.computeIfAbsent(ds.getId(), k -> create(ds));
    }

    private HikariDataSource create(BiDatasource ds) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(JdbcUrlBuilder.build(ds));
        cfg.setUsername(ds.getUsername());
        cfg.setPassword(ds.getPassword());
        cfg.setDriverClassName(JdbcUrlBuilder.isPostgres(ds)
                ? "org.postgresql.Driver"
                : "com.mysql.cj.jdbc.Driver");
        cfg.setMaximumPoolSize(5);
        cfg.setConnectionTimeout(30000);
        cfg.setPoolName("bi-ds-" + ds.getId());
        return new HikariDataSource(cfg);
    }

    /** 数据源配置变更后调用，销毁并重建对应连接池 */
    public void invalidate(Long datasourceId) {
        HikariDataSource old = pool.remove(datasourceId);
        if (old != null) {
            old.close();
        }
    }
}
