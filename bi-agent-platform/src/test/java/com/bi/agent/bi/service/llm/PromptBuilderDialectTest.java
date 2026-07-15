package com.bi.agent.bi.service.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 NL2SQL Prompt 的方言分支逻辑（纯字符串构建，不连数据库）。
 *
 * <p>修复点：原 Prompt 硬编码 MySQL 语法，对 PostgreSQL 数据源产出了 DATE_FORMAT / DATE_SUB
 * 等 MySQL-only 函数，导致 PG 执行报语法错误。现由数据源 type 动态决定方言，默认兜底 PostgreSQL。
 */
class PromptBuilderDialectTest {

    private final PromptBuilder promptBuilder = new PromptBuilder();

    private static final String QUERY = "分析上季度各区域销售额趋势";
    private static final String SCHEMA = "## 表名：sales_order\n字段：\n  - order_date date -- 订单日期\n  - amount numeric -- 金额\n";

    @Test
    void postgresqlDialect_usesPgSyntaxAndNoMysqlFunctions() {
        String prompt = promptBuilder.buildNl2SqlPrompt(QUERY, null, SCHEMA, null, "postgresql");

        assertTrue(prompt.contains("PostgreSQL"), "PG 方言应声明 PostgreSQL 语法");
        assertTrue(prompt.contains("TO_CHAR"), "PG 方言应使用 TO_CHAR 做月份格式化");
        assertFalse(prompt.contains("DATE_FORMAT"), "PG 方言不应出现 MySQL 的 DATE_FORMAT");
        assertFalse(prompt.contains("DATE_SUB"), "PG 方言不应出现 MySQL 的 DATE_SUB");
    }

    @Test
    void mysqlDialect_keepsMysqlSyntax() {
        String prompt = promptBuilder.buildNl2SqlPrompt(QUERY, null, SCHEMA, null, "mysql");

        assertTrue(prompt.contains("MySQL"), "MySQL 方言应声明 MySQL 语法");
        assertTrue(prompt.contains("DATE_FORMAT"), "MySQL 方言应保留 DATE_FORMAT");
    }

    @Test
    void defaultDialect_whenNull_fallsBackToPostgresql() {
        String prompt = promptBuilder.buildNl2SqlPrompt(QUERY, null, SCHEMA, null, null);

        assertTrue(prompt.contains("PostgreSQL"), "默认（null）应兜底为 PostgreSQL 语法");
        assertTrue(prompt.contains("TO_CHAR"), "默认应兜底使用 TO_CHAR");
        assertFalse(prompt.contains("DATE_FORMAT"), "默认不应出现 MySQL 的 DATE_FORMAT");
    }

    @Test
    void defaultDialect_whenUnknownFallsBackToPostgresql() {
        String prompt = promptBuilder.buildNl2SqlPrompt(QUERY, null, SCHEMA, null, "  oracle  ");

        assertTrue(prompt.contains("PostgreSQL"), "未知方言（含空白）应兜底为 PostgreSQL");
        assertTrue(prompt.contains("TO_CHAR"), "未知方言应兜底使用 TO_CHAR");
        assertFalse(prompt.contains("DATE_FORMAT"), "未知方言不应出现 MySQL 的 DATE_FORMAT");
    }
}
