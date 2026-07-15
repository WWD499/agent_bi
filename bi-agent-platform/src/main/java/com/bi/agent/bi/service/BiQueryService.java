package com.bi.agent.bi.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.domain.BiDatasource;
import com.bi.agent.bi.service.llm.LlmService;
import com.bi.agent.bi.service.llm.PromptBuilder;
import com.bi.agent.bi.service.sql.ChartSelector;
import com.bi.agent.bi.service.sql.SqlValidator;
import com.bi.agent.bi.util.BiDataSourceFactory;
import com.bi.agent.bi.util.JdbcUrlBuilder;
import com.bi.agent.bi.vo.DbTableVo;
import com.bi.agent.bi.vo.QueryResultVo;
import com.bi.agent.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

/**
 * BI自然语言查询核心编排服务
 *
 * 流程：用户输入 → 选数据源 → 获取所有表结构 → 构建Prompt → 调用LLM生成SQL
 *        → SQL校验 → 执行SQL → 智能选图 → 返回结果
 *
 * Phase 1：RAG 业务知识库以 stub 实现（buildRagContext 返回空串），
 * 完整向量检索见 Task #91（PG pgvector + BAAI/bge-m3）。
 *
 * @author ruoyi-bi (ported to agent-bi)
 */
@Service
public class BiQueryService {

    private static final Logger log = LoggerFactory.getLogger(BiQueryService.class);

    @Autowired
    private LlmService llmService;

    @Autowired
    private PromptBuilder promptBuilder;

    @Autowired
    private SqlValidator sqlValidator;

    @Autowired
    private ChartSelector chartSelector;

    @Autowired
    private IBiDatasourceService datasourceService;

    @Autowired
    private IBiKnowledgeService knowledgeService;

    @Autowired
    private BiDataSourceFactory dataSourceFactory;

    /**
     * 自然语言查询完整流程
     *
     * @param userQuery    用户自然语言输入
     * @param datasourceId 数据源ID
     * @param tableName    目标表名（可选，不传则让LLM从所有表中自行判断）
     * @return 查询结果（含SQL、数据、图表配置）
     */
    public QueryResultVo naturalLanguageQuery(String userQuery, Long datasourceId, String tableName) {
        log.info("开始自然语言查询：userQuery={}, datasourceId={}, tableName={}", userQuery, datasourceId, tableName);

        // 1. 获取数据源配置
        BiDatasource datasource = datasourceService.selectBiDatasourceById(datasourceId);
        if (datasource == null) {
            throw new BizException("数据源不存在，id=" + datasourceId);
        }

        // 2. 获取所有表结构（注入Prompt用）+ 收集可用表名
        Set<String> availableTables = new HashSet<>();
        String allTableSchemas = getAllTableSchemas(datasource, availableTables);
        log.info("可用表名：{}", availableTables);

        // 3. 构建NL2SQL Prompt（注入RAG业务知识库上下文；Phase1 stub 返回空串）
        String ragContext = knowledgeService.buildRagContext(userQuery, datasourceId);
        String prompt = promptBuilder.buildNl2SqlPrompt(userQuery, tableName, allTableSchemas, ragContext, datasource.getType());

        // 4. 调用LLM生成SQL
        String rawSql = llmService.chat(prompt, 0.1);
        String sql = extractSql(rawSql);
        log.info("LLM生成SQL：{}", sql);

        // 5. SQL安全校验（含表名白名单校验，防止LLM编造不存在的表）
        sqlValidator.validate(sql, availableTables);

        // 6. 执行SQL
        List<String> columns = new ArrayList<>();
        List<JSONObject> rows = new ArrayList<>();
        executeQuery(datasource, sql, columns, rows);

        // 7. 智能选图 + 生成ECharts配置（传入用户查询意图，提升选图准确率）
        ChartSelector.ChartType chartType = chartSelector.selectChart(columns, rows, userQuery);
        JSONObject echartsOption = chartSelector.generateEChartsOption(chartType, columns, rows);

        // 8. 数据解读（可选）
        String interpretation = null;
        if (rows.size() > 0) {
            try {
                String dataJson = JSON.toJSONString(rows);
                String interpPrompt = promptBuilder.buildDataInterpretationPrompt(userQuery, sql, dataJson);
                interpretation = llmService.chat(interpPrompt, 0.3);
            } catch (Exception e) {
                log.warn("数据解读失败，不影响主流程", e);
            }
        }

        // 9. 组装返回结果
        QueryResultVo result = new QueryResultVo();
        result.setSql(sql);
        result.setColumns(columns);
        result.setData(rows);
        result.setChartType(chartType.getType());
        result.setChartName(chartType.getName());
        result.setEchartsOption(echartsOption);
        result.setInterpretation(interpretation);
        result.setRowCount(rows.size());

        log.info("自然语言查询完成：rowCount={}, chartType={}", rows.size(), chartType.getType());
        return result;
    }

    /**
     * 执行【只读】SQL 取数（供 Agent 的 run_sql / select_chart 工具调用）
     *
     * <p>与 {@link #naturalLanguageQuery} 不同：本方法<b>不调 LLM、不智能选图</b>，
     * 仅做安全校验后直连业务库取数，返回字段与数据行。
     * 校验采用 {@link SqlValidator} 五层防护 + 表名白名单（从数据源当前库全表拉取，
     * 防止 Agent 拼出不存在/越权的表），安全边界与 NL2SQL 一致。
     *
     * @param datasourceId 数据源ID
     * @param sql          只读 SQL（仅允许 SELECT/WITH）
     * @return 查询结果 VO（sql / columns / data / rowCount，最多 100 行，保护 Agent 上下文）
     */
    public QueryResultVo runReadOnlySql(Long datasourceId, String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new BizException("SQL 不能为空");
        }
        BiDatasource datasource = datasourceService.selectBiDatasourceById(datasourceId);
        if (datasource == null) {
            throw new BizException("数据源不存在，id=" + datasourceId);
        }
        // 表名白名单：从数据源当前库拉取全表，防止 Agent 拼出不存在的表
        Set<String> allowedTables = new HashSet<>();
        for (DbTableVo t : datasourceService.listTables(datasourceId)) {
            allowedTables.add(t.getTableName());
        }
        // 五层防护：操作白名单 + 黑名单 + 注入特征 + 多语句 + 表名白名单
        sqlValidator.validate(sql, allowedTables);

        List<String> columns = new ArrayList<>();
        List<JSONObject> rows = new ArrayList<>();
        executeQuery(datasource, sql, columns, rows);

        // 仅保留前 100 行，避免撑爆 Agent 上下文
        if (rows.size() > 100) {
            rows = new ArrayList<>(rows.subList(0, 100));
        }

        QueryResultVo result = new QueryResultVo();
        result.setSql(sql);
        result.setColumns(columns);
        result.setData(rows);
        result.setRowCount(rows.size());
        log.info("只读 SQL 执行完成：rowCount={}", rows.size());
        return result;
    }

    /**
     * 从LLM返回中提取纯SQL（去掉可能的代码块标记）
     */
    private String extractSql(String raw) {
        if (raw == null) return null;
        String sql = raw.trim();

        // 去掉 ```sql ... ``` 代码块
        if (sql.startsWith("```")) {
            int start = sql.indexOf('\n');
            int end = sql.lastIndexOf("```");
            if (start > 0 && end > start) {
                sql = sql.substring(start, end).trim();
            } else {
                sql = sql.replaceAll("```", "").trim();
            }
        }

        // 去掉末尾的分号（executeQuery不需要）
        sql = sql.replaceAll(";\\s*$", "");
        return sql;
    }

    /**
     * 获取数据源中所有表的表结构
     * 返回格式：
     * ## 表名：table_name
     * 字段：column_name data_type -- comment
     */
    private String getAllTableSchemas(BiDatasource datasource, Set<String> tableNames) {
        StringBuilder schemas = new StringBuilder();
        // PG 的 catalog 传 null（使用当前连接库），schemaPattern 必须显式传 "public"
        String catalog = JdbcUrlBuilder.catalog(datasource);
        String schemaPattern = JdbcUrlBuilder.schemaPattern(datasource);
        try (Connection conn = dataSourceFactory.getDataSource(datasource).getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();

            // 获取当前数据库的所有表（限定catalog，避免泄漏其他库的表）
            try (ResultSet tables = metaData.getTables(catalog, schemaPattern, "%", new String[]{"TABLE"})) {
                int tableCount = 0;
                while (tables.next()) {
                    String tName = tables.getString("TABLE_NAME");
                    String remarks = tables.getString("REMARKS");
                    tableNames.add(tName);
                    schemas.append("## 表名：").append(tName);
                    if (remarks != null && !remarks.trim().isEmpty()) {
                        schemas.append("（").append(remarks).append("）");
                    }
                    schemas.append("\n");

                    // 获取该表的所有字段（同样限定catalog + schemaPattern）
                    schemas.append("字段：\n");
                    try (ResultSet cols = metaData.getColumns(catalog, schemaPattern, tName, null)) {
                        while (cols.next()) {
                            String colName = cols.getString("COLUMN_NAME");
                            String dataType = cols.getString("TYPE_NAME");
                            String colRemarks = cols.getString("REMARKS");
                            schemas.append("  - ").append(colName).append(" ").append(dataType);
                            if (colRemarks != null && !colRemarks.trim().isEmpty()) {
                                schemas.append(" -- ").append(colRemarks);
                            }
                            schemas.append("\n");
                        }
                    }

                    schemas.append("\n");
                    tableCount++;

                    // 最多取20张表，避免Prompt过长
                    if (tableCount >= 20) {
                        schemas.append("（还有更多表未显示...）\n");
                        break;
                    }
                }
            }

        } catch (SQLException e) {
            log.error("获取表结构失败", e);
            throw new BizException("获取表结构失败：" + e.getMessage());
        }

        return schemas.toString();
    }

    /**
     * 执行SQL查询，结果写入 columns 和 rows
     */
    private void executeQuery(BiDatasource datasource, String sql, List<String> columns, List<JSONObject> rows) {
        try (Connection conn = dataSourceFactory.getDataSource(datasource).getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData metaData = rs.getMetaData();
            int colCount = metaData.getColumnCount();
            for (int i = 1; i <= colCount; i++) {
                columns.add(metaData.getColumnLabel(i));
            }

            while (rs.next()) {
                JSONObject row = new JSONObject();
                for (int i = 1; i <= colCount; i++) {
                    String colName = metaData.getColumnLabel(i);
                    Object value = rs.getObject(i);
                    row.put(colName, value);
                }
                rows.add(row);
            }

        } catch (SQLException e) {
            log.error("SQL执行失败：sql={}", sql, e);
            throw new BizException("SQL执行失败：" + e.getMessage());
        }
    }
}
