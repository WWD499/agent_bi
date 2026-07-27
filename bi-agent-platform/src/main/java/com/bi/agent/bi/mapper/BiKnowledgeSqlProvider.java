package com.bi.agent.bi.mapper;

import com.bi.agent.bi.domain.BiKnowledge;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * BiKnowledgeMapper 动态 SQL 提供器（替代原 XML 中的 &lt;if&gt; / &lt;foreach&gt; 条件拼接）。
 *
 * <p>provider 方法可直接接收业务对象（如 {@link BiKnowledge}）或带 {@code @Param} 的参数，
 * 返回的字符串若以 {@code <script>} 包裹，MyBatis 会按动态 SQL 解析（支持 &lt;foreach&gt; 等标签）。
 *
 * @author ruoyi-bi (ported to agent-bi)
 */
public class BiKnowledgeSqlProvider {

    public String selectList(BiKnowledge k) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, title, content, source_type, source_url, business_domain, tags, "
                        + "chunk_index, total_chunks, status, create_by, create_time, update_by, update_time, remark "
                        + "FROM bi_knowledge");
        StringBuilder where = new StringBuilder();
        if (k != null) {
            if (k.getTitle() != null && !k.getTitle().isEmpty()) {
                where.append(" AND title LIKE CONCAT('%', #{title}, '%')");
            }
            if (k.getSourceType() != null && !k.getSourceType().isEmpty()) {
                where.append(" AND source_type = #{sourceType}");
            }
            if (k.getBusinessDomain() != null && !k.getBusinessDomain().isEmpty()) {
                where.append(" AND business_domain = #{businessDomain}");
            }
            if (k.getStatus() != null) {
                where.append(" AND status = #{status}");
            }
        }
        if (where.length() > 0) {
            sql.append(" WHERE ").append(where.substring(5));
        }
        sql.append(" ORDER BY create_time DESC");
        return sql.toString();
    }

    public String searchByKeyword(@Param("terms") List<String> terms,
                                 @Param("topK") int topK,
                                 @Param("domain") String domain) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, title, content, source_type, source_url, business_domain, tags, "
                        + "chunk_index, total_chunks, status, create_by, create_time, update_by, update_time, remark "
                        + "FROM bi_knowledge WHERE status = 1 ");
        if (domain != null && !domain.isEmpty()) {
            sql.append("AND business_domain = #{domain} ");
        }
        sql.append("AND (");
        sql.append("<foreach collection='terms' item='t' open='(' separator=' OR ' close=')'>");
        sql.append("content ILIKE CONCAT('%', #{t}, '%')");
        sql.append("</foreach>) ORDER BY id LIMIT #{topK}");
        return "<script>" + sql + "</script>";
    }

    public String updateBiKnowledge(BiKnowledge k) {
        StringBuilder sql = new StringBuilder("UPDATE bi_knowledge <set>");
        if (k.getTitle() != null && !k.getTitle().isEmpty()) {
            sql.append("title = #{title},");
        }
        if (k.getContent() != null && !k.getContent().isEmpty()) {
            sql.append("content = #{content},");
        }
        if (k.getContentVector() != null && !k.getContentVector().isEmpty()) {
            sql.append("content_vector = CAST(#{contentVector} AS vector),");
        }
        if (k.getSourceType() != null && !k.getSourceType().isEmpty()) {
            sql.append("source_type = #{sourceType},");
        }
        if (k.getSourceUrl() != null) {
            sql.append("source_url = #{sourceUrl},");
        }
        if (k.getBusinessDomain() != null && !k.getBusinessDomain().isEmpty()) {
            sql.append("business_domain = #{businessDomain},");
        }
        if (k.getTags() != null) {
            sql.append("tags = #{tags},");
        }
        if (k.getStatus() != null) {
            sql.append("status = #{status},");
        }
        sql.append("update_by = #{updateBy}, update_time = NOW()");
        if (k.getRemark() != null) {
            sql.append(", remark = #{remark}");
        }
        sql.append("</set> WHERE id = #{id}");
        return sql.toString();
    }
}
