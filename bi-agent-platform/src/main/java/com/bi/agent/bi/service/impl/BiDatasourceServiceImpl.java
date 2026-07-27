package com.bi.agent.bi.service.impl;

import com.bi.agent.bi.domain.BiDatasource;
import com.bi.agent.bi.mapper.BiDatasourceMapper;
import com.bi.agent.bi.service.IBiDatasourceService;
import com.bi.agent.bi.util.BiDataSourceFactory;
import com.bi.agent.bi.util.JdbcUrlBuilder;
import com.bi.agent.bi.vo.DbColumnVo;
import com.bi.agent.bi.vo.DbTableVo;
import com.bi.agent.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * BI 数据源配置表 Service 实现类
 */
@Service
public class BiDatasourceServiceImpl implements IBiDatasourceService {

    private static final Logger log = LoggerFactory.getLogger(BiDatasourceServiceImpl.class);

    @Autowired
    private BiDatasourceMapper datasourceMapper;

    @Autowired
    private BiDataSourceFactory dataSourceFactory;

    @Override
    public List<BiDatasource> selectBiDatasourceList(BiDatasource datasource) {
        return datasourceMapper.selectBiDatasourceList(datasource);
    }

    @Override
    public BiDatasource selectBiDatasourceById(Long id) {
        return datasourceMapper.selectBiDatasourceById(id);
    }

    @Override
    public int insertBiDatasource(BiDatasource datasource) {
        return datasourceMapper.insertBiDatasource(datasource);
    }

    @Override
    public int updateBiDatasource(BiDatasource datasource) {
        return datasourceMapper.updateBiDatasource(datasource);
    }

    @Override
    public int deleteBiDatasourceById(Long id) {
        return datasourceMapper.deleteBiDatasourceById(id);
    }

    @Override
    public int deleteBiDatasourceByIds(Long[] ids) {
        return datasourceMapper.deleteBiDatasourceByIds(ids);
    }

    @Override
    public boolean testConnection(BiDatasource datasource) {
        log.info("测试数据源连接：{} ({})", datasource.getName(), datasource.getHost());
        com.zaxxer.hikari.HikariConfig cfg = new com.zaxxer.hikari.HikariConfig();
        cfg.setJdbcUrl(JdbcUrlBuilder.build(datasource));
        cfg.setUsername(datasource.getUsername());
        cfg.setPassword(datasource.getPassword());
        cfg.setDriverClassName(JdbcUrlBuilder.isPostgres(datasource)
                ? "org.postgresql.Driver" : "com.mysql.cj.jdbc.Driver");
        cfg.setMaximumPoolSize(1);
        cfg.setConnectionTimeout(5000);
        cfg.setPoolName("bi-test-" + (datasource.getId() == null ? "tmp" : datasource.getId()));
        try (com.zaxxer.hikari.HikariDataSource tmp = new com.zaxxer.hikari.HikariDataSource(cfg);
             Connection conn = tmp.getConnection()) {
            return conn.isValid(3);
        } catch (SQLException e) {
            log.warn("测试连接失败：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 列出数据源当前库所有表（供预警规则表单级联选择）
     * 复用 BiDataSourceFactory 连接池 + JdbcUrlBuilder.catalog（PG catalog 必须为 ""）
     */
    @Override
    public List<DbTableVo> listTables(Long datasourceId) {
        BiDatasource ds = selectBiDatasourceById(datasourceId);
        if (ds == null) {
            return Collections.emptyList();
        }
        String catalog = JdbcUrlBuilder.catalog(ds);
        String schema = JdbcUrlBuilder.schemaPattern(ds);
        List<DbTableVo> result = new ArrayList<>();
        try (Connection conn = dataSourceFactory.getDataSource(ds).getConnection();
             ResultSet tables = conn.getMetaData().getTables(catalog, schema, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                DbTableVo vo = new DbTableVo();
                vo.setTableName(tables.getString("TABLE_NAME"));
                vo.setRemarks(tables.getString("REMARKS"));
                result.add(vo);
            }
        } catch (SQLException e) {
            log.error("获取表列表失败", e);
            throw new BizException("获取表列表失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 列出指定表所有字段（供预警规则表单级联选择）
     * 表名做白名单校验，防止非法标识符注入元数据库查询
     */
    @Override
    public List<DbColumnVo> listColumns(Long datasourceId, String tableName) {
        if (tableName == null || !tableName.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
            log.warn("非法表名，拒绝获取字段：{}", tableName);
            return Collections.emptyList();
        }
        BiDatasource ds = selectBiDatasourceById(datasourceId);
        if (ds == null) {
            return Collections.emptyList();
        }
        String catalog = JdbcUrlBuilder.catalog(ds);
        List<DbColumnVo> result = new ArrayList<>();
        try (Connection conn = dataSourceFactory.getDataSource(ds).getConnection();
             ResultSet cols = conn.getMetaData().getColumns(catalog, JdbcUrlBuilder.schemaPattern(ds), tableName, null)) {
            while (cols.next()) {
                DbColumnVo vo = new DbColumnVo();
                vo.setColumnName(cols.getString("COLUMN_NAME"));
                vo.setDataType(cols.getString("TYPE_NAME"));
                vo.setRemarks(cols.getString("REMARKS"));
                result.add(vo);
            }
        } catch (SQLException e) {
            log.error("获取字段列表失败", e);
            throw new BizException("获取字段列表失败：" + e.getMessage());
        }
        return result;
    }
}
