package com.bi.agent.bi.mapper;

import com.bi.agent.bi.domain.BiKnowledge;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * RAG 知识库 Mapper（注解式，与 BiDatasourceMapper 同惯例）。
 * <p>动态 WHERE / foreach 通过 {@link BiKnowledgeSqlProvider} 实现，
 * 矢量检索的 {@code <=>} 余弦距离运算直接内联在 {@code @Select} 中。
 *
 * <p>注意：所有 SELECT 都显式列出列、跳过 {@code content_vector}，
 * 避免 MyBatis 尝试把 PG 的 vector 类型读回 String 引发类型不匹配。
 *
 * @author ruoyi-bi (ported to agent-bi)
 */
@Mapper
public interface BiKnowledgeMapper {

    @Select("SELECT id, title, content, source_type, source_url, business_domain, tags, "
            + "chunk_index, total_chunks, status, create_by, create_time, update_by, update_time, remark "
            + "FROM bi_knowledge WHERE id = #{id}")
    BiKnowledge selectBiKnowledgeById(@Param("id") Long id);

    @SelectProvider(type = BiKnowledgeSqlProvider.class, method = "selectList")
    List<BiKnowledge> selectBiKnowledgeList(BiKnowledge knowledge);

    @SelectProvider(type = BiKnowledgeSqlProvider.class, method = "searchByKeyword")
    List<BiKnowledge> searchByKeyword(@Param("terms") List<String> terms,
                                      @Param("topK") int topK,
                                      @Param("domain") String domain);

    /**
     * 向量相似度检索（pgvector 余弦距离 {@code <=>}）。
     * <p>{@code #{domain} IS NULL} 走参数绑定：domain 为 null 时占位符为 NULL → 恒真，
     * 非 null 时 {@code business_domain = ?} 生效。无需动态 SQL。
     */
    @Select("SELECT id, title, content, source_type, source_url, business_domain, tags, "
            + "chunk_index, total_chunks, status, create_by, create_time, update_by, update_time, remark, "
            + "(content_vector <=> CAST(#{vectorStr} AS vector)) AS similarity "
            + "FROM bi_knowledge "
            + "WHERE status = 1 "
            + "AND (CAST(#{domain} AS varchar) IS NULL OR business_domain = #{domain}) "
            + "ORDER BY content_vector <=> CAST(#{vectorStr} AS vector) "
            + "LIMIT #{topK}")
    List<BiKnowledge> searchByVector(@Param("vectorStr") String vectorStr,
                                    @Param("topK") int topK,
                                    @Param("domain") String domain);

    @Select("SELECT id, title, content, source_type, source_url, business_domain, tags, "
            + "chunk_index, total_chunks, status, create_by, create_time, update_by, update_time, remark "
            + "FROM bi_knowledge WHERE status = 1 AND business_domain = #{domain} ORDER BY create_time DESC")
    List<BiKnowledge> selectByDomain(@Param("domain") String domain);

    @Insert("INSERT INTO bi_knowledge (title, content, content_vector, source_type, source_url, "
            + "business_domain, tags, chunk_index, total_chunks, status, create_by, create_time, update_by, update_time, remark) "
            + "VALUES (#{title}, #{content}, CAST(#{contentVector} AS vector), #{sourceType}, #{sourceUrl}, "
            + "#{businessDomain}, #{tags}, #{chunkIndex}, #{totalChunks}, #{status}, "
            + "#{createBy}, NOW(), #{updateBy}, NOW(), #{remark})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertBiKnowledge(BiKnowledge knowledge);

    @Insert("<script>INSERT INTO bi_knowledge (title, content, content_vector, source_type, source_url, "
            + "business_domain, tags, chunk_index, total_chunks, status, create_by, create_time, remark) VALUES "
            + "<foreach collection='list' item='item' separator=','>"
            + "(#{item.title}, #{item.content}, CAST(#{item.contentVector} AS vector), #{item.sourceType}, "
            + "#{item.sourceUrl}, #{item.businessDomain}, #{item.tags}, #{item.chunkIndex}, "
            + "#{item.totalChunks}, #{item.status}, #{item.createBy}, NOW(), #{item.remark})"
            + "</foreach></script>")
    int batchInsertBiKnowledge(List<BiKnowledge> list);

    @UpdateProvider(type = BiKnowledgeSqlProvider.class, method = "updateBiKnowledge")
    int updateBiKnowledge(BiKnowledge knowledge);

    @Delete("DELETE FROM bi_knowledge WHERE id = #{id}")
    int deleteBiKnowledgeById(@Param("id") Long id);

    @Delete("<script>DELETE FROM bi_knowledge WHERE id IN "
            + "<foreach collection='array' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteBiKnowledgeByIds(@Param("ids") Long[] ids);
}
