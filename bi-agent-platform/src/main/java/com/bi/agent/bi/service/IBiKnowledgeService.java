package com.bi.agent.bi.service;

import com.bi.agent.bi.domain.BiKnowledge;

import java.util.List;

/**
 * BI 业务知识库 Service 接口
 * <p>RAG 向量检索（PG pgvector + BAAI/bge-m3 语义检索，关键词 ILIKE 兜底）。
 *
 * <p>{@link #buildRagContext(String, Long)} 由 NL2SQL 主流程调用，
 * 把召回的业务口径/术语注入 Prompt，提升生成 SQL 的准确度。
 */
public interface IBiKnowledgeService {

    /**
     * 新增知识条目（自动切分 + 向量化入库）
     */
    int insertBiKnowledge(BiKnowledge knowledge);

    /**
     * 批量新增知识条目（用于文档切分后批量插入）
     */
    int batchInsertBiKnowledge(List<BiKnowledge> list);

    /**
     * 根据ID查询知识条目
     */
    BiKnowledge selectBiKnowledgeById(Long id);

    /**
     * 查询知识条目列表（带条件）
     */
    List<BiKnowledge> selectBiKnowledgeList(BiKnowledge knowledge);

    /**
     * 更新知识条目（内容有变更时自动重新向量化）
     */
    int updateBiKnowledge(BiKnowledge knowledge);

    /**
     * 根据ID删除知识条目
     */
    int deleteBiKnowledgeById(Long id);

    /**
     * 批量删除知识条目
     */
    int deleteBiKnowledgeByIds(Long[] ids);

    /**
     * 相似度检索（向量优先，向量不可用/为空时转关键词兜底）
     *
     * @param query 用户查询
     * @param topK  返回最相似的K条
     * @param domain 业务领域过滤（可null，不过滤）
     * @return 相似度排序的知识条目列表
     */
    List<BiKnowledge> searchSimilar(String query, int topK, String domain);

    /**
     * 构建 RAG 业务知识上下文（注入 NL2SQL Prompt）
     *
     * @param query        用户自然语言查询
     * @param datasourceId 数据源ID（可null，Phase1 暂不按数据源过滤业务领域）
     * @return 知识上下文文本（无则空串，不阻断主流程）
     */
    String buildRagContext(String query, Long datasourceId);

    /**
     * 单条重新向量化
     */
    void reEmbed(Long id);

    /**
     * 全量重新向量化（embedding 模型切换后使用）
     */
    void batchReEmbed();
}
