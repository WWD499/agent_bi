package com.bi.agent.bi.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.domain.BiSandboxDb;
import com.bi.agent.bi.domain.BiSandboxTable;
import com.bi.agent.bi.mapper.BiSandboxMapper;
import com.bi.agent.bi.service.llm.LlmService;
import com.bi.agent.bi.service.llm.PromptBuilder;
import com.bi.agent.bi.service.sql.ChartSelector;
import com.bi.agent.bi.service.sql.SqlValidator;
import com.bi.agent.bi.vo.DbColumnVo;
import com.bi.agent.bi.vo.DbTableVo;
import com.bi.agent.bi.vo.QueryResultVo;
import com.bi.agent.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据沙箱查询服务（M1：只读）。
 *
 * <p>沙箱在 agent_bi 库内的独立 {@code sandbox} schema，复用系统库主 {@link JdbcTemplate} 连接，
 * SQL 一律以 {@code sandbox.表名} 全限定形式访问，零新增数据源配置。
 *
 * <p>逻辑命名空间（沙箱库）：用户可在沙箱内新建多个「沙箱库」对表分组。物理表名 =
 * {@code db_key + "__" + table_name}（如 {@code sandbox."marts__sales"}），全部仍落在 sandbox schema，
 * 因此既保留了「统一 schema 前缀」的安全边界，又实现了「按库选择分析」的体验。
 *
 * <p>安全边界：
 * <ol>
 *   <li>{@link SqlValidator} 前 4 层（操作白名单 / 黑名单 / 注入 / 多语句）—— 仅允许 SELECT/WITH；</li>
 *   <li>{@link #assertAllTablesInSandbox(String)} 自定义边界 —— 所有 FROM/JOIN 表名必须以
 *       {@code sandbox.} 前缀，杜绝经沙箱工具越权访问 public 业务表或 bi_* 系统表（M2 再开放写工具）。</li>
 * </ol>
 */
@Service
public class SandboxQueryService {

    private static final Logger log = LoggerFactory.getLogger(SandboxQueryService.class);

    /** 沙箱 schema 名 */
    public static final String SANDBOX_SCHEMA = "sandbox";

    /** 物理名前缀分隔符：db_key 与 table_name 之间 */
    public static final String PHYSICAL_SEP = "__";

    /** 表名标识符合法性（防止建表/查询注入元数据库）：英文/数字/下划线，首字符非数字 */
    private static final Pattern IDENT_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /** 从 SQL 提取 FROM/JOIN 后的表名（支持 schema.表 与双引号形式），用于沙箱边界校验 */
    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile(
            "(?i)\\b(?:FROM|JOIN)\\s+([\"`]?)([A-Za-z_][A-Za-z0-9_]*)\\1(?:\\.([\"`]?)([A-Za-z_][A-Za-z0-9_]*)\\3)?");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SqlValidator sqlValidator;

    @Autowired
    private ChartSelector chartSelector;

    @Autowired
    private LlmService llmService;

    @Autowired
    private PromptBuilder promptBuilder;

    @Autowired
    private IBiKnowledgeService knowledgeService;

    @Autowired
    private BiSandboxMapper sandboxMapper;

    /**
     * 列出沙箱内所有用户表（默认全部库）。
     */
    public List<DbTableVo> listSandboxTables() {
        return listSandboxTables(null);
    }

    /**
     * 列出沙箱表（按库过滤）。
     *
     * @param dbId 沙箱库 id；为 null 表示列出全部库下的表
     * @return 每张表一个 DbTableVo：physicalName 为物理名（SQL/API 用），displayName 为友好短名
     */
    public List<DbTableVo> listSandboxTables(Long dbId) {
        // 库 id → db_key 映射，便于回填
        Map<Long, String> dbKeyMap = new HashMap<>();
        for (BiSandboxDb db : sandboxMapper.selectAllDb()) {
            dbKeyMap.put(db.getId(), db.getDbKey());
        }

        List<BiSandboxTable> rows = (dbId == null)
                ? sandboxMapper.selectAll()
                : sandboxMapper.selectByDbId(dbId);

        List<DbTableVo> result = new ArrayList<>();
        for (BiSandboxTable rec : rows) {
            DbTableVo vo = new DbTableVo();
            vo.setTableName(rec.getPhysicalName());
            vo.setPhysicalName(rec.getPhysicalName());
            vo.setShortName(rec.getTableName());
            // 显示名取用户设定的 display_name（可中文，如 员工表）；为空则回退到短名
            vo.setDisplayName((rec.getDisplayName() == null || rec.getDisplayName().isBlank())
                    ? rec.getTableName() : rec.getDisplayName());
            vo.setDbId(rec.getDbId());
            vo.setDbKey(dbKeyMap.getOrDefault(rec.getDbId(), "default"));
            result.add(vo);
        }
        return result;
    }

    /**
     * 列出沙箱某物理表的字段结构。物理名需为合法标识（防注入）。
     */
    public List<DbColumnVo> listSandboxColumns(String physicalName) {
        if (physicalName == null || !IDENT_PATTERN.matcher(physicalName).matches()) {
            log.warn("非法沙箱物理表名，拒绝获取字段：{}", physicalName);
            return Collections.emptyList();
        }
        return jdbcTemplate.query(
                "SELECT column_name, data_type FROM information_schema.columns "
                        + "WHERE table_schema = '" + SANDBOX_SCHEMA + "' AND table_name = ? ORDER BY ordinal_position",
                (rs, i) -> {
                    DbColumnVo vo = new DbColumnVo();
                    vo.setColumnName(rs.getString("column_name"));
                    vo.setDataType(rs.getString("data_type"));
                    return vo;
                },
                physicalName);
    }

    /**
     * 在沙箱内执行【只读】SQL（M1：仅 SELECT/WITH，且表必须全部位于 sandbox schema）。
     *
     * @param sql 只读 SQL，表名须以 {@code sandbox.} 全限定
     * @return 查询结果（最多 100 行，保护上下文与内存）
     */
    public QueryResultVo runSandboxReadOnlySql(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new BizException("SQL 不能为空");
        }
        sql = extractSql(sql);
        // 前 4 层只读校验（不传表名白名单，表名边界由 assertAllTablesInSandbox 保证）
        sqlValidator.validate(sql);
        assertAllTablesInSandbox(sql);

        String execSql = applySafeLimit(sql);
        List<Map<String, Object>> raw = jdbcTemplate.queryForList(execSql);

        List<String> columns = new ArrayList<>();
        if (!raw.isEmpty()) {
            columns.addAll(raw.get(0).keySet());
        }
        List<JSONObject> rows = new ArrayList<>();
        for (Map<String, Object> m : raw) {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Object> e : m.entrySet()) {
                o.put(e.getKey(), e.getValue());
            }
            rows.add(o);
        }
        if (rows.size() > 100) {
            rows = new ArrayList<>(rows.subList(0, 100));
        }

        QueryResultVo vo = new QueryResultVo();
        vo.setSql(sql);
        vo.setColumns(columns);
        vo.setData(rows);
        vo.setRowCount(rows.size());
        log.info("沙箱只读 SQL 执行完成：rowCount={}", rows.size());
        return vo;
    }

    /**
     * 沙箱自然语言查询（M1：只读 NL2SQL）。
     *
     * <p>复用 {@link PromptBuilder} 与 {@link LlmService} 生成 SQL，强制表名以
     * {@code sandbox."物理名"} 全限定，经只读 + sandbox 边界校验后执行，并附智能选图与数据解读。
     *
     * @param userQuery 用户自然语言
     * @param dbId      作用域沙箱库 id；为 null 表示全部沙箱库（全表参与 NL2SQL）
     * @return 查询结果 VO（含 sql / columns / data / chartType / echartsOption / interpretation）
     */
    public QueryResultVo naturalLanguageQuerySandbox(String userQuery, Long dbId) {
        if (userQuery == null || userQuery.trim().isEmpty()) {
            throw new BizException("query 不能为空");
        }
        // 1. 取作用域内的沙箱表结构（按物理名）
        List<DbTableVo> tables = listSandboxTables(dbId);
        if (tables.isEmpty()) {
            throw new BizException("沙箱暂无数据表，请先在「数据沙箱」页粘贴导入数据");
        }
        StringBuilder schemaText = new StringBuilder();
        for (DbTableVo t : tables) {
            String physical = t.getPhysicalName();
            schemaText.append("## 表名：").append(SANDBOX_SCHEMA).append(".\"").append(physical).append("\"\n字段：\n");
            for (DbColumnVo c : listSandboxColumns(physical)) {
                schemaText.append("  - ").append(c.getColumnName()).append(' ').append(c.getDataType()).append('\n');
            }
            schemaText.append('\n');
        }

        // 2. 构建 Prompt（注入 RAG 上下文 + 沙箱专属全限定约束）
        String ragContext = knowledgeService.buildRagContext(userQuery, 0L);
        String prompt = promptBuilder.buildNl2SqlPrompt(
                userQuery, null, schemaText.toString(), ragContext, "postgresql", null);
        prompt += "\n\n【沙箱专属约束】本查询针对数据沙箱（schema=" + SANDBOX_SCHEMA + "）。"
                + "你生成的 SQL 中所有表名必须使用 `" + SANDBOX_SCHEMA + ".\"物理表名\"` 全限定形式"
                + "（例如 " + SANDBOX_SCHEMA + ".\"default__sales_2025\"），禁止省略 schema 前缀，"
                + "禁止引用 public 或其他 schema 的表。\n";

        // 3. 生成 SQL
        String rawSql = llmService.chat(prompt, 0.1);
        String sql = extractSql(rawSql);
        log.info("沙箱 NL2SQL 生成：{}", sql);

        // 4. 只读 + 边界校验 + 执行（复用 runSandboxReadOnlySql，已含校验与 LIMIT 保护）
        QueryResultVo result = runSandboxReadOnlySql(sql);

        // 5. 智能选图 + 数据解读
        ChartSelector.ChartType chartType = chartSelector.selectChart(result.getColumns(), result.getData(), userQuery);
        JSONObject echartsOption = chartSelector.generateEChartsOption(chartType, result.getColumns(), result.getData());
        result.setChartType(chartType.getType());
        result.setChartName(chartType.getName());
        result.setEchartsOption(echartsOption);
        if (result.getRowCount() > 0) {
            try {
                String dataJson = JSON.toJSONString(result.getData());
                String interpPrompt = promptBuilder.buildDataInterpretationPrompt(userQuery, sql, dataJson);
                result.setInterpretation(llmService.chat(interpPrompt, 0.3));
            } catch (Exception e) {
                log.warn("沙箱数据解读失败，不影响主流程", e);
            }
        }
        return result;
    }

    /**
     * 列出全部沙箱库（逻辑命名空间）。
     */
    public List<BiSandboxDb> listSandboxDbs() {
        return sandboxMapper.selectAllDb();
    }

    /**
     * 新建沙箱库。dbKey 必须是合法标识符且全局唯一（作为物理名前缀键）。
     */
    public BiSandboxDb createSandboxDb(String name, String dbKey, String remark) {
        if (name == null || name.trim().isEmpty()) {
            throw new BizException("库名称不能为空");
        }
        if (dbKey == null || !IDENT_PATTERN.matcher(dbKey).matches()) {
            throw new BizException("库标识(dbKey)必须为合法标识符（英文/数字/下划线，首字符非数字）");
        }
        if (sandboxMapper.selectDbByKey(dbKey) != null) {
            throw new BizException("库标识已存在：" + dbKey);
        }
        BiSandboxDb db = new BiSandboxDb();
        db.setName(name.trim());
        db.setDbKey(dbKey);
        db.setRemark(remark);
        sandboxMapper.insertDb(db);
        return db;
    }

    /**
     * 修改沙箱表的用户显示名（如把 emp 改名为「员工表」）。仅改元数据 bi_sandbox_table.display_name，
     * 不影响物理表名与 SQL 作用域；物理名非法时拒绝（防注入）。
     *
     * @param physicalName 物理表名（如 default__emp）
     * @param displayName  新的显示名（可中文，留空则回退到短名）
     */
    public void renameSandboxTableDisplay(String physicalName, String displayName) {
        if (physicalName == null || !IDENT_PATTERN.matcher(physicalName).matches()) {
            throw new BizException("非法物理表名：" + physicalName);
        }
        BiSandboxTable rec = sandboxMapper.selectByPhysicalName(physicalName);
        if (rec == null) {
            throw new BizException("沙箱表不存在：" + physicalName);
        }
        String name = (displayName == null) ? "" : displayName.trim();
        sandboxMapper.updateDisplayNameByPhysical(physicalName, name);
        log.info("沙箱表显示名已更新：{} -> {}", physicalName, name);
    }

    /**
     * 沙箱边界校验：SQL 中所有 FROM/JOIN 表名必须以 {@code sandbox.} 前缀（支持双引号物理名）。
     */
    private void assertAllTablesInSandbox(String sql) {
        Matcher m = TABLE_NAME_PATTERN.matcher(sql);
        while (m.find()) {
            String schema = m.group(2);
            String tbl = m.group(4);
            if (tbl != null) {
                // schema.表 形式：schema 必须是 sandbox
                if (!SANDBOX_SCHEMA.equalsIgnoreCase(schema)) {
                    throw new BizException("数据沙箱仅允许访问 " + SANDBOX_SCHEMA
                            + " schema 下的表，非法表引用：" + schema + "." + tbl);
                }
            } else {
                // 未限定 schema：沙箱不允许裸表名
                throw new BizException("数据沙箱仅允许访问 " + SANDBOX_SCHEMA
                        + " schema 下的表，请使用 " + SANDBOX_SCHEMA + "." + schema + " 全限定形式");
            }
        }
    }

    /** 去掉 SQL 代码块标记与末尾分号（供 LLM 返回做清洗） */
    private String extractSql(String raw) {
        if (raw == null) {
            return null;
        }
        String sql = raw.trim();
        if (sql.startsWith("```")) {
            int start = sql.indexOf('\n');
            int end = sql.lastIndexOf("```");
            if (start > 0 && end > start) {
                sql = sql.substring(start, end).trim();
            } else {
                sql = sql.replaceAll("```", "").trim();
            }
        }
        sql = sql.replaceAll(";\\s*$", "");
        return sql;
    }

    /** 无 LIMIT 的只读查询自动追加 LIMIT 100，保护内存与上下文 */
    private String applySafeLimit(String sql) {
        if (!sql.toLowerCase().matches("(?s).*\\blimit\\b.*")) {
            return sql + " LIMIT 100";
        }
        return sql;
    }
}
