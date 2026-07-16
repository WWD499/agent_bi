package com.bi.agent.bi.service.llm;

import com.bi.agent.bi.vo.DataProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Prompt 模板构建器
 *
 * 构建用于 NL2SQL、数据解读等场景的 Prompt 模板
 * 支持：表结构注入、示例注入、业务术语注入、数据探查结果（DataProfile）注入
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
        return buildNl2SqlPrompt(userQuery, tableName, tableSchema, businessTerms, dialect, null);
    }

    /**
     * 构建 NL2SQL 的 Prompt（带方言 + 真实数据探查结果）。
     *
     * <p>构建流程<b>复制</b> 5 参版（角色设定 / 任务说明 / 表结构 / 业务术语 / 示例 / 用户输入），
     * 仅在「可用表结构」之后、「示例」之前额外注入目标表的<b>真实数据覆盖</b>段，
     * 引导 LLM 把「上季度 / 最近 N 个月」等时间表述映射到真实覆盖区间，
     * 避免用 CURRENT_DATE 推算的当前窗口与真实数据不重叠导致 0 行。
     *
     * @param userQuery     用户自然语言查询
     * @param tableName     查询的表名（可选）
     * @param tableSchema   表结构
     * @param businessTerms 业务术语解释（可选）
     * @param dialect       数据源方言（如 "postgresql" / "mysql"）
     * @param profile       数据探查结果（为 null 时退化为 5 参版行为）
     * @return 构建好的 Prompt
     */
    public String buildNl2SqlPrompt(String userQuery, String tableName, String tableSchema,
                                    String businessTerms, String dialect, DataProfile profile) {
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

        // 3.5 真实数据覆盖提示（数据探查前置，根治时间窗口 mismatch → 0 行）
        if (profile != null) {
            appendDataProfileSection(prompt, profile);
        }

        // 4. 业务术语解释（可选）
        if (businessTerms != null && !businessTerms.trim().isEmpty()) {
            prompt.append("业务术语解释：\n").append(businessTerms).append("\n\n");
        }

        // 5. 示例（Few-shot）
        prompt.append("SQL语法示例：\n");
        prompt.append("- 按某字段分组统计：SELECT field, COUNT(*) FROM 表名 GROUP BY field\n");
        prompt.append("- 排序取前N条：SELECT * FROM 表名 ORDER BY field DESC LIMIT 10\n\n");

        // 6. 用户输入
        prompt.append("用户输入：").append(userQuery).append("\n");
        prompt.append("SQL：");

        log.debug("构建NL2SQL Prompt（含数据探查={}），长度：{}", profile != null, prompt.length());
        return prompt.toString();
    }

    /**
     * 向 Prompt 注入「真实数据覆盖」段。
     *
     * <p>明确告知 LLM：下方时间/枚举范围来自<b>真实探查</b>（非假设），
     * 涉及时间范围时应使用真实覆盖区间，而非 CURRENT_DATE 推算的当前季度/月份。
     *
     * @param prompt  Prompt 构造器
     * @param profile 数据探查结果
     */
    private void appendDataProfileSection(StringBuilder prompt, DataProfile profile) {
        prompt.append("【真实数据覆盖提示】以下为目标表真实探查结果（非假设）：\n");

        // 时间列：真实覆盖区间 + 最新季度
        if (profile.getTimeColumns() != null && !profile.getTimeColumns().isEmpty()) {
            for (Map.Entry<String, DataProfile.TimeRange> e : profile.getTimeColumns().entrySet()) {
                DataProfile.TimeRange tr = e.getValue();
                prompt.append("- 表 ").append(profile.getTableName()).append(".").append(tr.getColumn())
                        .append(" 实际覆盖 MIN=").append(tr.getMin())
                        .append(" ~ MAX=").append(tr.getMax())
                        .append("（最新季度 ").append(tr.getLatestQuarter()).append("）。")
                        .append("当问题涉及时间范围（如上季度 / 最近N个月 / 今年）时，")
                        .append("请使用上述真实覆盖区间内的数据，")
                        .append("不要使用 CURRENT_DATE 推算的当前季度（真实数据可能早于当前日期）。\n");
            }
        }

        // 枚举列：真实取值 + 计数
        if (profile.getEnumColumns() != null && !profile.getEnumColumns().isEmpty()) {
            for (Map.Entry<String, List<DataProfile.EnumValue>> e : profile.getEnumColumns().entrySet()) {
                prompt.append("- 表 ").append(profile.getTableName()).append(".").append(e.getKey())
                        .append(" 实际取值(计数)：");
                List<String> parts = new java.util.ArrayList<>();
                for (DataProfile.EnumValue v : e.getValue()) {
                    parts.add(v.getValue() + "(" + v.getCount() + ")");
                }
                prompt.append(String.join("、", parts)).append("。")
                        .append("请用这些真实取值作 WHERE 过滤，不要凭空硬写其它值。\n");
            }
        }

        // 行数
        prompt.append("- 表 ").append(profile.getTableName())
                .append(" 总行数≈").append(profile.getRowCount()).append("。\n");

        // 收尾提醒：方言示例中的时间写法仅为语法示范
        prompt.append("上方方言语法示例中的时间范围（如 date_trunc('quarter', CURRENT_DATE)）")
                .append("仅为写法示范，真实数据区间以下方探查结果为准。\n\n");
    }

    /**
     * 根据数据源方言，向 Prompt 注入对应的语法约束与 few-shot 示例（兼容旧调用）。
     *
     * <p>等价于 {@link #appendDialectRules(StringBuilder, String, DataProfile)} 传入 {@code profile=null}，
     * 即保留原静态模板硬提示（兼容 {@code buildRagEnhancedPrompt} 等调用方）。
     *
     * @param prompt  Prompt 构造器
     * @param dialect 方言标识（小写，如 "postgresql" / "mysql"）
     */
    private void appendDialectRules(StringBuilder prompt, String dialect) {
        appendDialectRules(prompt, dialect, null);
    }

    /**
     * 根据数据源方言，向 Prompt 注入语法约束与 few-shot 示例。
     *
     * <p><b>软兜底策略（根治根因的关键）：</b>
     * 当 {@code profile != null}（已探查到真实数据覆盖）时，<b>不再硬锁 CURRENT_DATE 推算的
     * 当前日期区间</b>，而是提示 LLM「时间范围以上文『真实数据覆盖』给出的最新可用季度/月份为准，
     * 不要使用 CURRENT_DATE 推算」。仅保留方言函数示例（如 PG 的 TO_CHAR）。
     * 当 {@code profile == null}（降级）时，退化为原静态模板（含 CURRENT_DATE 示例），保持向后兼容。
     *
     * <p>注：NL2SQL 主流程的 6 参 buildNl2SqlPrompt 走 2 参版本（profile=null）以完整保留方言示例，
     * “禁用 CURRENT_DATE”的诉求已由 {@link #appendDataProfileSection(StringBuilder, DataProfile)} 承担。
     *
     * @param prompt  Prompt 构造器
     * @param dialect 方言标识（小写，如 "postgresql" / "mysql"）
     * @param profile 主表探查结果（可为 null）
     */
    private void appendDialectRules(StringBuilder prompt, String dialect, DataProfile profile) {
        String d = (dialect == null) ? "postgresql" : dialect.trim().toLowerCase();
        switch (d) {
            case "mysql":
                prompt.append("- 使用 MySQL 8.0 语法\n");
                prompt.append("- 按月份统计：SELECT DATE_FORMAT(date_field, '%Y-%m') AS month, SUM(amount) FROM 表名 GROUP BY month\n");
                if (profile != null) {
                    // 软兜底：时间范围以真实覆盖为准，禁用 CURRENT_DATE 推算
                    prompt.append("- 时间过滤请以「真实数据覆盖」段给出的最新可用季度/月份为准，"
                            + "不要使用 NOW()/CURRENT_DATE 推算当前日期（数据可能滞后于真实当前日期）\n");
                } else {
                    prompt.append("- 按最近N个月过滤：WHERE date_field >= DATE_SUB(DATE_FORMAT(NOW(), '%Y-%m-01'), INTERVAL 3 MONTH)\n");
                }
                break;
            case "postgresql":
            default:
                prompt.append("- 使用 PostgreSQL 语法（当前数据源为 PostgreSQL）\n");
                prompt.append("- 按月份统计：SELECT TO_CHAR(date_field, 'YYYY-MM') AS month, SUM(amount) FROM 表名 GROUP BY month\n");
                if (profile != null) {
                    // 软兜底：时间范围以真实覆盖为准，禁用 CURRENT_DATE 推算
                    prompt.append("- 时间过滤请以「真实数据覆盖」段给出的最新可用季度/月份为准，"
                            + "不要使用 date_trunc('quarter', CURRENT_DATE) 或 CURRENT_DATE 推算当前日期"
                            + "（数据可能滞后于真实当前日期，否则会与种子数据不重叠导致 0 行）\n");
                } else {
                    prompt.append("- 按最近N个月过滤：WHERE date_field >= date_trunc('month', CURRENT_DATE) - INTERVAL '3 months'\n");
                }
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
