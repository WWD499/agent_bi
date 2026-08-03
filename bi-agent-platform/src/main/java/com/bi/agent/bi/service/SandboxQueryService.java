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
import com.bi.agent.bi.service.SandboxAuditService;
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
 * <p>逻辑命名空间（沙箱库）：用户可在沙箱内新建多个「沙箱库」对表分组，元数据用 {@code db_id} 关联，
 * 但<b>物理表名不再拼接库前缀</b>——直接等于用户给定的短名（如 {@code sandbox."sales"}），全沙箱短名全局唯一。
 * 这样模型只需记住 list_tables 返回的短名即可，无需自己拼接 {@code dbkey__表名}（双下划线易被模型吞掉，
 * 导致建表后找不到自己创建的表）。安全边界始终由统一的 sandbox schema 前缀保证。
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

    /** 沙箱 schema 名（所有沙箱物理表统一落在 sandbox schema，作为安全边界） */
    public static final String SANDBOX_SCHEMA = "sandbox";

    /** 表名标识符合法性（防止建表/查询注入元数据库）：英文/数字/下划线，首字符非数字 */
    private static final Pattern IDENT_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /** 从 SQL 提取 FROM/JOIN 后的表名（支持 schema."表" / schema.表 / "表" 形式），用于沙箱边界校验。
     *  schema 不允许含点号；表名部分允许中文等非 ASCII 字符，且可位于可选的双引号/反引号对内。 */
    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile(
            "(?i)\\b(?:FROM|JOIN)\\s+([\"`]?)([^\"`\\.\\s]+)\\1(?:\\.([\"`]?)([^\"`\\s]+)\\3)?");

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

    @Autowired
    private SandboxAuditService auditService;

    /** 写工具允许的物理列类型白名单（建表/落表时校验，防 DDL 注入） */
    private static final java.util.regex.Pattern COL_TYPE_PATTERN = java.util.regex.Pattern.compile(
            "(?i)^(BIGINT|INTEGER|INT|INT4|INT8|SMALLINT|NUMERIC|DECIMAL|DOUBLE PRECISION|REAL|FLOAT|TEXT|VARCHAR|CHAR|BOOLEAN|BOOL|DATE|TIMESTAMP|TIME|JSONB|JSON)"
                    + "(?:\\s*\\(\\s*\\d+\\s*(?:,\\s*\\d+\\s*)?\\))?$");

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
     * @return 每张表一个 DbTableVo：tableName/shortName 为短名（模型唯一需要记住的名，SQL 用 sandbox."短名"），
     *         physicalName 为物理名（新表 == 短名；历史遗留表可能为旧拼接名）
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
            // 模型只需记住短名（tableName），SQL 一律用 sandbox."短名"
            vo.setTableName(rec.getTableName());
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
     * 列出沙箱某表的字段结构。
     *
     * <p>入参 tblName 可以是<b>短名 / 显示名 / 物理名</b>任意一种：方法先经
     * {@link #resolvePhysicalName(Long, String, String)} 解析为真实物理名再查 {@code information_schema}，
     * 从而兼容「去拼接改造」前遗留的旧式物理名表（如 {@code sales_dm__demo_order} 的短名为 {@code demo_order}）。
     * 解析不到时原样尝试（新表短名即物理名），保证新表与遗留表都能正确返回列。
     *
     * @param tblName 表名提示（短名 / 显示名 / 物理名）
     */
    public List<DbColumnVo> listSandboxColumns(String tblName) {
        if (tblName == null || tblName.trim().isEmpty()) {
            return Collections.emptyList();
        }
        // 短名/显示名 → 物理名（兼容历史拼接名）；解析不到则原样尝试（新表短名即物理名）
        String physicalName = resolvePhysicalName(null, tblName.trim(), null);
        if (physicalName == null) {
            physicalName = tblName.trim();
        }
        return querySandboxColumns(physicalName);
    }

    /** 按物理名查 information_schema 列（物理名需合法标识，防注入） */
    private List<DbColumnVo> querySandboxColumns(String physicalName) {
        if (physicalName == null || !IDENT_PATTERN.matcher(physicalName).matches()) {
            log.warn("非法沙箱物理表名，拒绝获取字段：{}", physicalName);
            return Collections.emptyList();
        }
        List<DbColumnVo> cols = jdbcTemplate.query(
                "SELECT column_name, data_type FROM information_schema.columns "
                        + "WHERE table_schema = '" + SANDBOX_SCHEMA + "' AND table_name = ? ORDER BY ordinal_position",
                (rs, i) -> {
                    DbColumnVo vo = new DbColumnVo();
                    vo.setColumnName(rs.getString("column_name"));
                    vo.setDataType(rs.getString("data_type"));
                    return vo;
                },
                physicalName);
        // 从元数据 columns_json 回填中文标签（label），便于 NL2SQL 把中文提问映射到物理列
        try {
            BiSandboxTable meta = sandboxMapper.selectByPhysicalName(physicalName);
            if (meta != null && meta.getColumnsJson() != null && !meta.getColumnsJson().isEmpty()) {
                JSONArray arr = JSON.parseArray(meta.getColumnsJson());
                if (arr != null) {
                    Map<String, String> labelMap = new HashMap<>();
                    for (int i = 0; i < arr.size(); i++) {
                        JSONObject c = arr.getJSONObject(i);
                        String n = c.getString("name");
                        String l = c.getString("label");
                        if (n != null && l != null && !l.equals(n)) {
                            labelMap.put(n.toLowerCase(), l);
                        }
                    }
                    for (DbColumnVo vo : cols) {
                        String lbl = labelMap.get(vo.getColumnName().toLowerCase());
                        if (lbl != null) {
                            vo.setLabel(lbl);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("回填沙箱列标签失败（不影响物理列查询）：{}", e.getMessage());
        }
        return cols;
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
        // 容错：模型有时会用「短名」(如 demo_monthly_revenue) 拼 SQL，但沙箱物理表名就是短名本身，
        // 而 list_tables 返回的就是短名；这里仍按元数据做一次一致性重写/校验，避免「表不存在」。
        String resolved = resolveSandboxTableNames(sql);
        if (!resolved.equals(sql)) {
            log.info("沙箱表名容错重写：{} -> {}", sql, resolved);
            sql = resolved;
        }
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
            // 用短名（tableName）：物理表名 == 短名，模型写 SQL 一律 sandbox."短名"
            String tbl = t.getTableName();
            schemaText.append("## 表名：").append(SANDBOX_SCHEMA).append(".\"").append(tbl).append("\"\n字段：\n");
            for (DbColumnVo c : listSandboxColumns(tbl)) {
                schemaText.append("  - ").append(c.getColumnName());
                if (c.getLabel() != null) {
                    schemaText.append(" (").append(c.getLabel()).append(')');
                }
                schemaText.append(' ').append(c.getDataType()).append('\n');
            }
            schemaText.append('\n');
        }

        // 2. 构建 Prompt（注入 RAG 上下文 + 沙箱专属全限定约束）
        String ragContext = knowledgeService.buildRagContext(userQuery, 0L);
        String prompt = promptBuilder.buildNl2SqlPrompt(
                userQuery, null, schemaText.toString(), ragContext, "postgresql", null);
        prompt += "\n\n【沙箱专属约束】本查询针对数据沙箱（schema=" + SANDBOX_SCHEMA + "）。"
                + "你生成的 SQL 中所有表名必须使用 `" + SANDBOX_SCHEMA + ".\"表名\"` 全限定形式"
                + "（表名即 list_tables 返回的 tableName，例如 " + SANDBOX_SCHEMA + ".\"sales_2025\"），禁止省略 schema 前缀，"
                + "禁止引用 public 或其他 schema 的表，也不要自行拼接任何库前缀（表名本身就是短名）。\n";

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
     * 按 dbId + tableName（短名）修改沙箱表显示名。模型通常只记住短名，本重载更友好。
     *
     * @param dbId        沙箱库 id（可选，用于缩小定位范围）
     * @param tableName   表短名（来自 list_tables 的 tableName）
     * @param displayName 新的显示名（可中文，留空则回退到短名）
     */
    public void renameSandboxTableDisplay(Long dbId, String tableName, String displayName) {
        String physicalName = resolvePhysicalName(dbId, tableName, null);
        if (physicalName == null) {
            throw new BizException("未找到对应的沙箱表：dbId=" + dbId + ", tableName=" + tableName);
        }
        renameSandboxTableDisplay(physicalName, displayName);
    }

    // ============ M2 写工具（需用户确认后由 Agent 调用） ============

    /**
     * 落表（CTAS）：把一个【只读】SELECT 的结果物化成一张新沙箱表。
     *
     * <p>安全边界：SELECT 部分先经 SqlValidator 只读校验 + assertAllTablesInSandbox 边界校验
     * （只允许读 sandbox schema 内的表），再包裹成 {@code CREATE TABLE sandbox."短名" AS <SELECT>}。
     * 目标表物理名直接等于短名（不再拼接库前缀），强制落在 sandbox schema；不会触达 public / bi_* 系统表。
     *
     * @param selectSql       只读 SELECT（表名须 sandbox. 全限定，或短名由本方法自动重写）
     * @param targetTableName 目标表短名（合法标识符，或自动规范化）
     * @param scopeDbId       作用域沙箱库 id（即 Agent 当前锁定的沙箱库，新表落在此库下）；为 null 回落默认库
     * @param operator        操作人（用于审计留痕）
     * @return JSONObject：{tableName(短名), shortName, rowCount, columns}
     */
    public JSONObject materializeTable(String selectSql, String targetTableName, Long scopeDbId, String operator) {
        String opDetail = "selectSql=" + (selectSql == null ? "" : selectSql);
        try {
            if (selectSql == null || selectSql.trim().isEmpty()) {
                throw new BizException("SELECT 语句不能为空");
            }
            if (targetTableName == null || targetTableName.trim().isEmpty()) {
                throw new BizException("目标表名不能为空");
            }
            // 1. 解析作用域库
            BiSandboxDb db = resolveScopeDb(scopeDbId);
            String shortName = sanitizeIdentifierLocal(targetTableName);
            // 物理名 == 短名，全沙箱短名全局唯一：任一库已存在同名表则拒绝
            if (sandboxMapper.countByTableName(shortName) > 0) {
                throw new BizException("沙箱已存在表 " + shortName + "（表名全沙箱唯一），请换名或先删除");
            }
            // 2. 清洗 + 重写短名为物理名 + 边界校验 SELECT 部分（只读 & 全在 sandbox）
            String resolved = resolveSandboxTableNames(selectSql);
            String cleaned = extractSql(resolved);
            sqlValidator.validate(cleaned);
            assertAllTablesInSandbox(cleaned);
            // 物理名即短名（不再拼接库前缀）
            String physicalName = shortName;
            String ddl = "CREATE TABLE " + SANDBOX_SCHEMA + ".\"" + physicalName + "\" AS " + cleaned;
            jdbcTemplate.execute(ddl);
            log.info("沙箱落表完成：{}（库 {}）", physicalName, db.getName());

            // 3. 读取新表结构 + 行数，登记元数据
            List<DbColumnVo> cols = listSandboxColumns(physicalName);
            Integer rowCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + SANDBOX_SCHEMA + ".\"" + physicalName + "\"", Integer.class);
            JSONArray colsJson = new JSONArray();
            for (DbColumnVo c : cols) {
                JSONObject o = new JSONObject();
                o.put("name", c.getColumnName());
                o.put("type", c.getDataType());
                colsJson.add(o);
            }
            BiSandboxTable rec = new BiSandboxTable();
            rec.setDbId(db.getId());
            rec.setTableName(shortName);
            rec.setPhysicalName(physicalName);
            rec.setDisplayName(shortName);
            rec.setOwner(operator);
            rec.setColumnsJson(colsJson.toJSONString());
            rec.setRowCount(rowCount == null ? 0 : rowCount);
            rec.setSourceType("agent_ctas");
            rec.setRemark("Agent 落表（CTAS）");
            sandboxMapper.insert(rec);

            JSONObject out = new JSONObject();
            out.put("tableName", physicalName);
            out.put("shortName", shortName);
            out.put("rowCount", rowCount == null ? 0 : rowCount);
            out.put("columns", colsJson);
            auditService.logSuccess(SandboxAuditService.OP_MATERIALIZE, physicalName, operator,
                    Map.of("db", db.getName(), "sql", cleaned, "rowCount", rowCount == null ? 0 : rowCount));
            return out;
        } catch (Exception e) {
            auditService.logFailure(SandboxAuditService.OP_MATERIALIZE,
                    targetTableName == null ? "" : targetTableName, operator, e.getMessage());
            throw new BizException("落表失败：" + e.getMessage());
        }
    }

    /**
     * 在沙箱内新建一张空表（显式列定义）。
     *
     * @param tableName 表短名（合法标识符或自动规范化）
     * @param columns   列定义列表，每项含 name / type（type 须为白名单类型，如 BIGINT / NUMERIC(18,2) / VARCHAR(50) / TEXT / DATE）
     * @param scopeDbId 作用域沙箱库 id；为 null 回落默认库
     * @param operator  操作人（审计）
     * @return JSONObject：{tableName(短名), shortName, columns}
     */
    public JSONObject createSandboxTable(String tableName, List<Map<String, String>> columns, Long scopeDbId, String operator) {
        try {
            if (tableName == null || tableName.trim().isEmpty()) {
                throw new BizException("表名不能为空");
            }
            if (columns == null || columns.isEmpty()) {
                throw new BizException("至少需要一列");
            }
            BiSandboxDb db = resolveScopeDb(scopeDbId);
            String shortName = sanitizeIdentifierLocal(tableName);
            // 物理名 == 短名，全沙箱短名全局唯一：任一库已存在同名表则拒绝
            if (sandboxMapper.countByTableName(shortName) > 0) {
                throw new BizException("沙箱已存在表 " + shortName + "（表名全沙箱唯一），请换名或先删除");
            }
            StringBuilder colDefs = new StringBuilder();
            JSONArray colsJson = new JSONArray();
            Set<String> seenCols = new HashSet<>();
            for (int i = 0; i < columns.size(); i++) {
                Map<String, String> col = columns.get(i);
                String rawName = col == null ? null : col.get("name");
                String ctype = col == null ? null : col.get("type");
                if (rawName == null || rawName.trim().isEmpty()) {
                    throw new BizException("列名不能为空");
                }
                if (ctype == null || !COL_TYPE_PATTERN.matcher(ctype.trim()).matches()) {
                    throw new BizException("非法列类型：" + ctype + "（仅允许 BIGINT/NUMERIC(18,2)/VARCHAR(n)/TEXT/DATE 等）");
                }
                // 列名 sanitize 为 ASCII 物理名（中文列名自动转义），原始列名保留为 label
                String base = sanitizeIdentifierLocal(rawName);
                String cname = base;
                int k = 1;
                while (!seenCols.add(cname)) {
                    cname = base + "_" + (k++);
                }
                if (i > 0) {
                    colDefs.append(", ");
                }
                colDefs.append('"').append(cname).append("\" ").append(ctype.trim());
                JSONObject o = new JSONObject();
                o.put("name", cname);
                if (!rawName.trim().equals(cname)) {
                    o.put("label", rawName.trim());
                }
                o.put("type", ctype.trim());
                colsJson.add(o);
            }
            String physicalName = shortName; // 物理名即短名，不再拼接库前缀
            String ddl = "CREATE TABLE " + SANDBOX_SCHEMA + ".\"" + physicalName + "\" (" + colDefs + ")";
            jdbcTemplate.execute(ddl);
            log.info("沙箱建表完成：{}（库 {}）列：{}", physicalName, db.getName(), colsJson);

            BiSandboxTable rec = new BiSandboxTable();
            rec.setDbId(db.getId());
            rec.setTableName(shortName);
            rec.setPhysicalName(physicalName);
            rec.setDisplayName(shortName);
            rec.setOwner(operator);
            rec.setColumnsJson(colsJson.toJSONString());
            rec.setRowCount(0);
            rec.setSourceType("agent_create");
            rec.setRemark("Agent 建表");
            sandboxMapper.insert(rec);

            JSONObject out = new JSONObject();
            out.put("tableName", physicalName);
            out.put("shortName", shortName);
            out.put("columns", colsJson);
            auditService.logSuccess(SandboxAuditService.OP_CREATE_TABLE, physicalName, operator,
                    Map.of("db", db.getName(), "columns", colsJson));
            return out;
        } catch (Exception e) {
            auditService.logFailure(SandboxAuditService.OP_CREATE_TABLE,
                    tableName == null ? "" : tableName, operator, e.getMessage());
            throw new BizException("建表失败：" + e.getMessage());
        }
    }

    /**
     * 删除沙箱表（DDL + 元数据 + 审计）。Agent 写工具与前端直接删除都走这里（单一事实来源）。
     *
     * @param physicalName 物理表名（新表即短名，如 emp；历史遗留表可为旧拼接名）
     * @param operator     操作人（审计）
     */
    public void dropSandboxTable(String physicalName, String operator) {
        dropSandboxTable(null, null, physicalName, operator);
    }

    /**
     * 删除沙箱表（支持 dbId + tableName 优先定位）。Agent 写工具传参更灵活：
     * 优先按 dbId+tableName（短名）解析物理表名；若都为空则按 physicalName 解析（新表 physicalName == 短名）。
     *
     * @param dbId         沙箱库 id（可选）
     * @param tableName    表短名（可选）
     * @param physicalName 物理表名（可选，作为兜底/兼容；新表即短名）
     * @param operator     操作人（审计）
     */
    public void dropSandboxTable(Long dbId, String tableName, String physicalName, String operator) {
        String target = null;
        try {
            target = resolvePhysicalName(dbId, tableName, physicalName);
            if (target == null) {
                throw new BizException("未找到对应的沙箱表：dbId=" + dbId + ", tableName=" + tableName + ", physicalName=" + physicalName);
            }
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + SANDBOX_SCHEMA + ".\"" + target + "\"");
            sandboxMapper.deleteByPhysicalName(target);
            log.info("沙箱表已删除：{}（操作人 {}）", target, operator);
            auditService.logSuccess(SandboxAuditService.OP_DROP_TABLE, target, operator, Map.of());
        } catch (Exception e) {
            auditService.logFailure(SandboxAuditService.OP_DROP_TABLE,
                    target == null ? (physicalName == null ? "" : physicalName) : target, operator, e.getMessage());
            throw new BizException("删表失败：" + e.getMessage());
        }
    }

    /**
     * 把用户/模型传入的表名提示解析为真实物理表名。
     * 优先顺序：dbId+tableName（短名） > physicalName 精确匹配 > tableName 模糊匹配 > displayName 模糊匹配。
     *
     * @param dbId         沙箱库 id（可选）
     * @param tableName    表短名（可选）
     * @param physicalName 物理表名提示（可选）
     * @return 真实物理表名；找不到返回 null
     */
    public String resolvePhysicalName(Long dbId, String tableName, String physicalName) {
        // 1. 优先 dbId + tableName 短名（最可靠，避免 __ 被模型吃掉的场景）
        if (dbId != null && tableName != null && !tableName.isBlank()) {
            BiSandboxTable rec = sandboxMapper.selectByDbIdAndTable(dbId, tableName.trim());
            if (rec != null) {
                return rec.getPhysicalName();
            }
        }
        // 2. 按 physicalName 精确匹配
        if (physicalName != null && !physicalName.isBlank()) {
            String p = physicalName.trim();
            if (IDENT_PATTERN.matcher(p).matches()) {
                BiSandboxTable rec = sandboxMapper.selectByPhysicalName(p);
                if (rec != null) {
                    return rec.getPhysicalName();
                }
            }
        }
        // 3. 兜底：按 tableName 短名或 displayName 在全库匹配
        String nameHint = (tableName != null && !tableName.isBlank()) ? tableName.trim()
                : (physicalName != null && !physicalName.isBlank()) ? physicalName.trim() : null;
        if (nameHint == null) {
            return null;
        }
        String lowerHint = nameHint.toLowerCase();
        // 3.1 短名精确匹配（取最近创建的一张，防止跨库同名歧义）
        for (BiSandboxTable rec : sandboxMapper.selectAll()) {
            if (nameHint.equalsIgnoreCase(rec.getTableName())) {
                return rec.getPhysicalName();
            }
        }
        // 3.2 displayName 精确匹配
        BiSandboxTable byDisplay = sandboxMapper.selectByDisplayName(nameHint);
        if (byDisplay != null) {
            return byDisplay.getPhysicalName();
        }
        // 3.3 物理名包含匹配（对用户口语化简称做尽力兜底，如把 products 误写成 product 也能命中）
        for (BiSandboxTable rec : sandboxMapper.selectAll()) {
            String phy = rec.getPhysicalName();
            if (phy != null && phy.toLowerCase().contains(lowerHint)) {
                return phy;
            }
            String shortName = rec.getTableName();
            if (shortName != null && lowerHint.contains(shortName.toLowerCase())) {
                return rec.getPhysicalName();
            }
        }
        return null;
    }

    /** 解析 Agent 当前锁定的作用域沙箱库；dbId 为 null 回落默认库 */
    private BiSandboxDb resolveScopeDb(Long dbId) {
        if (dbId != null) {
            BiSandboxDb db = sandboxMapper.selectDbById(dbId);
            if (db != null) {
                return db;
            }
        }
        BiSandboxDb def = sandboxMapper.selectDbByKey("default");
        if (def != null) {
            return def;
        }
        List<BiSandboxDb> all = sandboxMapper.selectAllDb();
        if (!all.isEmpty()) {
            return all.get(0);
        }
        throw new BizException("沙箱尚未初始化（缺少默认库），请先执行 sandbox_init.sql");
    }

    /** 把任意源标识符规范化为合法沙箱标识符（与 SandboxImportService 同规则） */
    private String sanitizeIdentifierLocal(String raw) {
        if (raw == null) {
            throw new BizException("表名不能为空");
        }
        String s = raw.trim().toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_') {
                sb.append(ch);
            } else {
                sb.append('_');
            }
        }
        String r = sb.toString();
        if (r.isEmpty()) {
            r = "tbl";
        }
        char first = r.charAt(0);
        if (!((first >= 'a' && first <= 'z') || first == '_')) {
            r = "t_" + r;
        }
        // 与库内已存在短名冲突防御：交由调用方 countByDbAndTable 检查
        return r;
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

    /**
     * 把 SQL 中引用的「短表名」按元数据重写回物理名，规避模型用人类可读简称(如 demo_monthly_revenue)拼 SQL
     * 而报「表不存在」的问题。规则：
     * <ul>
     *   <li>遍历所有沙箱表，建 short(table_name) → physical_name 映射；</li>
     *   <li>仅当某个 short 在所有表中唯一时重写，避免跨库同名造成歧义；</li>
     *   <li>只重写 {@code sandbox.表名} 形态（含或不带引号），不触碰其他 schema。</li>
     * </ul>
     */
    private String resolveSandboxTableNames(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        Map<String, String> shortToPhysical = new HashMap<>();
        for (BiSandboxTable rec : sandboxMapper.selectAll()) {
            if (rec.getTableName() == null || rec.getPhysicalName() == null) {
                continue;
            }
            String key = rec.getTableName().toLowerCase();
            if (shortToPhysical.containsKey(key)) {
                // 出现同名短名（不同库），标记歧义，跳过重写
                shortToPhysical.put(key, "__AMBIGUOUS__");
            } else {
                shortToPhysical.put(key, rec.getPhysicalName());
            }
        }
        if (shortToPhysical.isEmpty()) {
            return sql;
        }
        // 匹配 sandbox."name" / sandbox.`name` / sandbox.name（引号可选且前后一致）。
        // name 允许中文等非 ASCII 字符，因此不能用 [A-Za-z0-9_]* 限制。
        Pattern p = Pattern.compile("(?i)\\bsandbox\\.([\"`]?)([^\"`\\s]+)\\1");
        Matcher m = p.matcher(sql);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = m.group(2);
            String physical = shortToPhysical.get(name.toLowerCase());
            if (physical != null && !"__AMBIGUOUS__".equals(physical) && !physical.equalsIgnoreCase(name)) {
                String quote = m.group(1);
                m.appendReplacement(sb, "sandbox." + quote + physical + quote);
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
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
