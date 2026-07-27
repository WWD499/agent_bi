package com.bi.agent.controller.bi;

import com.bi.agent.bi.domain.BiAlertRecord;
import com.bi.agent.bi.domain.BiAlertRule;
import com.bi.agent.bi.service.IBiAlertRecordService;
import com.bi.agent.bi.service.IBiAlertRuleService;
import com.bi.agent.bi.vo.AlertHandleReq;
import com.bi.agent.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * BI 数据预警控制器（Phase 1）。
 *
 * <p>鉴权：命中 Sa-Token 的 {@code /api/**} 规则，需登录态（token 放 {@code Authorization} 头）。
 *
 * <p>能力：
 * <ul>
 *   <li>GET    /api/bi/alert/rules           — 预警规则列表（按 name/tableName/status 过滤）</li>
 *   <li>GET    /api/bi/alert/rules/{id}      — 规则详情</li>
 *   <li>POST   /api/bi/alert/rules           — 新增规则</li>
 *   <li>PUT    /api/bi/alert/rules           — 修改规则</li>
 *   <li>DELETE /api/bi/alert/rules/{ids}     — 批量删除（ids 逗号分隔）</li>
 *   <li>POST   /api/bi/alert/check           — 手动触发预警引擎扫描（返回触发的预警条数）</li>
 *   <li>GET    /api/bi/alert/records         — 预警记录列表</li>
 *   <li>GET    /api/bi/alert/records/{id}    — 记录详情</li>
 *   <li>POST   /api/bi/alert/records/{id}/handle — 标记处理（更新 status/handledBy/handledRemark）</li>
 *   <li>DELETE /api/bi/alert/records/{ids}   — 批量删除记录（ids 逗号分隔，支持单条）</li>
 * </ul>
 *
 * @author agent-bi
 */
@RestController
@RequestMapping("/api/bi/alert")
public class BiAlertController {

    private static final Logger log = LoggerFactory.getLogger(BiAlertController.class);

    @Autowired
    private IBiAlertRuleService alertRuleService;

    @Autowired
    private IBiAlertRecordService alertRecordService;

    // ==================== 预警规则 ====================

    @GetMapping("/rules")
    public Result<List<BiAlertRule>> listRules(BiAlertRule query) {
        return Result.ok(alertRuleService.selectBiAlertRuleList(query));
    }

    @GetMapping("/rules/{id}")
    public Result<BiAlertRule> getRule(@PathVariable Long id) {
        return Result.ok(alertRuleService.selectBiAlertRuleById(id));
    }

    @PostMapping("/rules")
    public Result<Integer> addRule(@RequestBody BiAlertRule rule) {
        if (rule == null || rule.getName() == null || rule.getName().trim().isEmpty()) {
            return Result.fail(400, "name 不能为空");
        }
        if (rule.getDatasourceId() == null) {
            return Result.fail(400, "datasourceId 不能为空");
        }
        if (rule.getConditionSql() == null || rule.getConditionSql().trim().isEmpty()) {
            return Result.fail(400, "conditionSql 不能为空");
        }
        log.info("新增预警规则：name={}, dsId={}", rule.getName(), rule.getDatasourceId());
        return Result.ok(alertRuleService.insertBiAlertRule(rule));
    }

    @PutMapping("/rules")
    public Result<Integer> updateRule(@RequestBody BiAlertRule rule) {
        if (rule == null || rule.getId() == null) {
            return Result.fail(400, "id 不能为空");
        }
        return Result.ok(alertRuleService.updateBiAlertRule(rule));
    }

    @DeleteMapping("/rules/{ids}")
    public Result<Integer> deleteRules(@PathVariable Long[] ids) {
        return Result.ok(alertRuleService.deleteBiAlertRuleByIds(ids));
    }

    /**
     * 手动触发预警引擎扫描（Phase 1 无 Quartz，由此外部 cron / 按钮触发）
     */
    @PostMapping("/check")
    public Result<Integer> check() {
        int triggered = alertRuleService.scanAndCheckAlerts();
        return Result.ok(triggered);
    }

    // ==================== 预警记录 ====================

    @GetMapping("/records")
    public Result<List<BiAlertRecord>> listRecords(BiAlertRecord query) {
        return Result.ok(alertRecordService.selectBiAlertRecordList(query));
    }

    @GetMapping("/records/{id}")
    public Result<BiAlertRecord> getRecord(@PathVariable Long id) {
        return Result.ok(alertRecordService.selectBiAlertRecordById(id));
    }

    /**
     * 标记处理：更新 status / handledBy / handledRemark；
     * 当 status 为 confirmed/resolved 时由 SQL 自动写入 handled_time。
     */
    @PostMapping("/records/{id}/handle")
    public Result<Integer> handle(@PathVariable Long id, @RequestBody AlertHandleReq req) {
        if (req == null || req.getStatus() == null || req.getStatus().trim().isEmpty()) {
            return Result.fail(400, "status 不能为空");
        }
        BiAlertRecord rec = new BiAlertRecord();
        rec.setId(id);
        rec.setStatus(req.getStatus().trim());
        rec.setHandledBy(req.getHandledBy());
        rec.setHandledRemark(req.getHandledRemark());
        return Result.ok(alertRecordService.updateBiAlertRecord(rec));
    }

    /**
     * 批量删除预警记录（ids 为路径变量，逗号分隔；单条删除传一个 id 即可）。
     * 复用 Service 的 deleteBiAlertRecordByIds（MyBatis IN 动态 SQL）。
     */
    @DeleteMapping("/records/{ids}")
    public Result<Integer> deleteRecords(@PathVariable Long[] ids) {
        return Result.ok(alertRecordService.deleteBiAlertRecordByIds(ids));
    }
}
