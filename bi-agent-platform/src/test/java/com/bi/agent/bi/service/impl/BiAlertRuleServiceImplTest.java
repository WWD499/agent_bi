package com.bi.agent.bi.service.impl;

import com.bi.agent.bi.domain.BiAlertRule;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 预警纯逻辑单元测试（Phase 1 收口回归守卫）。
 *
 * <p>不启动 Spring 容器，直接 {@code new BiAlertRuleServiceImpl()} 调用包级可见的
 * 纯函数（shouldCheck / compare / determineAlertLevel），无需数据库。
 */
class BiAlertRuleServiceImplTest {

    private final BiAlertRuleServiceImpl service = new BiAlertRuleServiceImpl();

    // ===================== shouldCheck =====================

    @Test
    void shouldCheck_trueWhenLastCheckNull() {
        BiAlertRule rule = new BiAlertRule();
        assertTrue(service.shouldCheck(rule, LocalDateTime.now()));
    }

    @Test
    void shouldCheck_falseWithinInterval() {
        LocalDateTime now = LocalDateTime.now();
        BiAlertRule rule = new BiAlertRule();
        rule.setLastCheckTime(now.minusMinutes(10));
        rule.setCheckInterval(60);
        // 下次允许时间 = now-10min + 60min = now+50min，仍在未来 → 不检查
        assertFalse(service.shouldCheck(rule, now));
    }

    @Test
    void shouldCheck_trueAfterInterval() {
        LocalDateTime now = LocalDateTime.now();
        BiAlertRule rule = new BiAlertRule();
        rule.setLastCheckTime(now.minusMinutes(70));
        rule.setCheckInterval(60);
        // 下次允许时间 = now-70min + 60min = now-10min，已过 → 检查
        assertTrue(service.shouldCheck(rule, now));
    }

    @Test
    void shouldCheck_trueWhenIntervalNullFallsToExceptionPath() {
        LocalDateTime now = LocalDateTime.now();
        BiAlertRule rule = new BiAlertRule();
        rule.setLastCheckTime(now.minusMinutes(10));
        rule.setCheckInterval(null); // plusMinutes(null) 自动拆箱 NPE → catch → 按需要检查
        assertTrue(service.shouldCheck(rule, now));
    }

    // ===================== compare =====================

    @Test
    void compare_greaterThan() {
        assertTrue(service.compare(5.0, 3.0, ">"));
        assertFalse(service.compare(3.0, 3.0, ">"));
        assertFalse(service.compare(2.0, 3.0, ">"));
    }

    @Test
    void compare_greaterThanOrEqual() {
        assertTrue(service.compare(3.0, 3.0, ">="));
        assertFalse(service.compare(2.0, 3.0, ">="));
    }

    @Test
    void compare_lessThan() {
        assertTrue(service.compare(2.0, 3.0, "<"));
        assertFalse(service.compare(3.0, 3.0, "<"));
    }

    @Test
    void compare_lessThanOrEqual() {
        assertTrue(service.compare(3.0, 3.0, "<="));
        assertFalse(service.compare(4.0, 3.0, "<="));
    }

    @Test
    void compare_equalsWithTolerance() {
        assertTrue(service.compare(3.0, 3.0, "=="));
        assertTrue(service.compare(3.0, 3.0, "="));
        // 差值 0.0001 不 < 0.0001 → 不相等
        assertFalse(service.compare(3.0001, 3.0, "=="));
        // 差值 0.00005 < 0.0001 → 相等
        assertTrue(service.compare(3.00005, 3.0, "=="));
    }

    @Test
    void compare_notEquals() {
        assertTrue(service.compare(3.1, 3.0, "!="));
        assertFalse(service.compare(3.0, 3.0, "!="));
    }

    @Test
    void compare_falseOnNulls() {
        assertFalse(service.compare(null, 3.0, ">"));
        assertFalse(service.compare(3.0, null, ">"));
        assertFalse(service.compare(3.0, 3.0, null));
    }

    @Test
    void compare_falseOnUnknownOperator() {
        assertFalse(service.compare(3.0, 3.0, "***"));
    }

    // ===================== determineAlertLevel =====================

    @Test
    void determineAlertLevel_warningWhenThresholdNull() {
        assertEquals("warning", service.determineAlertLevel(5.0, null));
    }

    @Test
    void determineAlertLevel_warningWhenThresholdZero() {
        assertEquals("warning", service.determineAlertLevel(5.0, 0.0));
    }

    @Test
    void determineAlertLevel_criticalAtDeviationOne() {
        // 实测验证场景：actual=2.0, threshold=1.0 → 偏差 1.0 ≥ 1.0 → critical
        assertEquals("critical", service.determineAlertLevel(2.0, 1.0));
    }

    @Test
    void determineAlertLevel_warningAtDeviationHalf() {
        // actual=1.5, threshold=1.0 → 偏差 0.5 ≥ 0.5 → warning
        assertEquals("warning", service.determineAlertLevel(1.5, 1.0));
    }

    @Test
    void determineAlertLevel_infoBelowHalf() {
        // actual=1.2, threshold=1.0 → 偏差 0.2 < 0.5 → info
        assertEquals("info", service.determineAlertLevel(1.2, 1.0));
    }

    @Test
    void determineAlertLevel_criticalWhenActualBelowThreshold() {
        // actual=0, threshold=1.0 → 偏差 1.0 → critical
        assertEquals("critical", service.determineAlertLevel(0.0, 1.0));
    }
}
