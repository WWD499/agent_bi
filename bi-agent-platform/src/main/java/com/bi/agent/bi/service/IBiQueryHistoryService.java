package com.bi.agent.bi.service;

import com.bi.agent.bi.domain.BiQueryHistory;
import java.util.List;

/**
 * NL2SQL 查询历史服务。
 */
public interface IBiQueryHistoryService {

    /** 落库一条查询历史，返回自增 id */
    Long save(BiQueryHistory record);

    /** 按用户分页查询（可选数据源 / 关键词过滤） */
    List<BiQueryHistory> list(String userId, Long datasourceId, String keyword, int page, int size);

    /** 单条详情 */
    BiQueryHistory getById(Long id);

    /** 删除单条 */
    int delete(Long id);

    /** 批量删除 */
    int deleteByIds(List<Long> ids);
}
