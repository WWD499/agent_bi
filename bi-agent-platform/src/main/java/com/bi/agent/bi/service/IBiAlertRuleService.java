package com.bi.agent.bi.service;

import com.bi.agent.bi.domain.BiAlertRule;

import java.util.List;

/**
 * 预警规则 Service 接口
 *
 * @author ruoyi-bi (ported to agent-bi)
 */
public interface IBiAlertRuleService {

    List<BiAlertRule> selectBiAlertRuleList(BiAlertRule rule);

    BiAlertRule selectBiAlertRuleById(Long id);

    int insertBiAlertRule(BiAlertRule rule);

    int updateBiAlertRule(BiAlertRule rule);

    int deleteBiAlertRuleByIds(Long[] ids);

    /** 扫描所有启用的规则，执行异常检测（预警引擎入口） */
    int scanAndCheckAlerts();

    /**
     * 单条规则的实时异常分析（供 Agent 的 analyze_alert 工具调用）
     *
     * @param ruleId 预警规则ID
     * @return JSON 字符串：实际值 / 阈值 / 是否触发 / 预警级别 / （触发时）AI 原因分析
     */
    String analyzeAlert(Long ruleId);
}
