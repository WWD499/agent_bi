package com.bi.agent.bi.mapper;

import com.bi.agent.bi.domain.BiDashboard;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * BI 大屏配置表 Mapper（注解式，动态 WHERE 见 {@link BiDashboardSqlProvider}）
 */
@Mapper
public interface BiDashboardMapper {

    @Select("SELECT id, name, description, config_json, status, is_public, "
            + "access_token, create_time, update_time FROM bi_dashboard WHERE id = #{id}")
    BiDashboard selectBiDashboardById(@Param("id") Long id);

    /** 按访问令牌查询「已公开」大屏（含 config_json）。令牌错误或对应大屏未公开均返回 null（fail-safe，不泄露未公开大屏）。 */
    @Select("SELECT id, name, description, config_json, status, is_public, "
            + "access_token, create_time, update_time FROM bi_dashboard "
            + "WHERE access_token = #{token} AND is_public = '1'")
    BiDashboard selectByAccessToken(@Param("token") String token);

    @SelectProvider(type = BiDashboardSqlProvider.class, method = "selectList")
    List<BiDashboard> selectBiDashboardList(BiDashboard dashboard);

    @Insert("INSERT INTO bi_dashboard (name, description, config_json, thumbnail, status, "
            + "is_public, access_token, create_time) "
            + "VALUES (#{name}, #{description}, #{configJson}, #{thumbnail}, "
            + "COALESCE(#{status}, '1'), COALESCE(#{isPublic}, '0'), #{accessToken}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertBiDashboard(BiDashboard dashboard);

    @Update("UPDATE bi_dashboard SET name=#{name}, description=#{description}, "
            + "config_json=#{configJson}, thumbnail=#{thumbnail}, status=#{status}, "
            + "is_public=#{isPublic}, access_token=#{accessToken}, update_time=NOW() WHERE id=#{id}")
    int updateBiDashboard(BiDashboard dashboard);

    @Delete("DELETE FROM bi_dashboard WHERE id = #{id}")
    int deleteBiDashboardById(Long id);

    @Delete("<script>DELETE FROM bi_dashboard WHERE id IN "
            + "<foreach item='id' collection='array' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteBiDashboardByIds(Long[] ids);
}
