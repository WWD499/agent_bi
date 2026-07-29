package com.bi.agent.bi.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.domain.BiDatasource;
import com.bi.agent.bi.domain.BiSandboxDb;
import com.bi.agent.bi.domain.BiSandboxTable;
import com.bi.agent.bi.mapper.BiSandboxMapper;
import com.bi.agent.bi.service.IBiDatasourceService;
import com.bi.agent.bi.util.BiDataSourceFactory;
import com.bi.agent.bi.util.JdbcUrlBuilder;
import com.bi.agent.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;

import com.zaxxer.hikari.HikariDataSource;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSetMetaData;
import java.util.*;

/**
 * 数据沙箱导入服务（M1）。
 *
 * <p>把用户粘贴的 CSV / TSV 文本，或从业务数据源勾选的表，解析为结构化数据：自动识别分隔符、
 * 按列采样做类型推断、在 {@code sandbox} schema 下动态建表、批量写入，并登记元数据到
 * {@code bi_sandbox_table}。
 *
 * <p>逻辑命名空间（沙箱库）：表落在某一沙箱库（bi_sandbox_db）下，物理表名 =
 * {@code db_key || "__" || table_name}（如 {@code sandbox."marts__sales"}），全部仍位于 sandbox schema，
 * 因此既保留「统一 schema 前缀」安全边界，又实现「按库选择分析」体验。
 *
 * <p>安全：表名与列名强制为合法标识符（{@code [A-Za-z_][A-Za-z0-9_]}），并以双引号包裹，
 * 杜绝 DDL 注入；建表前校验（库内）表名不冲突。
 */
@Service
public class SandboxImportService {

    private static final Logger log = LoggerFactory.getLogger(SandboxImportService.class);

    public static final String SANDBOX_SCHEMA = "sandbox";

    /** 物理名前缀分隔符：db_key 与 table_name 之间 */
    public static final String PHYSICAL_SEP = "__";

    /** 默认库 db_key（存量兼容迁移种子） */
    public static final String DEFAULT_DB_KEY = "default";

    /** 单表从数据源导入时最多拷贝的行数，防止大表撑爆沙箱库 */
    public static final int MAX_IMPORT_ROWS = 10000;

    /** 标识符合法正则：英文/数字/下划线，首字符非数字 */
    private static final java.util.regex.Pattern IDENT_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final java.util.regex.Pattern INT_PATTERN =
            java.util.regex.Pattern.compile("^-?\\d+$");
    private static final java.util.regex.Pattern DEC_PATTERN =
            java.util.regex.Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final java.util.regex.Pattern DATE_PATTERN =
            java.util.regex.Pattern.compile("^\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}$");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BiSandboxMapper sandboxMapper;

    @Autowired
    private IBiDatasourceService datasourceService;

    @Autowired
    private BiDataSourceFactory dataSourceFactory;

    /**
     * 解析粘贴文本并导入沙箱（可指定目标沙箱库）。
     *
     * @param rawText   粘贴的 CSV/TSV 文本（首行为表头）
     * @param tableName 沙箱表短名（必填，合法标识符，或自动规范化为标识符）
     * @param separator 分隔符："," 或 "\t"；为空则自动检测
     * @param dbId      目标沙箱库 id；为 null 时落入默认库
     * @return JSONObject：{tableName(物理名), shortName, rowCount, columns:[{name,type}]}
     */
    public JSONObject importFromText(String rawText, String tableName, String separator, Long dbId) {
        if (rawText == null || rawText.trim().isEmpty()) {
            throw new BizException("粘贴内容不能为空");
        }
        if (tableName == null || tableName.trim().isEmpty()) {
            throw new BizException("表名不能为空");
        }
        BiSandboxDb db = resolveDb(dbId);
        String shortName = sanitizeIdentifier(tableName);
        if (sandboxMapper.countByDbAndTable(db.getId(), shortName) > 0) {
            throw new BizException("沙箱库[" + db.getName() + "]已存在表 " + shortName + "，请先删除或换名后再导入");
        }

        // 1. 按行解析（跳过空行）
        String sep = (separator == null || separator.trim().isEmpty())
                ? detectSeparator(rawText) : separator;
        String[] lines = rawText.split("\\r?\\n");
        List<String[]> rows = new ArrayList<>();
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }
            rows.add(line.split(java.util.regex.Pattern.quote(sep), -1));
        }
        if (rows.size() < 2) {
            throw new BizException("至少需要表头行 + 一行数据");
        }

        // 2. 规范化列名
        String[] headers = rows.get(0);
        int colCount = headers.length;
        String[] colNames = new String[colCount];
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < colCount; i++) {
            String h = (headers[i] == null) ? "" : headers[i].trim();
            if (h.isEmpty()) {
                h = "col" + (i + 1);
            }
            if (!IDENT_PATTERN.matcher(h).matches()) {
                throw new BizException("列名非法（仅英文/数字/下划线，首字符非数字）：" + headers[i]);
            }
            String lc = h.toLowerCase();
            if (!seen.add(lc)) {
                throw new BizException("存在重复列名：" + lc);
            }
            colNames[i] = lc;
        }

        // 3. 按列采样非空值做类型推断
        String[] colTypes = new String[colCount];
        for (int c = 0; c < colCount; c++) {
            List<String> samples = new ArrayList<>();
            for (int r = 1; r < rows.size(); r++) {
                String[] row = rows.get(r);
                if (row.length <= c) {
                    continue;
                }
                String v = row[c];
                if (v != null && !v.trim().isEmpty()) {
                    samples.add(v.trim());
                }
            }
            colTypes[c] = inferType(samples);
        }

        // 4. 组装数据行（去掉表头行）并共用 writeSandboxTable 建表写入
        List<String[]> dataRows = new ArrayList<>(rows.subList(1, rows.size()));
        return writeSandboxTable(db.getId(), db.getDbKey(), shortName, colNames, colTypes, dataRows, "paste", null);
    }

    /**
     * 从已配置的数据源批量拷贝指定表进沙箱（替代复制粘贴，用户在前端勾选后一键导入）。
     *
     * @param datasourceId 业务数据源 id（对应 bi_datasource）
     * @param sourceTables 待导入的源表名列表
     * @param dbId         目标沙箱库 id；为 null 时落入默认库
     * @return 每张表的导入摘要 JSONObject 列表
     */
    public List<JSONObject> importFromDatasource(Long datasourceId, List<String> sourceTables, Long dbId) {
        if (datasourceId == null) {
            throw new BizException("数据源ID不能为空");
        }
        if (sourceTables == null || sourceTables.isEmpty()) {
            throw new BizException("请至少选择一张表");
        }
        BiDatasource ds = datasourceService.selectBiDatasourceById(datasourceId);
        if (ds == null) {
            throw new BizException("数据源不存在，id=" + datasourceId);
        }
        BiSandboxDb db = resolveDb(dbId);
        HikariDataSource hds = dataSourceFactory.getDataSource(ds);
        JdbcTemplate bizJt = new JdbcTemplate(hds);
        boolean isPg = JdbcUrlBuilder.isPostgres(ds);

        // 1) 预校验：目标沙箱表名冲突（任一处冲突则整体拒绝，避免半导入）
        List<String> conflicts = new ArrayList<>();
        for (String src : sourceTables) {
            String target = sanitizeIdentifier(src);
            if (sandboxMapper.countByDbAndTable(db.getId(), target) > 0) {
                conflicts.add(target);
            }
        }
        if (!conflicts.isEmpty()) {
            throw new BizException("以下沙箱表在库[" + db.getName() + "]已存在，请先删除再导入：" + String.join(", ", conflicts));
        }

        // 2) 逐表拷贝
        List<JSONObject> results = new ArrayList<>();
        for (String src : sourceTables) {
            String target = sanitizeIdentifier(src);
            String quotedSrc = isPg ? "\"" + src + "\"" : "`" + src + "`";
            String sql = "SELECT * FROM " + quotedSrc + " LIMIT " + MAX_IMPORT_ROWS;
            // ResultSetExtractor 一次性取出列名与全部数据行，避免跨行透传列名的 hack
            final String[][] rawColsHolder = {null};
            List<String[]> allRows = bizJt.query(sql, (ResultSetExtractor<List<String[]>>) rs -> {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                String[] names = new String[n];
                for (int i = 0; i < n; i++) {
                    names[i] = md.getColumnLabel(i + 1);
                }
                rawColsHolder[0] = names;
                List<String[]> rows = new ArrayList<>();
                while (rs.next()) {
                    String[] row = new String[n];
                    for (int i = 0; i < n; i++) {
                        row[i] = rs.getString(i + 1);
                    }
                    rows.add(row);
                }
                return rows;
            });
            String[] rawCols = rawColsHolder[0];
            if (rawCols == null || rawCols.length == 0) {
                throw new BizException("表 " + src + " 无可用列，导入中止");
            }
            String[] colNames = sanitizeColumns(rawCols);
            int c = colNames.length;

            // 按列采样非空值做类型推断
            List<List<String>> samples = new ArrayList<>(c);
            for (int i = 0; i < c; i++) {
                samples.add(new ArrayList<>());
            }
            for (String[] row : allRows) {
                for (int i = 0; i < c; i++) {
                    String v = (i < row.length) ? row[i] : null;
                    if (v != null && !v.trim().isEmpty()) {
                        samples.get(i).add(v.trim());
                    }
                }
            }
            String[] colTypes = new String[c];
            for (int i = 0; i < c; i++) {
                colTypes[i] = inferType(samples.get(i));
            }

            String remark = ds.getName() + "." + src
                    + (allRows.size() >= MAX_IMPORT_ROWS ? " (截断至" + MAX_IMPORT_ROWS + "行)" : "");
            JSONObject res = writeSandboxTable(db.getId(), db.getDbKey(), target, colNames, colTypes, allRows, "datasource", remark);
            results.add(res);
            log.info("数据源表导入沙箱：{} -> sandbox.\"{}\" ({}行)", src, res.getString("tableName"), allRows.size());
        }
        return results;
    }

    /**
     * 建表 + 批量写入 + 登记元数据的共用实现（粘贴导入与数据源导入都走这里）。
     *
     * @param dbId      所属沙箱库 id
     * @param dbKey     沙箱库前缀键（用于拼物理名）
     * @param shortName 沙箱表短名（已为合法标识符、已去重）
     * @param colNames  列名数组（已规范化、已去重）
     * @param colTypes  列类型数组（BIGINT / NUMERIC(18,2) / DATE / TEXT）
     * @param dataRows  数据行（不含表头）
     * @param sourceType 来源标记：paste / datasource
     * @param remark    来源备注（如 数据源名.源表名）
     * @return 导入摘要 JSONObject
     */
    private JSONObject writeSandboxTable(Long dbId, String dbKey, String shortName, String[] colNames, String[] colTypes,
                                        List<String[]> dataRows, String sourceType, String remark) {
        String physicalName = dbKey + PHYSICAL_SEP + shortName;
        // 1. 动态建表
        StringBuilder ddl = new StringBuilder("CREATE TABLE ")
                .append(SANDBOX_SCHEMA).append(".\"").append(physicalName).append("\" (");
        for (int c = 0; c < colNames.length; c++) {
            if (c > 0) {
                ddl.append(", ");
            }
            ddl.append('"').append(colNames[c]).append("\" ").append(colTypes[c]);
        }
        ddl.append(")");
        jdbcTemplate.execute(ddl.toString());
        log.info("沙箱建表 {} 完成，列：{}", physicalName, Arrays.toString(colTypes));

        // 2. 批量插入
        String insertSql = buildInsertSql(physicalName, colNames);
        List<Object[]> batch = new ArrayList<>();
        for (String[] row : dataRows) {
            Object[] params = new Object[colNames.length];
            for (int c = 0; c < colNames.length; c++) {
                String v = (c < row.length) ? row[c] : null;
                params[c] = convertCell(v, colTypes[c]);
            }
            batch.add(params);
        }
        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(insertSql, batch);
        }

        // 3. 登记元数据
        BiSandboxTable rec = new BiSandboxTable();
        rec.setDbId(dbId);
        rec.setTableName(shortName);
        rec.setPhysicalName(physicalName);
        // 默认显示名 = 短名（如 emp），用户后续可在前端改为中文名（如 员工表）
        rec.setDisplayName(shortName);
        rec.setOwner(null);
        JSONArray colsJson = new JSONArray();
        for (int c = 0; c < colNames.length; c++) {
            JSONObject o = new JSONObject();
            o.put("name", colNames[c]);
            o.put("type", colTypes[c]);
            colsJson.add(o);
        }
        rec.setColumnsJson(colsJson.toJSONString());
        rec.setRowCount(dataRows.size());
        rec.setSourceType(sourceType);
        rec.setRemark(remark);
        sandboxMapper.insert(rec);

        // 4. 返回摘要
        JSONObject out = new JSONObject();
        out.put("tableName", physicalName);
        out.put("shortName", shortName);
        out.put("rowCount", dataRows.size());
        out.put("columns", colsJson);
        return out;
    }

    /**
     * 删除沙箱表（用户在前端「数据沙箱」页主动点击，属明确意图操作，不受 Agent 写工具 M1 约束限制）。
     *
     * @param physicalName 物理表名（如 marts__sales）
     */
    public void dropSandboxTable(String physicalName) {
        if (physicalName == null || !IDENT_PATTERN.matcher(physicalName).matches()) {
            throw new BizException("非法表名：" + physicalName);
        }
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + SANDBOX_SCHEMA + ".\"" + physicalName + "\"");
        sandboxMapper.deleteByPhysicalName(physicalName);
        log.info("沙箱表已删除：{}", physicalName);
    }

    /**
     * 删除沙箱库（级联删除其下全部物理表 + 元数据 + 库行）。
     *
     * @param dbId 沙箱库 id
     */
    public void dropSandboxDb(Long dbId) {
        if (dbId == null) {
            throw new BizException("沙箱库ID不能为空");
        }
        BiSandboxDb db = sandboxMapper.selectDbById(dbId);
        if (db == null) {
            throw new BizException("沙箱库不存在，id=" + dbId);
        }
        if (DEFAULT_DB_KEY.equals(db.getDbKey())) {
            throw new BizException("默认库不可删除（可清空其中的表，但库本身保留）");
        }
        // 1) 删物理表
        for (BiSandboxTable rec : sandboxMapper.selectByDbId(dbId)) {
            String physical = rec.getPhysicalName();
            if (physical != null && IDENT_PATTERN.matcher(physical).matches()) {
                jdbcTemplate.execute("DROP TABLE IF EXISTS " + SANDBOX_SCHEMA + ".\"" + physical + "\"");
            }
        }
        // 2) 删元数据
        sandboxMapper.deleteTablesByDbId(dbId);
        // 3) 删库行
        sandboxMapper.deleteDbById(dbId);
        log.info("沙箱库已删除：{}（{}）", db.getName(), db.getDbKey());
    }

    /** 解析目标沙箱库：dbId 为 null 时回落到默认库；库不存在则报错 */
    private BiSandboxDb resolveDb(Long dbId) {
        if (dbId == null) {
            BiSandboxDb def = sandboxMapper.selectDbByKey(DEFAULT_DB_KEY);
            if (def != null) {
                return def;
            }
            List<BiSandboxDb> all = sandboxMapper.selectAllDb();
            if (!all.isEmpty()) {
                return all.get(0);
            }
            throw new BizException("沙箱尚未初始化（缺少默认库），请先执行 sandbox_init.sql");
        }
        BiSandboxDb db = sandboxMapper.selectDbById(dbId);
        if (db == null) {
            throw new BizException("沙箱库不存在，id=" + dbId);
        }
        return db;
    }

    // ---- 内部辅助 ----

    private String detectSeparator(String text) {
        // 取前两行判断：含制表符优先 tab，否则逗号
        String[] lines = text.split("\\r?\\n", 3);
        for (String line : lines) {
            if (line.contains("\t")) {
                return "\t";
            }
        }
        return ",";
    }

    private String inferType(List<String> samples) {
        if (samples.isEmpty()) {
            return "TEXT";
        }
        boolean intOk = true, decOk = true, dateOk = true;
        for (String s : samples) {
            if (!INT_PATTERN.matcher(s).matches()) {
                intOk = false;
            }
            if (!DEC_PATTERN.matcher(s).matches()) {
                decOk = false;
            }
            if (!DATE_PATTERN.matcher(s).matches()) {
                dateOk = false;
            }
        }
        if (intOk) {
            return "BIGINT";
        }
        if (decOk) {
            return "NUMERIC(18,2)";
        }
        if (dateOk) {
            return "DATE";
        }
        return "TEXT";
    }

    private String buildInsertSql(String physicalName, String[] colNames) {
        StringBuilder cols = new StringBuilder();
        StringBuilder ph = new StringBuilder();
        for (int i = 0; i < colNames.length; i++) {
            if (i > 0) {
                cols.append(", ");
                ph.append(", ");
            }
            cols.append('"').append(colNames[i]).append('"');
            ph.append('?');
        }
        return "INSERT INTO " + SANDBOX_SCHEMA + ".\"" + physicalName + "\" (" + cols + ") VALUES (" + ph + ")";
    }

    private Object convertCell(String cell, String type) {
        if (cell == null || cell.trim().isEmpty()) {
            return null;
        }
        String s = cell.trim();
        if ("BIGINT".equals(type)) {
            return Long.parseLong(s);
        }
        if (type.startsWith("NUMERIC")) {
            return new BigDecimal(s);
        }
        if ("DATE".equals(type)) {
            return Date.valueOf(s.replace('/', '-'));
        }
        return s;
    }

    /**
     * 把任意源标识符规范化为合法沙箱标识符：转小写、非 [a-z0-9_] 转下划线、首字符非法则补 t_。
     */
    private String sanitizeIdentifier(String raw) {
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
        return r;
    }

    /**
     * 规范化列名数组，去重（重复时追加 _1/_2...），保证每个都是合法标识符。
     */
    private String[] sanitizeColumns(String[] raw) {
        String[] out = new String[raw.length];
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.length; i++) {
            String base = sanitizeIdentifier(raw[i]);
            String name = base;
            int k = 1;
            while (!seen.add(name)) {
                name = base + "_" + (k++);
            }
            out[i] = name;
        }
        return out;
    }
}
