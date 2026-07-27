package com.bi.agent.bi.mapper;

import com.bi.agent.bi.domain.BiDatasource;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * BI 数据源配置表 Mapper
 * <p>
 * 采用注解式（替代 XML），避免 Spring Boot 可执行 fat jar 下
 * {@code classpath*:/mapper/**} 通配资源扫描扫不到 BOOT-INF/classes 内 XML 的问题。
 * 动态 WHERE 通过 {@link BiDatasourceSqlProvider} 实现。
 */
@Mapper
public interface BiDatasourceMapper {

    @Select("SELECT id, name, type, host, port, database_name, username, password, jdbc_url, "
            + "status, remark, create_time, update_time "
            + "FROM bi_datasource WHERE id = #{id}")
    BiDatasource selectBiDatasourceById(@Param("id") Long id);

    @SelectProvider(type = BiDatasourceSqlProvider.class, method = "selectList")
    List<BiDatasource> selectBiDatasourceList(BiDatasource datasource);

    @Insert("INSERT INTO bi_datasource (name, type, host, port, database_name, username, password, "
            + "jdbc_url, status, remark, create_time) "
            + "VALUES (#{name}, #{type}, #{host}, #{port}, #{databaseName}, #{username}, #{password}, "
            + "#{jdbcUrl}, #{status}, #{remark}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertBiDatasource(BiDatasource datasource);

    @Update("UPDATE bi_datasource SET name=#{name}, type=#{type}, host=#{host}, port=#{port}, "
            + "database_name=#{databaseName}, username=#{username}, password=#{password}, jdbc_url=#{jdbcUrl}, "
            + "status=#{status}, remark=#{remark}, update_time=NOW() WHERE id=#{id}")
    int updateBiDatasource(BiDatasource datasource);

    @Delete("DELETE FROM bi_datasource WHERE id = #{id}")
    int deleteBiDatasourceById(Long id);

    @Delete("<script>DELETE FROM bi_datasource WHERE id IN "
            + "<foreach item='id' collection='array' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteBiDatasourceByIds(Long[] ids);

    @Select("SELECT * FROM bi_datasource WHERE status = #{status} ORDER BY create_time DESC")
    List<BiDatasource> selectByStatus(Integer status);

    @Select("SELECT * FROM bi_datasource WHERE type = #{type} ORDER BY create_time DESC")
    List<BiDatasource> selectByType(String type);
}
