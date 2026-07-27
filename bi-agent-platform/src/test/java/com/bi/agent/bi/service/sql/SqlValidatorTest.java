package com.bi.agent.bi.service.sql;

import com.bi.agent.common.BizException;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQL 安全校验器回归测试。
 *
 * <p>重点守卫第三层「注入特征」正则：必须放行正常的 AND/OR/UNION 业务查询，
 * 同时仍能拦截注释注入、块注释混淆与恒真条件注入。
 */
class SqlValidatorTest {

    private final SqlValidator validator = new SqlValidator();

    // ===================== 正常业务 SQL 必须放行 =====================

    @Test
    void normalSelectWithAnd_pass() {
        String sql = "SELECT region, SUM(amount) FROM sales WHERE region = '华东' AND category = '食品'";
        assertDoesNotThrow(() -> validator.validate(sql));
    }

    @Test
    void normalSelectWithOr_pass() {
        String sql = "SELECT * FROM orders WHERE status = 'active' OR status = 'pending'";
        assertDoesNotThrow(() -> validator.validate(sql));
    }

    @Test
    void normalSelectWithAndOrMixed_pass() {
        String sql = "SELECT id FROM t WHERE a = 1 AND (b = 2 OR c = 3)";
        assertDoesNotThrow(() -> validator.validate(sql));
    }

    @Test
    void normalQuotedNumericEquality_pass() {
        // 带引号数字的等值查询（如字典编码）不应被恒真规则误杀
        String sql = "SELECT * FROM t WHERE a = '1' AND b = '2'";
        assertDoesNotThrow(() -> validator.validate(sql));
    }

    @Test
    void normalUnionSelect_pass() {
        String sql = "SELECT dept, cnt FROM v1 UNION SELECT dept, cnt FROM v2";
        assertDoesNotThrow(() -> validator.validate(sql));
    }

    @Test
    void normalSelectWithQuotedString_pass() {
        // 字符串里含 "and" 也不应误杀
        String sql = "SELECT * FROM logs WHERE msg = 'please and or wait'";
        assertDoesNotThrow(() -> validator.validate(sql));
    }

    // ===================== 真正的注入特征必须拦截 =====================

    @Test
    void commentInjectionAfterString_rejected() {
        String sql = "SELECT * FROM users WHERE name = '' -- comment";
        BizException ex = assertThrows(BizException.class, () -> validator.validate(sql));
        assertTrue(ex.getMessage().contains("注入"));
    }

    @Test
    void blockCommentObfuscation_rejected() {
        String sql = "SELECT * FROM t WHERE id = 1 /* evil */ OR 1=1";
        BizException ex = assertThrows(BizException.class, () -> validator.validate(sql));
        assertTrue(ex.getMessage().contains("注入"));
    }

    @Test
    void tautologyQuoted_rejected() {
        String sql = "SELECT * FROM users WHERE name = '' OR '1'='1'";
        BizException ex = assertThrows(BizException.class, () -> validator.validate(sql));
        assertTrue(ex.getMessage().contains("注入"));
    }

    @Test
    void tautologyNumericOr_rejected() {
        String sql = "SELECT * FROM t WHERE id = 1 OR 1=1";
        BizException ex = assertThrows(BizException.class, () -> validator.validate(sql));
        assertTrue(ex.getMessage().contains("注入"));
    }

    @Test
    void tautologyNumericAnd_rejected() {
        String sql = "SELECT * FROM t WHERE id = 1 AND 1=1";
        BizException ex = assertThrows(BizException.class, () -> validator.validate(sql));
        assertTrue(ex.getMessage().contains("注入"));
    }

    @Test
    void tautologyDoubleQuoted_rejected() {
        String sql = "SELECT * FROM t WHERE name = \"\" OR \"1\"=\"1\"";
        BizException ex = assertThrows(BizException.class, () -> validator.validate(sql));
        assertTrue(ex.getMessage().contains("注入"));
    }

    @Test
    void tautologyNoSpace_rejected() {
        String sql = "SELECT * FROM t WHERE x='a' OR'1'='1'";
        BizException ex = assertThrows(BizException.class, () -> validator.validate(sql));
        assertTrue(ex.getMessage().contains("注入"));
    }

    // ===================== 其他层仍生效 =====================

    @Test
    void forbiddenKeyword_drop_rejected() {
        String sql = "DROP TABLE users";
        assertThrows(BizException.class, () -> validator.validate(sql));
    }

    @Test
    void tableWhitelist_blocksUnknownTable() {
        Set<String> allowed = new HashSet<>();
        allowed.add("sales");
        BizException ex = assertThrows(BizException.class,
                () -> validator.validate("SELECT * FROM secret_table", allowed));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    // ===================== CTE（公用表表达式）别名必须放行 =====================

    @Test
    void cteSelect_cteAliasAllowed_pass() {
        // 复现 select_chart 失败：WITH quarterly_sales AS (...) 后 SELECT ... FROM quarterly_sales
        // quarterly_sales 是 CTE 别名（非物理表），不应被表名白名单误拒；
        // 内部真实表 fact_sales_order 仍在白名单内，整句应放行
        Set<String> allowed = new HashSet<>();
        allowed.add("fact_sales_order");
        String sql = "WITH quarterly_sales AS ("
                + " SELECT region_id, EXTRACT(MONTH FROM order_date) AS month, SUM(amount) AS total"
                + " FROM fact_sales_order"
                + " WHERE order_date BETWEEN '2025-07-01' AND '2025-09-30'"
                + " GROUP BY region_id, EXTRACT(MONTH FROM order_date))"
                + " SELECT region_id, month, total FROM quarterly_sales ORDER BY region_id, month";
        assertDoesNotThrow(() -> validator.validate(sql, allowed));
    }

    @Test
    void cteSelect_innerRealTableStillValidated() {
        // CTE 放行的是别名本身，内部真实表仍在白名单约束内：
        // 内部 FROM fake_table 不在白名单 → 仍应被拒
        Set<String> allowed = new HashSet<>();
        allowed.add("fact_sales_order");
        String sql = "WITH cte AS (SELECT * FROM fake_table) SELECT * FROM cte";
        BizException ex = assertThrows(BizException.class, () -> validator.validate(sql, allowed));
        assertTrue(ex.getMessage().contains("不存在"));
    }
}
