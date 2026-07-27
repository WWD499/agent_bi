package com.bi.agent.bi.task;

import com.bi.agent.bi.service.IBiAlertRuleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * BI 数据预警定时检查任务
 *
 * <p>Phase 1 不引入 Quartz，改由控制器 {@code POST /api/bi/alert/check} 或外部 cron
 * 手动触发 {@link #scanAndCheckAlerts()}。保留该 Bean（beanName = "biAlertCheckTask"），
 * 后续若接若依 Quartz（sys_job）可直接以
 * {@code biAlertCheckTask.scanAndCheckAlerts()} 作为 invoke_target 调度。
 *
 * @author ruoyi-bi (ported to agent-bi)
 */
@Component("biAlertCheckTask")
public class BiAlertCheckTask {

    private static final Logger log = LoggerFactory.getLogger(BiAlertCheckTask.class);

    @Autowired
    private IBiAlertRuleService alertRuleService;

    /**
     * 扫描并执行所有启用的预警规则
     */
    public int scanAndCheckAlerts() {
        log.info("===== BI 数据预警检查开始 =====");
        long startTime = System.currentTimeMillis();

        try {
            int alertCount = alertRuleService.scanAndCheckAlerts();
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("===== BI 数据预警检查完成，触发 {} 条预警，耗时 {} ms =====", alertCount, elapsed);
            return alertCount;
        } catch (Exception e) {
            log.error("===== BI 数据预警检查异常 =====", e);
            return 0;
        }
    }
}
