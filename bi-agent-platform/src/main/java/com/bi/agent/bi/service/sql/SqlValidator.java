package com.bi.agent.bi.service.sql;

import com.bi.agent.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL安全校验器
 *
 * 五层防护：
 * 1. 操作类型白名单（仅允许SELECT/WITH/EXPLAIN/SHOW等）
 * 2. 关键词黑名单（禁止DROP、DELETE等）
 * 3. SQL注入特征正则
 * 4. 多语句（分号）校验
 * 5. 表名白名单校验（防止LLM编造不存在的表）
 *
 * @author ruoyi-bi (ported to agent-bi)
 */
@Component
public class SqlValidator {

    private static final Logger log = LoggerFactory.getLogger(SqlValidator.class);

    // 禁止的操作关键词（黑名单）
    private static final Set<String> FORBIDDEN_KEYWORDS = new HashSet<>(Arrays.asList(
        "DROP", "DELETE", "UPDATE", "INSERT", "ALTER", "CREATE", "TRUNCATE",
        "REPLACE", "MERGE", "GRANT", "REVOKE", "EXEC", "EXECUTE",
        "INTO OUTFILE", "LOAD DATA", "LOAD XML", "LOCK TABLES", "UNLOCK TABLES"
    ));

    // 允许的操作关键词（白名单）
    private static final Set<String> ALLOWED_OPERATIONS = new HashSet<>(Arrays.asList(
        "SELECT", "WITH", "EXPLAIN", "DESCRIBE", "DESC", "SHOW"
    ));

    // SQL注入特征正则（仅匹配明确的注入特征，避免误杀正常的 AND/OR/UNION 查询）
    // 说明：原先的 \bor\b / \band\b 会匹配任意 WHERE 条件里的 and/or 单词，导致正常业务 SQL 被误拒。
    // 这里只拦：字符串后接注释(' -- / "--" / '#)、块注释混淆(/* */)、恒真条件注入('or'1'='1' / or 1=1 / and 1=1)。
    // 只读 + 操作白名单 + 表名白名单 + 多语句拦截 已在前几层覆盖，无需在此过度拦截。
    private static final Pattern SQL_INJECTION_PATTERN = Pattern.compile(
        "(?i)"
        + "('|\")\\s*--"                                                   // 字符串后接注释注入
        + "|('|\")\\s*#"                                                   // MySQL 行注释注入
        + "|/\\*.*\\*/"                                                     // 块注释混淆
        + "|('|\")\\s*(or|and)\\s*(('|\")?\\d+('|\")?)\\s*=\\s*(('|\")?\\d+('|\")?)"  // 'or'1'='1' 恒真注入（数字可带引号）
        + "|(?<!\\w)(or|and)\\s+\\d+\\s*=\\s*\\d+",                       // or 1=1 / and 1=1 恒真注入
        Pattern.CASE_INSENSITIVE
    );

    // 字符串字面量正则（用于排除字符串内的关键词）
    private static final Pattern STRING_LITERAL_PATTERN = Pattern.compile(
        "'[^']*'|\"[^\"]*\"",
        Pattern.CASE_INSENSITIVE
    );

    // 提取SQL中表名的正则（匹配 FROM/JOIN 后面的表名）
    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile(
            "(?i)\\b(?:FROM|JOIN)\\s+`?([a-zA-Z_][a-zA-Z0-9_]*)`?",
            Pattern.CASE_INSENSITIVE
    );

    // EXTRACT(... ) 函数体（含其内部的 FROM 关键字）。
    // 典型故障：EXTRACT(YEAR FROM o.order_date) 里的「FROM o」会被上面的表名正则误判为表名 o，
    // 触发「表名 'o' 不存在」（见 run_sql 带别名场景：FROM fact_sales_order o 本合法却因 EXTRACT 内部 FROM 被误伤）。
    private static final Pattern EXTRACT_FUNC_PATTERN = Pattern.compile(
            "(?i)EXTRACT\\s*\\([^()]*\\)",
            Pattern.CASE_INSENSITIVE
    );

    // CTE（公用表表达式）别名：WITH name AS (...) 的首个别名，或 , name AS (...) 的后续别名。
    // 典型故障：WITH quarterly_sales AS (SELECT ... FROM fact_sales_order ...)
    // 后续 SELECT ... FROM quarterly_sales 会被表名正则误判为「表名 'quarterly_sales' 不存在」。
    // 这里提取 CTE 别名，使其在白名单校验中被放行（它本身不是物理表）。
    private static final Pattern CTE_NAME_PATTERN = Pattern.compile(
            "(?i)(?:\\bWITH\\s+(?:RECURSIVE\\s+)?|,)\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s+AS\\s*\\(",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 校验SQL是否安全
     *
     * @param sql 待校验的SQL
     * @throws BizException 如果SQL不安全
     */
    public void validate(String sql) {
        validate(sql, null);
    }

    /**
     * 校验SQL是否安全（含表名白名单校验）
     *
     * @param sql           待校验的SQL
     * @param allowedTables 允许的表名集合（为null或空则跳过表名校验）
     * @throws BizException 如果SQL不安全
     */
    public void validate(String sql, Set<String> allowedTables) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new BizException("SQL不能为空");
        }

        String trimmedSql = sql.trim();
        log.debug("校验SQL：{}", trimmedSql.substring(0, Math.min(100, trimmedSql.length())));

        // 第一层：检查是否以允许的操作为开头
        validateOperation(trimmedSql);

        // 第二层：检查黑名单关键词
        validateBlacklist(trimmedSql);

        // 第三层：检查SQL注入特征
        validateInjection(trimmedSql);

        // 第四层：检查是否包含多个语句（分号）
        validateMultiStatement(trimmedSql);

        // 第五层：表名白名单校验
        if (allowedTables != null && !allowedTables.isEmpty()) {
            validateTableNames(trimmedSql, allowedTables);
        }

        log.debug("SQL校验通过");
    }

    /**
     * 从 SQL 中提取所有表名（FROM / JOIN 之后），供调用方做主表选取等用途。
     *
     * <p>与第五层表名白名单校验共用同一正则；表名不区分大小写返回。
     * 标识符支持可选的反引号（`col`）或双引号（"col"）转义。
     *
     * @param sql 待解析的 SQL
     * @return 表名列表（去重、保持首次出现顺序）
     */
    public List<String> extractTableNames(String sql) {
        List<String> result = new ArrayList<>();
        if (sql == null || sql.trim().isEmpty()) {
            return result;
        }
        // 提取表名前先剥离 EXTRACT(... ) 函数体，避免其内部「FROM 别名」被误判为表名
        String scanSql = stripExtractFunctions(sql);
        // 跳过 WITH ... AS (...) 定义的 CTE 别名（它不是真实物理表）
        Set<String> cteNames = extractCteNames(sql);
        Matcher matcher = TABLE_NAME_PATTERN.matcher(scanSql);
        Set<String> seen = new HashSet<>();
        while (matcher.find()) {
            String tableName = matcher.group(1);
            if (cteNames.contains(tableName.toLowerCase())) {
                continue;
            }
            if (seen.add(tableName.toLowerCase())) {
                result.add(tableName);
            }
        }
        return result;
    }

    /**
     * 第一层：检查操作类型
     */
    private void validateOperation(String sql) {
        String upperSql = sql.toUpperCase().trim();

        // 提取第一个关键词
        String firstKeyword = upperSql.split("\\s+")[0];

        if (!ALLOWED_OPERATIONS.contains(firstKeyword)) {
            throw new BizException(
                "不允许的操作类型：" + firstKeyword + "。仅允许：" + ALLOWED_OPERATIONS
            );
        }
    }

    /**
     * 第二层：检查黑名单关键词
     * 注意：需要排除字符串字面量内的关键词
     */
    private void validateBlacklist(String sql) {
        // 移除字符串字面量（避免误判）
        String sqlWithoutStrings = STRING_LITERAL_PATTERN.matcher(sql).replaceAll("");

        String upperSql = sqlWithoutStrings.toUpperCase();

        for (String keyword : FORBIDDEN_KEYWORDS) {
            if (upperSql.contains(keyword)) {
                throw new BizException(
                    "SQL包含禁止的关键词：" + keyword
                );
            }
        }
    }

    /**
     * 第三层：检查SQL注入特征
     */
    private void validateInjection(String sql) {
        if (SQL_INJECTION_PATTERN.matcher(sql).find()) {
            throw new BizException("SQL可能包含注入攻击特征，已拒绝执行");
        }
    }

    /**
     * 第四层：检查多个语句
     */
    private void validateMultiStatement(String sql) {
        // 移除字符串内的分号
        String sqlWithoutStrings = STRING_LITERAL_PATTERN.matcher(sql).replaceAll("");

        // 检查是否有分号（多个语句）
        if (sqlWithoutStrings.contains(";")) {
            // 允许末尾的分号
            String withoutTrailingSemicolon = sqlWithoutStrings.replaceAll(";\\s*$", "");
            if (withoutTrailingSemicolon.contains(";")) {
                throw new BizException("不允许执行多个SQL语句");
            }
        }
    }

    /**
     * 第五层：校验SQL中引用的表名是否在白名单内
     */
    private void validateTableNames(String sql, Set<String> allowedTables) {
        // 构建小写表名集合（不区分大小写）
        Set<String> lowerAllowed = new HashSet<>();
        for (String t : allowedTables) {
            lowerAllowed.add(t.toLowerCase());
        }
        // CTE（WITH ... AS (...)）定义的别名不是真实物理表，加入放行集合，
        // 避免「表名 'quarterly_sales' 不存在」这类误报
        Set<String> cteNames = extractCteNames(sql);
        lowerAllowed.addAll(cteNames);

        // 提取SQL中所有表名
        // 提取表名前先剥离 EXTRACT(... ) 函数体，避免其内部「FROM 别名」被误判为表名
        String scanSql = stripExtractFunctions(sql);
        Matcher matcher = TABLE_NAME_PATTERN.matcher(scanSql);
        List<String> foundTables = new ArrayList<>();
        while (matcher.find()) {
            String tableName = matcher.group(1).toLowerCase();
            // 跳过 CTE 别名本身（虽已加入放行集合，这里统一跳过，避免把它当真实表放入待校验列表）
            if (cteNames.contains(tableName)) {
                continue;
            }
            foundTables.add(tableName);
        }

        // 检查每个表名是否在白名单中
        for (String table : foundTables) {
            if (!lowerAllowed.contains(table)) {
                throw new BizException(
                    "表名 '" + table + "' 不存在。可用表：" + allowedTables
                );
            }
        }

        if (foundTables.isEmpty()) {
            log.warn("SQL中未解析到表名，跳过表名校验");
        }
    }

    /**
     * 剥离 SQL 中的 EXTRACT(... ) 函数体（含其内部的 FROM 关键字）。
     * <p>否则 {@code EXTRACT(YEAR FROM o.order_date)} 里的 “FROM o” 会被表名正则误判为表名 o，
     * 触发“表名不存在”误报（典型见 run_sql 带别名场景：
     * {@code FROM fact_sales_order o} 本合法，却因 EXTRACT 内部的 FROM 被误伤）。
     */
    private String stripExtractFunctions(String sql) {
        if (sql == null) {
            return sql;
        }
        return sql.replaceAll("(?i)EXTRACT\\s*\\([^()]*\\)", " ");
    }

    /**
     * 提取 SQL 中的 CTE（公用表表达式）名称。
     * <p>匹配 {@code WITH name AS (...)} 的首个别名，以及 {@code , name AS (...)} 的后续别名。
     * 这些别名不是物理表，不应被表名白名单拦截。
     */
    private Set<String> extractCteNames(String sql) {
        Set<String> cteNames = new HashSet<>();
        if (sql == null) {
            return cteNames;
        }
        Matcher matcher = CTE_NAME_PATTERN.matcher(sql);
        while (matcher.find()) {
            cteNames.add(matcher.group(1).toLowerCase());
        }
        return cteNames;
    }
}
