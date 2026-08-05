package com.bi.agent.bi.mapper;

import com.bi.agent.bi.domain.BiQueryHistory;
import org.apache.ibatis.annotations.*;
import java.util.List;

/**
 * NL2SQL 查询历史 Mapper（MyBatis 注解风格，与 BiOcrRecordMapper 一致）。
 */
@Mapper
public interface BiQueryHistoryMapper {

    @Insert("INSERT INTO bi_query_history(user_id, datasource_id, query, sql, row_count, duration_ms, status, error_msg, create_time) "
            + "VALUES(#{userId}, #{datasourceId}, #{query}, #{sql}, #{rowCount}, #{durationMs}, #{status}, #{errorMsg}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(BiQueryHistory record);

    @Select("<script>"
            + "SELECT * FROM bi_query_history"
            + "<where>"
            + "  AND user_id = #{userId}"
            + "  <if test='datasourceId != null'>AND datasource_id = #{datasourceId}</if>"
            + "  <if test=\"keyword != null and keyword != ''\">AND (query LIKE CONCAT('%', #{keyword}, '%') OR sql LIKE CONCAT('%', #{keyword}, '%'))</if>"
            + "</where>"
            + "ORDER BY create_time DESC LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<BiQueryHistory> selectList(@Param("userId") String userId,
                                    @Param("datasourceId") Long datasourceId,
                                    @Param("keyword") String keyword,
                                    @Param("limit") int limit,
                                    @Param("offset") int offset);

    @Select("SELECT * FROM bi_query_history WHERE id = #{id}")
    BiQueryHistory selectById(@Param("id") Long id);

    @Delete("DELETE FROM bi_query_history WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    @Delete("<script>"
            + "DELETE FROM bi_query_history WHERE id IN"
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int deleteByIds(@Param("ids") List<Long> ids);
}
