package com.bi.agent.bi.mapper;

import com.bi.agent.bi.domain.BiAlertRule;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 预警规则 Mapper
 * <p>
 * 采用注解式（替代 XML），避免 Spring Boot 可执行 fat jar 下
 * {@code classpath*:/mapper/**} 通配资源扫描扫不到 BOOT-INF/classes 内 XML 的问题。
 * 动态 WHERE 通过 {@link BiAlertRuleSqlProvider} 实现。
 */
@Mapper
public interface BiAlertRuleMapper {

    @SelectProvider(type = BiAlertRuleSqlProvider.class, method = "selectList")
    List<BiAlertRule> selectBiAlertRuleList(BiAlertRule rule);

    @Select("SELECT * FROM bi_alert_config WHERE id = #{id}")
    BiAlertRule selectBiAlertRuleById(Long id);

    @InsertProvider(type = BiAlertRuleSqlProvider.class, method = "insert")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertBiAlertRule(BiAlertRule rule);

    @UpdateProvider(type = BiAlertRuleSqlProvider.class, method = "update")
    int updateBiAlertRule(BiAlertRule rule);

    @Delete("DELETE FROM bi_alert_config WHERE id = #{id}")
    int deleteBiAlertRuleById(Long id);

    @Delete("<script>DELETE FROM bi_alert_config WHERE id IN "
            + "<foreach item='id' collection='array' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteBiAlertRuleByIds(Long[] ids);

    /** 查询所有启用的规则（按最近检查时间升序，让久未检查者优先） */
    @Select("SELECT * FROM bi_alert_config WHERE status = 1 "
            + "ORDER BY last_check_time ASC NULLS FIRST")
    List<BiAlertRule> selectEnabledRules();

    /** 更新最近检查时间 */
    @Update("UPDATE bi_alert_config SET last_check_time = #{lastCheckTime} WHERE id = #{id}")
    int updateLastCheckTime(@Param("id") Long id, @Param("lastCheckTime") LocalDateTime lastCheckTime);

    /** 更新最近预警时间 */
    @Update("UPDATE bi_alert_config SET last_alert_time = #{lastAlertTime} WHERE id = #{id}")
    int updateLastAlertTime(@Param("id") Long id, @Param("lastAlertTime") LocalDateTime lastAlertTime);
}
