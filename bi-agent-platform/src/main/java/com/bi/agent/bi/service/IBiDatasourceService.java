package com.bi.agent.bi.service;

import com.bi.agent.bi.domain.BiDatasource;
import com.bi.agent.bi.vo.DbColumnVo;
import com.bi.agent.bi.vo.DbTableVo;

import java.util.List;

/**
 * BI 数据源配置表 Service 接口
 */
public interface IBiDatasourceService {

    List<BiDatasource> selectBiDatasourceList(BiDatasource datasource);

    BiDatasource selectBiDatasourceById(Long id);

    int insertBiDatasource(BiDatasource datasource);

    int updateBiDatasource(BiDatasource datasource);

    int deleteBiDatasourceById(Long id);

    int deleteBiDatasourceByIds(Long[] ids);

    boolean testConnection(BiDatasource datasource);

    /**
     * 列出数据源当前库中的所有表（供预警规则表单级联选择）
     */
    List<DbTableVo> listTables(Long datasourceId);

    /**
     * 列出指定表的所有字段（供预警规则表单级联选择）
     *
     * @param datasourceId 数据源ID
     * @param tableName     表名（做白名单校验，非法标识符直接返回空）
     */
    List<DbColumnVo> listColumns(Long datasourceId, String tableName);
}
