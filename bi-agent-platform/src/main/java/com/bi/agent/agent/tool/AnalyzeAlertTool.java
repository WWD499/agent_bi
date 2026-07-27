package com.bi.agent.agent.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.service.IBiAlertRuleService;

/**
 * Agent 工具：对指定预警规则做实时异常分析（手写 ReAct 版）
 *
 * <p>包装 {@link IBiAlertRuleService#analyzeAlert(Long)}：读取规则当前监控指标实际值、
 * 与阈值比对、判断是否触发，触发时补充 AI 原因分析。
 * 当用户问『某规则现在是否异常』『分析一下 XX 预警』时调用。
 */
public class AnalyzeAlertTool implements AgentTool {

    private final IBiAlertRuleService alertRuleService;

    public AnalyzeAlertTool(IBiAlertRuleService alertRuleService) {
        this.alertRuleService = alertRuleService;
    }

    @Override
    public String name() {
        return "analyze_alert";
    }

    @Override
    public String description() {
        return "对指定预警规则（按 ruleId）做实时异常分析：读取当前监控指标实际值、"
                + "与阈值比对、判断是否触发，并在触发时给出 AI 原因分析。"
                + "当用户问『某预警规则现在是否异常』『分析一下某某预警』时调用。";
    }

    @Override
    public String jsonSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"ruleId\":{\"type\":\"integer\",\"description\":\"预警规则ID，整数\"}},"
                + "\"required\":[\"ruleId\"]}";
    }

    @Override
    public String call(String argsJson) {
        Long ruleId;
        try {
            JSONObject a = JSON.parseObject(argsJson);
            if (a == null || !a.containsKey("ruleId")) {
                return "缺少 ruleId 参数";
            }
            ruleId = a.getLong("ruleId");
        } catch (Exception e) {
            return "参数解析失败：" + e.getMessage();
        }
        if (ruleId == null) {
            return "缺少 ruleId 参数";
        }
        try {
            return alertRuleService.analyzeAlert(ruleId);
        } catch (Exception e) {
            return "分析失败：" + e.getMessage();
        }
    }
}
