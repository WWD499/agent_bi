package com.bi.agent.bi.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.domain.BiAlertRecord;
import com.bi.agent.bi.domain.BiAlertRule;
import com.bi.agent.bi.domain.BiDatasource;
import com.bi.agent.bi.enums.AlertLevel;
import com.bi.agent.bi.enums.AlertStatus;
import com.bi.agent.bi.mapper.BiAlertRecordMapper;
import com.bi.agent.bi.mapper.BiAlertRuleMapper;
import com.bi.agent.bi.service.IBiAlertNotifyService;
import com.bi.agent.bi.service.IBiAlertRuleService;
import com.bi.agent.bi.service.IBiDatasourceService;
import com.bi.agent.bi.service.llm.LlmService;
import com.bi.agent.bi.service.llm.PromptBuilder;
import com.bi.agent.bi.util.BiDataSourceFactory;
import com.bi.agent.bi.util.JdbcUrlBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 预警规则 Service 实现
 * 含异常检测引擎核心逻辑
 *
 * @author ruoyi-bi (ported to agent-bi)
 */
@Service
public class BiAlertRuleServiceImpl implements IBiAlertRuleService {

    private static final Logger log = LoggerFactory.getLogger(BiAlertRuleServiceImpl.class);

    @Autowired
    private BiAlertRuleMapper alertRuleMapper;

    @Autowired
    private BiAlertRecordMapper alertRecordMapper;

    @Autowired
    private IBiDatasourceService datasourceService;

    @Autowired(required = false)
    private LlmService llmService;

    @Autowired(required = false)
    private PromptBuilder promptBuilder;

    @Autowired(required = false)
    private IBiAlertNotifyService notifyService;

    @Autowired
    private BiDataSourceFactory dataSourceFactory;

    @Override
    public List<BiAlertRule> selectBiAlertRuleList(BiAlertRule rule) {
        return alertRuleMapper.selectBiAlertRuleList(rule);
    }

    @Override
    public BiAlertRule selectBiAlertRuleById(Long id) {
        return alertRuleMapper.selectBiAlertRuleById(id);
    }

    @Override
    public int insertBiAlertRule(BiAlertRule rule) {
        if (rule.getStatus() == null) rule.setStatus(1);
        if (rule.getCheckInterval() == null) rule.setCheckInterval(60);
        if (rule.getAnalysisEnabled() == null) rule.setAnalysisEnabled(1);
        return alertRuleMapper.insertBiAlertRule(rule);
    }

    @Override
    public int updateBiAlertRule(BiAlertRule rule) {
        return alertRuleMapper.updateBiAlertRule(rule);
    }

    @Override
    public int deleteBiAlertRuleByIds(Long[] ids) {
        return alertRuleMapper.deleteBiAlertRuleByIds(ids);
    }

    /**
     * ====== 异常检测引擎 ======
     * 扫描所有启用的规则，逐个检查是否需要执行检测
     */
    @Override
    public int scanAndCheckAlerts() {
        List<BiAlertRule> rules = alertRuleMapper.selectEnabledRules();
        log.info("扫描到 {} 条启用的预警规则", rules.size());
        if (rules.isEmpty()) {
            return 0;
        }

        int alertCount = 0;
        LocalDateTime now = LocalDateTime.now();

        for (BiAlertRule rule : rules) {
            try {
                // 1. 判断是否需要检查（根据检查间隔）
                if (!shouldCheck(rule, now)) {
                    continue;
                }

                // 2. 执行数据源查询获取当前值
                Double actualValue = executeCheckQuery(rule);

                // 3. 更新检查时间
                alertRuleMapper.updateLastCheckTime(rule.getId(), now);

                if (actualValue == null) {
                    continue;
                }

                // 4. 比较实际值和阈值
                boolean triggered = compare(actualValue, rule.getThresholdValue(), rule.getComparisonOperator());
                if (triggered) {
                    log.info("预警触发！规则：{}（{}），实际值：{}，阈值：{}，运算符：{}",
                            rule.getName(), rule.getTableName(), actualValue,
                            rule.getThresholdValue(), rule.getComparisonOperator());

                    // 5. 重复预警抑制：同一规则已存在未处理(pending)记录时，跳过重复写入与通知
                    long pending = 0;
                    try {
                        pending = alertRecordMapper.countPendingByRuleId(rule.getId());
                    } catch (Exception e) {
                        log.error("查询 pending 预警数失败", e);
                    }
                    if (pending > 0) {
                        log.info("规则 [{}] 已存在未处理预警，本次跳过重复写入与通知", rule.getName());
                        continue;
                    }

                    // 6. 创建预警记录
                    BiAlertRecord record = createAlertRecord(rule, actualValue, now);
                    // 7. AI 分析原因（如果启用）
                    if (rule.getAnalysisEnabled() != null && rule.getAnalysisEnabled() == 1
                            && llmService != null && promptBuilder != null) {
                        try {
                            String analysis = analyzeAnomaly(rule, actualValue);
                            record.setAnalysisResult(analysis);
                        } catch (Exception e) {
                            log.warn("AI 分析失败，跳过", e);
                        }
                    }

                    alertRecordMapper.insertBiAlertRecord(record);
                    // 8. 通知到人（站内信 + 可选邮件）
                    if (notifyService != null) {
                        try {
                            notifyService.notify(rule, record);
                        } catch (Exception e) {
                            log.warn("预警通知失败，跳过", e);
                        }
                    }

                    alertRuleMapper.updateLastAlertTime(rule.getId(), now);
                    alertCount++;
                }

            } catch (Exception e) {
                log.error("检查预警规则 [{}] 失败", rule.getName(), e);
                // 即使失败也更新检查时间，避免频繁重试
                alertRuleMapper.updateLastCheckTime(rule.getId(), now);
            }
        }

        log.info("预警扫描完成，触发 {} 条预警", alertCount);
        return alertCount;
    }

    /**
     * 单条规则的实时异常分析（Agent 工具入口）
     * <p>复用私有 {@link #executeCheckQuery}、{@link #compare}、{@link #determineAlertLevel}、
     * {@link #analyzeAnomaly}，给出当前监控指标实际值、与阈值比对、是否触发，
     * 触发时再补一段 AI 原因分析，返回紧凑 JSON 供 Agent 综合。
     */
    @Override
    public String analyzeAlert(Long ruleId) {
        if (ruleId == null) {
            return "{\"error\":\"ruleId 不能为空\"}";
        }
        BiAlertRule rule = alertRuleMapper.selectBiAlertRuleById(ruleId);
        if (rule == null) {
            return "{\"error\":\"预警规则不存在，id=" + ruleId + "\"}";
        }
        Double actual = executeCheckQuery(rule);
        JSONObject out = new JSONObject();
        out.put("ruleId", ruleId);
        out.put("ruleName", rule.getName());
        out.put("tableName", rule.getTableName());
        out.put("metricField", rule.getMetricField());
        if (actual == null) {
            out.put("error", "无法获取监控指标当前值（查询为空或数据源不可用）");
            return out.toJSONString();
        }
        boolean triggered = compare(actual, rule.getThresholdValue(), rule.getComparisonOperator());
        out.put("actualValue", actual);
        out.put("thresholdValue", rule.getThresholdValue());
        out.put("comparisonOperator", rule.getComparisonOperator());
        out.put("triggered", triggered);
        out.put("alertLevel", determineAlertLevel(actual, rule.getThresholdValue()));
        if (triggered && llmService != null && promptBuilder != null) {
            try {
                out.put("analysis", analyzeAnomaly(rule, actual));
            } catch (Exception e) {
                log.warn("AI 异常分析失败，跳过", e);
            }
        }
        return out.toJSONString();
    }

    /**
     * 判断是否需要检查
     * <p>包级可见，便于单元测试直接覆盖纯逻辑。</p>
     */
    boolean shouldCheck(BiAlertRule rule, LocalDateTime now) {
        if (rule.getLastCheckTime() == null) {
            return true;
        }
        try {
            LocalDateTime nextAllowed = rule.getLastCheckTime().plusMinutes(rule.getCheckInterval());
            return !nextAllowed.isAfter(now);
        } catch (Exception e) {
            log.warn("计算检查间隔失败，按需要检查处理：ruleId={}", rule.getId(), e);
            return true;
        }
    }

    /**
     * 执行检查查询，获取监控指标当前值
     */
    private Double executeCheckQuery(BiAlertRule rule) {
        BiDatasource datasource = datasourceService.selectBiDatasourceById(rule.getDatasourceId());
        if (datasource == null) {
            log.warn("数据源不存在：id={}", rule.getDatasourceId());
            return null;
        }

        // 走 Hikari 连接池，不再每次 DriverManager.getConnection
        try (Connection conn = dataSourceFactory.getDataSource(datasource).getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(30);
            try (ResultSet rs = stmt.executeQuery(rule.getConditionSql())) {
                if (rs.next()) {
                    // 指标列可能为 NULL，getDouble 会返回 0.0（非 null），
                    // 必须用 wasNull() 判断，否则 NULL 被当成 0.0 可能误触 < 0 类预警
                    double val = rs.getDouble(1);
                    if (rs.wasNull()) {
                        return null;
                    }
                    return val;
                }
            }
        } catch (Exception e) {
            log.error("执行检查SQL失败：{} - {}", rule.getConditionSql(), rule.getName(), e);
        }

        return null;
    }

    /**
     * 比较实际值和阈值
     * <p>包级可见，便于单元测试直接覆盖纯逻辑。</p>
     */
    boolean compare(Double actual, Double threshold, String operator) {
        if (actual == null || threshold == null || operator == null) {
            return false;
        }

        switch (operator) {
            case ">":  return actual > threshold;
            case ">=": return actual >= threshold;
            case "<":  return actual < threshold;
            case "<=": return actual <= threshold;
            case "=":
            case "==": return Math.abs(actual - threshold) < 0.0001;
            case "!=": return Math.abs(actual - threshold) >= 0.0001;
            default:
                log.warn("不支持的比较运算符：{}", operator);
                return false;
        }
    }

    /**
     * 创建预警记录
     */
    private BiAlertRecord createAlertRecord(BiAlertRule rule, Double actualValue, LocalDateTime now) {
        BiAlertRecord record = new BiAlertRecord();
        record.setRuleId(rule.getId());
        record.setRuleName(rule.getName());
        record.setDatasourceId(rule.getDatasourceId());
        record.setTableName(rule.getTableName());
        record.setCheckSql(rule.getConditionSql());
        record.setThresholdValue(rule.getThresholdValue());
        record.setActualValue(actualValue);
        record.setComparisonOperator(rule.getComparisonOperator());
        record.setAlertLevel(determineAlertLevel(actualValue, rule.getThresholdValue()));
        record.setAlertTime(now);
        // 用枚举消灭 "pending" 魔法字符串
        record.setStatus(AlertStatus.PENDING.getCode());

        // 生成预警消息
        String message = String.format("【%s】表 %s 的%s实际值为 %.2f，触发阈值（%s %.2f）",
                rule.getName(), rule.getTableName(),
                rule.getMetricField() != null ? rule.getMetricField() : "指标",
                actualValue, rule.getComparisonOperator(), rule.getThresholdValue());
        record.setAlertMessage(message);

        return record;
    }

    /**
     * 确定预警级别
     * <p>包级可见，便于单元测试直接覆盖纯逻辑。</p>
     */
    String determineAlertLevel(Double actual, Double threshold) {
        // threshold 是 Double 装箱类型，绝不能写成 `threshold == 0`（会自动拆箱 null→NPE）。
        // 先单独判 null，再单独判 0，确保两个分支都不会对非 null 以外的引用做拆箱。
        if (threshold == null) {
            return AlertLevel.WARNING.getCode();
        }
        if (threshold == 0.0) {
            return AlertLevel.WARNING.getCode();
        }
        double deviation = Math.abs(actual - threshold) / Math.abs(threshold);
        if (deviation >= 1.0) return AlertLevel.CRITICAL.getCode();
        if (deviation >= 0.5) return AlertLevel.WARNING.getCode();
        return AlertLevel.INFO.getCode();
    }

    /**
     * AI 分析异常原因
     */
    private String analyzeAnomaly(BiAlertRule rule, Double actualValue) {
        JSONObject data = new JSONObject();
        data.put("ruleName", rule.getName());
        data.put("tableName", rule.getTableName());
        data.put("metricField", rule.getMetricField());
        data.put("actualValue", actualValue);
        data.put("thresholdValue", rule.getThresholdValue());
        data.put("operator", rule.getComparisonOperator());

        // 尝试获取最近数据
        try {
            List<JSONObject> recentData = getRecentData(rule);
            if (recentData != null && !recentData.isEmpty()) {
                data.put("recentData", recentData);
            }
        } catch (Exception e) {
            // 异常必须带堆栈，禁止只打 e.getMessage()
            log.error("获取近期数据失败", e);
        }

        String prompt = promptBuilder.buildAnomalyDetectionPrompt(
                data.toJSONString(), rule.getThresholdValue());

        return llmService.chat(prompt);
    }

    /**
     * 获取最近的监控数据（供 AI 分析参考）
     * 支持 MySQL 和 PostgreSQL 数据源
     */
    private List<JSONObject> getRecentData(BiAlertRule rule) {
        BiDatasource datasource = datasourceService.selectBiDatasourceById(rule.getDatasourceId());
        if (datasource == null) {
            return null;
        }
        String table = rule.getTableName();
        if (table == null || table.trim().isEmpty()) {
            return null;
        }
        // 表名白名单校验，防止非法标识符注入 SQL
        if (!table.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
            log.warn("非法表名，拒绝查询：{}", table);
            return null;
        }

        // 走连接池
        try (Connection conn = dataSourceFactory.getDataSource(datasource).getConnection();
             Statement stmt = conn.createStatement()) {

            // 探测目标表是否存在 id 列，避免无 id 列的表直接抛 SQLException
            boolean hasId = false;
            try (ResultSet cols = conn.getMetaData().getColumns(
                    JdbcUrlBuilder.catalog(datasource),
                    JdbcUrlBuilder.schemaPattern(datasource),
                    table, "id")) {
                hasId = cols.next();
            }

            String limitSql = String.format("SELECT * FROM %s%s LIMIT 10",
                    table, hasId ? " ORDER BY id DESC" : "");

            try (ResultSet rs = stmt.executeQuery(limitSql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                List<JSONObject> rows = new ArrayList<>();

                while (rs.next()) {
                    JSONObject row = new JSONObject();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(meta.getColumnName(i), rs.getString(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (Exception e) {
            // 异常必须带堆栈
            log.error("获取近期数据失败", e);
            return null;
        }
    }
}
