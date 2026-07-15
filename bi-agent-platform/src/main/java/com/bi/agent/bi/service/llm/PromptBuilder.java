package com.bi.agent.bi.service.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Prompt 模板构建器
 *
 * 构建用于 NL2SQL、数据解读等场景的 Prompt 模板
 * 支持：表结构注入、示例注入、业务术语注入
 */
@Component
public class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);

    /**
     * 构建 NL2SQL 的 Prompt
     *
     * @param userQuery     用户自然语言查询
     * @param tableName     查询的表名（可选，为 null 时让 LLM 从所有表中自行选择）
     * @param tableSchema   表结构（字段名、类型、注释；或所有表的表结构）
     * @param businessTerms 业务术语解释（可选，方案B中可传 null）
     * @return 构建好的 Prompt
     */
    public String buildNl2SqlPrompt(String userQuery, String tableName, String tableSchema, String businessTerms) {
        return buildNl2SqlPrompt(userQuery, tableName, tableSchema, businessTerms, null);
    }

    /**
     * 构建 NL2SQL 的 Prompt（带方言参数）
     *
     * @param userQuery     用户自然语言查询
     * @param tableName     查询的表名（可选，为 null 时让 LLM 从所有表中自行选择）
     * @param tableSchema   表结构（字段名、类型、注释；或所有表的表结构）
     * @param businessTerms 业务术语解释（可选，方案B中可传 null）
     * @param dialect       数据源方言（如 "postgresql" / "mysql"），为 null 或未知时按 PostgreSQL 处理
     * @return 构建好的 Prompt
     */
    public String buildNl2SqlPrompt(String userQuery, String tableName, String tableSchema, String businessTerms, String dialect) {
        StringBuilder prompt = new StringBuilder();

        // 1. 角色设定
        prompt.append("你是一个专业的数据分析助手，擅长将自然语言转换为SQL查询。\n\n");

        // 2. 任务说明
        prompt.append("任务：根据用户的自然语言查询，生成对应的SQL查询语句。\n");
        prompt.append("要求：\n");
        prompt.append("- 只输出SQL语句，不要有任何其他解释或说明\n");
        appendDialectRules(prompt, dialect);
        prompt.append("- 【重要】只能使用上面提供的可用表结构中的表名和字段名，禁止编造不存在的表或字段\n");
        prompt.append("- 如果查询涉及聚合，合理使用GROUP BY和聚合函数\n");
        prompt.append("- 如果查询涉及排序，合理使用ORDER BY\n");
        prompt.append("- 如果查询结果可能很大，添加LIMIT 1000限制\n");
        prompt.append("- 不要使用任何DDL或DML语句（如CREATE、DROP、INSERT、UPDATE、DELETE等）\n\n");

        // 3. 表结构信息
        if (tableName != null && !tableName.trim().isEmpty()) {
            prompt.append("查询目标表：").append(tableName).append("\n");
        }
        prompt.append("可用表结构：\n").append(tableSchema).append("\n\n");

        // 4. 业务术语解释（可选）
        if (businessTerms != null && !businessTerms.trim().isEmpty()) {
            prompt.append("业务术语解释：\n").append(businessTerms).append("\n\n");
        }

        // 5. 示例（Few-shot）—— 方言相关示例由 appendDialectRules 注入，下面为与方言无关的通用示例
        prompt.append("SQL语法示例：\n");
        prompt.append("- 按某字段分组统计：SELECT field, COUNT(*) FROM 表名 GROUP BY field\n");
        prompt.append("- 排序取前N条：SELECT * FROM 表名 ORDER BY field DESC LIMIT 10\n\n");

        // 6. 用户输入
        prompt.append("用户输入：").append(userQuery).append("\n");
        prompt.append("SQL：");

        log.debug("构建NL2SQL Prompt，长度：{}", prompt.length());
        return prompt.toString();
    }

    /**
     * 根据数据源方言，向 Prompt 注入对应的语法约束与 few-shot 示例。
     *
     * <p>dialect 未提供或未知时，按 PostgreSQL 兜底（当前唯一业务库为 PostgreSQL）。
     *
     * @param prompt  Prompt 构造器
     * @param dialect 方言标识（小写，如 "postgresql" / "mysql"）
     */
    private void appendDialectRules(StringBuilder prompt, String dialect) {
        String d = (dialect == null) ? "postgresql" : dialect.trim().toLowerCase();
        switch (d) {
            case "mysql":
                prompt.append("- 使用 MySQL 8.0 语法\n");
                prompt.append("- 按月份统计：SELECT DATE_FORMAT(date_field, '%Y-%m') AS month, SUM(amount) FROM 表名 GROUP BY month\n");
                prompt.append("- 按最近N个月过滤：WHERE date_field >= DATE_SUB(DATE_FORMAT(NOW(), '%Y-%m-01'), INTERVAL 3 MONTH)\n");
                break;
            case "postgresql":
            default:
                prompt.append("- 使用 PostgreSQL 语法（当前数据源为 PostgreSQL）\n");
                prompt.append("- 按月份统计：SELECT TO_CHAR(date_field, 'YYYY-MM') AS month, SUM(amount) FROM 表名 GROUP BY month\n");
                prompt.append("- 按最近N个月过滤：WHERE date_field >= date_trunc('month', CURRENT_DATE) - INTERVAL '3 months'\n");
                break;
        }
    }

    /**
     * 构建数据解读的 Prompt
     *
     * @param userQuery   用户原始查询
     * @param sql         生成的SQL
     * @param queryResult 查询结果（JSON格式）
     * @return 构建好的Prompt
     */
    public String buildDataInterpretationPrompt(String userQuery, String sql, String queryResult) {
        StringBuilder prompt = new StringBuilder();

        // 1. 角色设定
        prompt.append("你是一个专业的数据分析助手，擅长解读数据查询结果。\n\n");

        // 2. 任务说明
        prompt.append("任务：根据用户查询、执行的SQL和查询结果，给出数据解读。\n");
        prompt.append("要求：\n");
        prompt.append("- 用简洁易懂的语言解释数据含义\n");
        prompt.append("- 指出数据中的关键发现、趋势或异常\n");
        prompt.append("- 如果有业务建议，可以简要提及\n");
        prompt.append("- 不要重复原始数据，要给出洞察\n\n");

        // 3. 上下文信息
        prompt.append("用户查询：").append(userQuery).append("\n\n");
        prompt.append("执行的SQL：\n").append(sql).append("\n\n");
        prompt.append("查询结果：\n").append(queryResult).append("\n\n");

        // 4. 输出要求
        prompt.append("请给出数据解读：");

        log.debug("构建数据解读Prompt，长度：{}", prompt.length());
        return prompt.toString();
    }

    /**
     * 构建RAG检索增强的Prompt
     *
     * @param userQuery   用户查询
     * @param tableName   查询的表名
     * @param tableSchema 表结构
     * @param ragContext  RAG检索到的相关上下文（业务知识、历史查询等）
     * @return 构建好的Prompt
     */
    public String buildRagEnhancedPrompt(String userQuery, String tableName, String tableSchema, String ragContext) {
        return buildRagEnhancedPrompt(userQuery, tableName, tableSchema, ragContext, null);
    }

    /**
     * 构建RAG检索增强的Prompt（带方言参数）
     *
     * @param userQuery   用户查询
     * @param tableName   查询的表名
     * @param tableSchema 表结构
     * @param ragContext  RAG检索到的相关上下文（业务知识、历史查询等）
     * @param dialect     数据源方言（如 "postgresql" / "mysql"），为 null 或未知时按 PostgreSQL 处理
     * @return 构建好的Prompt
     */
    public String buildRagEnhancedPrompt(String userQuery, String tableName, String tableSchema, String ragContext, String dialect) {
        StringBuilder prompt = new StringBuilder();

        // 1. 角色设定
        prompt.append("你是一个专业的数据分析助手，擅长将自然语言转换为SQL查询。\n\n");

        // 2. 任务说明
        prompt.append("任务：根据用户的自然语言查询，生成对应的SQL查询语句。\n");
        prompt.append("要求：\n");
        prompt.append("- 只输出SQL语句，不要有任何其他解释或说明\n");
        appendDialectRules(prompt, dialect);
        prompt.append("- 【重要】只能使用下面提供的表结构中的表名和字段名，禁止编造不存在的表或字段\n");
        prompt.append("- 如果查询涉及聚合，合理使用GROUP BY和聚合函数\n");
        prompt.append("- 如果查询涉及排序，合理使用ORDER BY\n");
        prompt.append("- 如果查询结果可能很大，添加LIMIT 1000限制\n");
        prompt.append("- 不要使用任何DDL或DML语句（如CREATE、DROP、INSERT、UPDATE、DELETE等）\n\n");

        // 3. 表结构信息
        prompt.append("表名：").append(tableName).append("\n");
        prompt.append("表结构：\n").append(tableSchema).append("\n\n");

        // 4. RAG上下文（业务知识、历史查询等）
        if (ragContext != null && !ragContext.trim().isEmpty()) {
            prompt.append("相关背景知识：\n").append(ragContext).append("\n\n");
        }

        // 5. 用户输入
        prompt.append("用户输入：").append(userQuery).append("\n");
        prompt.append("SQL：");

        log.debug("构建RAG增强Prompt，长度：{}", prompt.length());
        return prompt.toString();
    }

    /**
     * 构建异常检测的Prompt
     *
     * @param data      待检测的数据（JSON格式）
     * @param threshold 异常阈值（可选）
     * @return 构建好的Prompt
     */
    public String buildAnomalyDetectionPrompt(String data, Double threshold) {
        StringBuilder prompt = new StringBuilder();

        // 1. 角色设定
        prompt.append("你是一个专业的数据异常检测助手。\n\n");

        // 2. 任务说明
        prompt.append("任务：检测数据中的异常值或异常趋势。\n");
        prompt.append("要求：\n");
        prompt.append("- 识别明显的异常值（如突然的峰值、谷值、缺失值等）\n");
        prompt.append("- 如果提供了阈值，严格按照阈值判断\n");
        prompt.append("- 输出格式：JSON数组，每个异常包含：字段名、异常值、异常类型、建议\n");
        prompt.append("- 如果没有异常，返回空数组[]\n\n");

        // 3. 数据
        prompt.append("数据：\n").append(data).append("\n\n");

        // 4. 阈值（可选）
        if (threshold != null) {
            prompt.append("异常阈值：").append(threshold).append("\n\n");
        }

        // 5. 输出格式要求
        prompt.append("输出格式示例：\n");
        prompt.append("[\n");
        prompt.append("  {\n");
        prompt.append("    \"field\": \"sales\",\n");
        prompt.append("    \"value\": 99999,\n");
        prompt.append("    \"type\": \"峰值异常\",\n");
        prompt.append("    \"suggestion\": \"建议检查该笔销售记录是否录入错误\"\n");
        prompt.append("  }\n");
        prompt.append("]\n\n");

        prompt.append("请检测异常：");

        log.debug("构建异常检测Prompt，长度：{}", prompt.length());
        return prompt.toString();
    }
}
