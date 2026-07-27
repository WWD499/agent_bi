package com.bi.agent.bi.mapper;

import com.bi.agent.bi.domain.BiAlertRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 预警记录 Mapper
 * <p>注解式（替代 XML），动态 SQL 通过 {@link BiAlertRecordSqlProvider} 实现。</p>
 */
@Mapper
public interface BiAlertRecordMapper {

    @SelectProvider(type = BiAlertRecordSqlProvider.class, method = "selectList")
    List<BiAlertRecord> selectBiAlertRecordList(BiAlertRecord record);

    @Select("SELECT * FROM bi_alert_record WHERE id = #{id}")
    BiAlertRecord selectBiAlertRecordById(Long id);

    @InsertProvider(type = BiAlertRecordSqlProvider.class, method = "insert")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertBiAlertRecord(BiAlertRecord record);

    @UpdateProvider(type = BiAlertRecordSqlProvider.class, method = "update")
    int updateBiAlertRecord(BiAlertRecord record);

    @Delete("DELETE FROM bi_alert_record WHERE id = #{id}")
    int deleteBiAlertRecordById(Long id);

    @Delete("<script>DELETE FROM bi_alert_record WHERE id IN "
            + "<foreach item='id' collection='array' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteBiAlertRecordByIds(Long[] ids);

    /** 统计某规则今天的预警次数（可扩展用） */
    @Select("SELECT COUNT(*) FROM bi_alert_record "
            + "WHERE rule_id = #{ruleId} AND alert_time::DATE = CURRENT_DATE")
    int countTodayByRuleId(Long ruleId);

    /** 统计某规则未处理(pending)的预警数量，用于重复预警抑制 */
    @Select("SELECT COUNT(*) FROM bi_alert_record "
            + "WHERE rule_id = #{ruleId} AND status = 'pending'")
    int countPendingByRuleId(Long ruleId);
}
