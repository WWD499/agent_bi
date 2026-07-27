package com.bi.agent.bi.service;

import com.bi.agent.bi.domain.BiAlertRecord;
import com.bi.agent.bi.domain.BiAlertRule;

/**
 * BI 预警通知服务
 * 预警触发后负责将异常"通知到人"。
 *
 * <p>Phase 1 暂以结构化日志实现（站内信 / 邮件通道将在 Phase 3 前端 + 通知总线补齐）。
 * 接口保持不变，后续替换实现即可，不影响预警引擎调用方。
 *
 * @author ruoyi-bi (ported to agent-bi)
 */
public interface IBiAlertNotifyService {

    /**
     * 触发预警后的通知处理
     *
     * @param rule   命中的预警规则
     * @param record 已生成的预警记录
     */
    void notify(BiAlertRule rule, BiAlertRecord record);
}
