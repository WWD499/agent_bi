package com.bi.agent.bi.mapper;

import com.bi.agent.bi.domain.BiOcrRecord;
import org.apache.ibatis.annotations.*;
import java.util.List;

/**
 * OCR 识别历史记录 Mapper（MyBatis 注解风格）。
 */
@Mapper
public interface BiOcrRecordMapper {

    @Insert("INSERT INTO bi_ocr_record(ds_id, image_path, raw_text, structured_json, source, create_by, create_time) "
            + "VALUES(#{dsId}, #{imagePath}, #{rawText}, #{structuredJson}, #{source}, #{createBy}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(BiOcrRecord record);

    @Select("<script>"
            + "SELECT * FROM bi_ocr_record"
            + "<where>"
            + "  <if test='dsId != null'>AND ds_id = #{dsId}</if>"
            + "  <if test=\"keyword != null and keyword != ''\">AND raw_text LIKE CONCAT('%', #{keyword}, '%')</if>"
            + "</where>"
            + "ORDER BY create_time DESC LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<BiOcrRecord> selectList(@Param("dsId") Long dsId,
                                  @Param("keyword") String keyword,
                                  @Param("limit") int limit,
                                  @Param("offset") int offset);

    @Select("SELECT * FROM bi_ocr_record WHERE id = #{id}")
    BiOcrRecord selectById(@Param("id") Long id);

    @Delete("DELETE FROM bi_ocr_record WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
