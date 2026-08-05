package com.bi.agent.bi.mapper;

import com.bi.agent.bi.domain.BiNotify;
import org.apache.ibatis.annotations.*;
import java.util.List;

/**
 * 站内信 / 用户通知 Mapper（MyBatis 注解风格，与 BiOcrRecordMapper 一致）。
 */
@Mapper
public interface BiNotifyMapper {

    @Insert("INSERT INTO bi_notify(user_id, rule_id, record_id, title, content, level, is_read, create_time) "
            + "VALUES(#{userId}, #{ruleId}, #{recordId}, #{title}, #{content}, #{level}, #{isRead}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(BiNotify notify);

    @Select("<script>"
            + "SELECT * FROM bi_notify"
            + "<where>"
            + "  AND user_id = #{userId}"
            + "  <if test='unreadOnly'>AND is_read = 0</if>"
            + "</where>"
            + "ORDER BY create_time DESC LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<BiNotify> selectList(@Param("userId") String userId,
                              @Param("unreadOnly") boolean unreadOnly,
                              @Param("limit") int limit,
                              @Param("offset") int offset);

    @Select("SELECT * FROM bi_notify WHERE id = #{id}")
    BiNotify selectById(@Param("id") Long id);

    @Update("UPDATE bi_notify SET is_read = 1 WHERE id = #{id} AND user_id = #{userId}")
    int updateReadById(@Param("id") Long id, @Param("userId") String userId);

    @Update("UPDATE bi_notify SET is_read = 1 WHERE user_id = #{userId} AND is_read = 0")
    int updateReadAllByUser(@Param("userId") String userId);

    @Select("SELECT COUNT(*) FROM bi_notify WHERE user_id = #{userId} AND is_read = 0")
    long countUnread(@Param("userId") String userId);

    @Delete("DELETE FROM bi_notify WHERE id = #{id} AND user_id = #{userId}")
    int deleteById(@Param("id") Long id, @Param("userId") String userId);

    @Delete("<script>"
            + "DELETE FROM bi_notify WHERE user_id = #{userId} AND id IN"
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int deleteByIds(@Param("userId") String userId, @Param("ids") List<Long> ids);
}
