package com.bi.agent.bi.mapper;

import com.bi.agent.bi.domain.BiSandboxDb;
import com.bi.agent.bi.domain.BiSandboxTable;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 数据沙箱元数据 Mapper（注解式，与 BiDatasourceMapper 风格一致）。
 *
 * <p>仅管理 bi_sandbox_table / bi_sandbox_db 元数据表；沙箱业务表（sandbox.*）由
 * SandboxImportService / SandboxQueryService 经 JdbcTemplate 动态建表与查询，
 * 不走 MyBatis 动态表名（避免 SQL 注入与 XML 通配扫描问题）。
 *
 * <p>字段命名遵循 mybatis.map-underscore-to-camel-case=true：db_id→dbId、physical_name→physicalName。
 */
@Mapper
public interface BiSandboxMapper {

    // ===== 沙箱表元数据 =====

    @Select("SELECT id, db_id, table_name, physical_name, display_name, owner, columns_json, row_count, source_type, remark, "
            + "to_char(create_time, 'YYYY-MM-DD HH24:MI:SS') AS create_time, "
            + "to_char(update_time, 'YYYY-MM-DD HH24:MI:SS') AS update_time "
            + "FROM bi_sandbox_table ORDER BY db_id, create_time DESC")
    List<BiSandboxTable> selectAll();

    @Select("SELECT id, db_id, table_name, physical_name, display_name, owner, columns_json, row_count, source_type, remark, "
            + "to_char(create_time, 'YYYY-MM-DD HH24:MI:SS') AS create_time, "
            + "to_char(update_time, 'YYYY-MM-DD HH24:MI:SS') AS update_time "
            + "FROM bi_sandbox_table WHERE db_id = #{dbId} ORDER BY create_time DESC")
    List<BiSandboxTable> selectByDbId(@Param("dbId") Long dbId);

    @Select("SELECT id, db_id, table_name, physical_name, display_name, owner, columns_json, row_count, source_type, remark, "
            + "to_char(create_time, 'YYYY-MM-DD HH24:MI:SS') AS create_time, "
            + "to_char(update_time, 'YYYY-MM-DD HH24:MI:SS') AS update_time "
            + "FROM bi_sandbox_table WHERE physical_name = #{physicalName}")
    BiSandboxTable selectByPhysicalName(@Param("physicalName") String physicalName);

    @Select("SELECT id, db_id, table_name, physical_name, display_name, owner, columns_json, row_count, source_type, remark, "
            + "to_char(create_time, 'YYYY-MM-DD HH24:MI:SS') AS create_time, "
            + "to_char(update_time, 'YYYY-MM-DD HH24:MI:SS') AS update_time "
            + "FROM bi_sandbox_table WHERE db_id = #{dbId} AND table_name = #{tableName}")
    BiSandboxTable selectByDbIdAndTable(@Param("dbId") Long dbId, @Param("tableName") String tableName);

    @Insert("INSERT INTO bi_sandbox_table (db_id, table_name, physical_name, display_name, owner, columns_json, "
            + "row_count, source_type, remark, create_time, update_time) "
            + "VALUES (#{dbId}, #{tableName}, #{physicalName}, #{displayName}, #{owner}, #{columnsJson}, #{rowCount}, "
            + "#{sourceType}, #{remark}, NOW(), NOW())")
    int insert(BiSandboxTable record);

    @Update("UPDATE bi_sandbox_table SET row_count = #{rowCount}, update_time = NOW() "
            + "WHERE physical_name = #{physicalName}")
    int updateRowCountByPhysical(@Param("physicalName") String physicalName, @Param("rowCount") int rowCount);

    @Update("UPDATE bi_sandbox_table SET display_name = #{displayName}, update_time = NOW() "
            + "WHERE physical_name = #{physicalName}")
    int updateDisplayNameByPhysical(@Param("physicalName") String physicalName, @Param("displayName") String displayName);

    @Delete("DELETE FROM bi_sandbox_table WHERE physical_name = #{physicalName}")
    int deleteByPhysicalName(@Param("physicalName") String physicalName);

    @Select("SELECT COUNT(1) FROM bi_sandbox_table WHERE db_id = #{dbId} AND table_name = #{tableName}")
    int countByDbAndTable(@Param("dbId") Long dbId, @Param("tableName") String tableName);

    @Select("SELECT COUNT(1) FROM bi_sandbox_table WHERE db_id = #{dbId}")
    int countTablesByDbId(@Param("dbId") Long dbId);

    @Delete("DELETE FROM bi_sandbox_table WHERE db_id = #{dbId}")
    int deleteTablesByDbId(@Param("dbId") Long dbId);

    // ===== 沙箱库（逻辑命名空间） =====

    @Insert("INSERT INTO bi_sandbox_db (db_key, name, owner, remark, create_time, update_time) "
            + "VALUES (#{dbKey}, #{name}, #{owner}, #{remark}, NOW(), NOW())")
    int insertDb(BiSandboxDb record);

    @Select("SELECT id, db_key, name, owner, remark, "
            + "to_char(create_time, 'YYYY-MM-DD HH24:MI:SS') AS create_time, "
            + "to_char(update_time, 'YYYY-MM-DD HH24:MI:SS') AS update_time "
            + "FROM bi_sandbox_db ORDER BY id")
    List<BiSandboxDb> selectAllDb();

    @Select("SELECT id, db_key, name, owner, remark, "
            + "to_char(create_time, 'YYYY-MM-DD HH24:MI:SS') AS create_time, "
            + "to_char(update_time, 'YYYY-MM-DD HH24:MI:SS') AS update_time "
            + "FROM bi_sandbox_db WHERE id = #{id}")
    BiSandboxDb selectDbById(@Param("id") Long id);

    @Select("SELECT id, db_key, name, owner, remark, "
            + "to_char(create_time, 'YYYY-MM-DD HH24:MI:SS') AS create_time, "
            + "to_char(update_time, 'YYYY-MM-DD HH24:MI:SS') AS update_time "
            + "FROM bi_sandbox_db WHERE db_key = #{dbKey}")
    BiSandboxDb selectDbByKey(@Param("dbKey") String dbKey);

    @Delete("DELETE FROM bi_sandbox_db WHERE id = #{id}")
    int deleteDbById(@Param("id") Long id);
}
