package com.bi.agent.bi.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.domain.BiDatasource;
import com.bi.agent.bi.domain.BiSandboxDb;
import com.bi.agent.bi.domain.BiSandboxTable;
import com.bi.agent.bi.mapper.BiSandboxMapper;
import com.bi.agent.bi.service.IBiDatasourceService;
import com.bi.agent.bi.util.BiDataSourceFactory;
import com.bi.agent.bi.util.JdbcUrlBuilder;
import com.bi.agent.bi.service.SandboxAuditService;
import com.bi.agent.bi.service.SandboxQueryService;
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
 * <p>逻辑命名空间（沙箱库）：表落在某一沙箱库（bi_sandbox_db）下，物理表名直接等于短名
 * （如 {@code sandbox."sales"}），全沙箱短名全局唯一，全部位于 sandbox schema，
 * 既保留「统一 schema 前缀」安全边界，又避免模型拼接 {@code dbkey__表名} 而丢失下划线导致找不到表。
 *
 * <p>安全：表名与列名强制为合法标识符（{@code [A-Za-z_][A-Za-z0-9_]}），并以双引号包裹，
 * 杜绝 DDL 注入；建表前校验（全沙箱）表名不冲突。
 */
@Service
public class SandboxImportService {

    private static final Logger log = LoggerFactory.getLogger(SandboxImportService.class);

    public static final String SANDBOX_SCHEMA = "sandbox";

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

    @Autowired
    private SandboxQueryService sandboxQueryService;

    @Autowired
    private SandboxAuditService auditService;

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
        // 物理名 == 短名，全沙箱短名全局唯一：任一库已存在同名表则拒绝
        if (sandboxMapper.countByTableName(shortName) > 0) {
            throw new BizException("沙箱已存在表 " + shortName + "（表名全沙箱唯一），请先删除或换名后再导入");
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
        JSONObject res = writeSandboxTable(db.getId(), shortName, colNames, colTypes, dataRows, "paste", null);
        auditService.logSuccess(SandboxAuditService.OP_IMPORT_TEXT, res.getString("tableName"), "ui",
                Map.of("db", db.getName(), "rowCount", res.getInteger("rowCount"),
                        "columns", res.getJSONArray("columns")));
        return res;
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

        // 1) 预校验：目标沙箱表名冲突（任一处冲突则整体拒绝，避免半导入；短名全沙箱唯一）
        List<String> conflicts = new ArrayList<>();
        for (String src : sourceTables) {
            String target = sanitizeIdentifier(src);
            if (sandboxMapper.countByTableName(target) > 0) {
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
            JSONObject res = writeSandboxTable(db.getId(), target, colNames, colTypes, allRows, "datasource", remark);
            results.add(res);
            auditService.logSuccess(SandboxAuditService.OP_IMPORT_DATASOURCE, res.getString("tableName"), "ui",
                    Map.of("source", ds.getName() + "." + src, "db", db.getName(), "rowCount", allRows.size()));
            log.info("数据源表导入沙箱：{} -> sandbox.\"{}\" ({}行)", src, res.getString("tableName"), allRows.size());
        }
        return results;
    }

    /**
     * 建表 + 批量写入 + 登记元数据的共用实现（粘贴导入与数据源导入都走这里）。
     * 物理表名直接等于短名（不再拼接库前缀），全沙箱短名全局唯一。
     *
     * @param dbId      所属沙箱库 id
     * @param shortName 沙箱表短名（已为合法标识符、已全局去重）
     * @param colNames  列名数组（已规范化、已去重）
     * @param colTypes  列类型数组（BIGINT / NUMERIC(18,2) / DATE / TEXT）
     * @param dataRows  数据行（不含表头）
     * @param sourceType 来源标记：paste / datasource
     * @param remark    来源备注（如 数据源名.源表名）
     * @return 导入摘要 JSONObject
     */
    private JSONObject writeSandboxTable(Long dbId, String shortName, String[] colNames, String[] colTypes,
                                        List<String[]> dataRows, String sourceType, String remark) {
        String physicalName = shortName; // 物理名即短名，不再拼接库前缀
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
     * 从上传的文件（Excel .xlsx/.xls 或 CSV .csv）导入沙箱（M3）。
     *
     * <p>解析出表头 + 数据行后，复用 {@link #writeSandboxTable} 做类型推断、建表与批量写入，并登记审计。
     * 为避免大文件撑爆沙箱库，数据行上限 {@link #MAX_IMPORT_ROWS}（超出截断）。
     *
     * @param file      上传文件（MultipartFile）
     * @param tableName 目标沙箱表短名（必填，合法标识符或自动规范化）
     * @param dbId      目标沙箱库 id；为 null 时落入默认库
     * @return 导入摘要 JSONObject
     */
    public JSONObject importFromFile(org.springframework.web.multipart.MultipartFile file, String tableName, Long dbId) {
        if (file == null || file.isEmpty()) {
            throw new BizException("上传文件为空");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        boolean isCsv = original.endsWith(".csv");
        boolean isExcel = original.endsWith(".xlsx") || original.endsWith(".xls");
        if (!isCsv && !isExcel) {
            throw new BizException("仅支持 .csv / .xlsx / .xls 文件");
        }
        try {
            List<String[]> rows;
            if (isCsv) {
                rows = parseCsv(file);
            } else {
                rows = parseExcel(file);
            }
            if (rows.size() < 2) {
                throw new BizException("文件至少需要表头行 + 一行数据");
            }
            // 规范化表名 + 冲突检查（与 importFromText 同路径）
            BiSandboxDb db = resolveDb(dbId);
            String shortName = sanitizeIdentifier(tableName);
            // 物理名 == 短名，全沙箱短名全局唯一
            if (sandboxMapper.countByTableName(shortName) > 0) {
                throw new BizException("沙箱已存在表 " + shortName + "（表名全沙箱唯一），请先删除或换名后再导入");
            }
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
            List<String[]> dataRows = new ArrayList<>(rows.subList(1, rows.size()));
            JSONObject res = writeSandboxTable(db.getId(), shortName, colNames, colTypes, dataRows, "upload", original);
            auditService.logSuccess(SandboxAuditService.OP_IMPORT_FILE, res.getString("tableName"), "ui",
                    Map.of("file", file.getOriginalFilename(), "db", db.getName(), "rowCount", res.getInteger("rowCount"),
                            "columns", res.getJSONArray("columns")));
            return res;
        } catch (BizException e) {
            auditService.logFailure(SandboxAuditService.OP_IMPORT_FILE, tableName == null ? "" : tableName, "ui", e.getMessage());
            throw e;
        } catch (Exception e) {
            auditService.logFailure(SandboxAuditService.OP_IMPORT_FILE, tableName == null ? "" : tableName, "ui", e.getMessage());
            throw new BizException("文件导入失败：" + e.getMessage());
        }
    }

    /** 解析 CSV 为「首行表头 + 数据行」的二维数组（使用 commons-csv 正确处理引号与转义） */
    private List<String[]> parseCsv(org.springframework.web.multipart.MultipartFile file) throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (java.io.InputStream in = file.getInputStream();
             org.apache.commons.csv.CSVParser parser = new org.apache.commons.csv.CSVParser(
                     new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8),
                     org.apache.commons.csv.CSVFormat.DEFAULT)) {
            for (org.apache.commons.csv.CSVRecord rec : parser) {
                if (rows.size() > MAX_IMPORT_ROWS) {
                    break; // 数据安全：仅保留上限行
                }
                String[] arr = new String[rec.size()];
                for (int i = 0; i < rec.size(); i++) {
                    arr[i] = rec.get(i);
                }
                rows.add(arr);
            }
        }
        return rows;
    }

    /** 解析 Excel（.xlsx/.xls）为二维数组（Apache POI，首行为表头） */
    private List<String[]> parseExcel(org.springframework.web.multipart.MultipartFile file) throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (java.io.InputStream in = file.getInputStream();
             org.apache.poi.ss.usermodel.Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(in)) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheetAt(0);
            int taken = 0;
            for (org.apache.poi.ss.usermodel.Row row : sheet) {
                if (taken > MAX_IMPORT_ROWS) {
                    break;
                }
                int n = row.getLastCellNum();
                if (n < 0) {
                    n = 0;
                }
                String[] arr = new String[n];
                for (int i = 0; i < n; i++) {
                    org.apache.poi.ss.usermodel.Cell cell = row.getCell(i, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    arr[i] = readCellToString(cell);
                }
                rows.add(arr);
                taken++;
            }
        }
        return rows;
    }

    /** 把 POI 单元格读成字符串（数值/布尔/公式统一转文本，避免类型推断失真） */
    private String readCellToString(org.apache.poi.ss.usermodel.Cell cell) {
        if (cell == null) {
            return "";
        }
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                double d = cell.getNumericCellValue();
                // 整数形态去 .0，便于后续类型推断判 INTEGER
                if (d == Math.rint(d) && !Double.isInfinite(d)) {
                    return String.valueOf((long) d);
                }
                return String.valueOf(d);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return String.valueOf(cell.getNumericCellValue());
                } catch (Exception e) {
                    return cell.getStringCellValue();
                }
            default:
                return "";
        }
    }

    /**
     * 向已有沙箱表导入/追加数据行（Agent 用）。
     *
     * <p>按 dbId + tableName（短名）定位目标表，从元数据 columns_json 读取列结构，校验传入数据后批量插入。
     * 支持两种模式：append（追加，默认）与 replace（先清空再插入）。
     *
     * @param tableName 目标沙箱表短名（来自 list_tables 的返回）
     * @param rows      数据行，每项为 {列名: 值} 的 Map；列名大小写不敏感，缺失列补 null
     * @param dbId      目标沙箱库 id（可选，用于缩小定位范围）
     * @param mode      append 或 replace；默认 append
     * @param operator  操作人（审计）
     * @return 导入摘要 JSONObject：{tableName, rowCount(导入后总行数), inserted(本次插入行数), mode}
     */
    public JSONObject importDataIntoTable(String tableName, List<Map<String, Object>> rows, Long dbId, String mode, String operator) {
        if (tableName == null || tableName.trim().isEmpty()) {
            throw new BizException("表名不能为空");
        }
        if (rows == null || rows.isEmpty()) {
            throw new BizException("数据行不能为空");
        }
        if (rows.size() > MAX_IMPORT_ROWS) {
            throw new BizException("单次导入超过上限 " + MAX_IMPORT_ROWS + " 行，请分批");
        }

        // 1. 定位物理表与元数据
        String physicalName = sandboxQueryService.resolvePhysicalName(dbId, tableName, null);
        if (physicalName == null) {
            throw new BizException("未找到对应的沙箱表：dbId=" + dbId + ", tableName=" + tableName);
        }
        BiSandboxTable meta = sandboxMapper.selectByPhysicalName(physicalName);
        if (meta == null) {
            throw new BizException("沙箱表元数据不存在：" + physicalName);
        }

        // 2. 解析列结构
        String[] colNames;
        String[] colTypes;
        try {
            JSONArray cols = JSON.parseArray(meta.getColumnsJson());
            if (cols == null || cols.isEmpty()) {
                throw new BizException("目标表无元数据列定义，无法导入");
            }
            colNames = new String[cols.size()];
            colTypes = new String[cols.size()];
            for (int i = 0; i < cols.size(); i++) {
                JSONObject c = cols.getJSONObject(i);
                colNames[i] = c.getString("name").toLowerCase();
                colTypes[i] = c.getString("type");
            }
        } catch (Exception e) {
            throw new BizException("解析目标表列定义失败：" + e.getMessage());
        }

        // 3. replace 模式先清空
        boolean isReplace = "replace".equalsIgnoreCase(mode);
        if (isReplace) {
            jdbcTemplate.execute("TRUNCATE TABLE " + SANDBOX_SCHEMA + ".\"" + physicalName + "\"");
        }

        // 4. 批量插入
        String insertSql = buildInsertSql(physicalName, colNames);
        List<Object[]> batch = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object[] params = new Object[colNames.length];
            for (int c = 0; c < colNames.length; c++) {
                Object v = row == null ? null : findValueIgnoreCase(row, colNames[c]);
                params[c] = convertValue(v, colTypes[c]);
            }
            batch.add(params);
        }
        jdbcTemplate.batchUpdate(insertSql, batch);

        // 5. 更新元数据 row_count
        int inserted = batch.size();
        int finalRowCount = isReplace ? inserted
                : (meta.getRowCount() == null ? 0 : meta.getRowCount()) + inserted;
        sandboxMapper.updateRowCountByPhysical(physicalName, finalRowCount);

        // 6. 审计
        auditService.logSuccess(SandboxAuditService.OP_IMPORT_DATA, physicalName, operator,
                Map.of("db", meta.getDbId(), "mode", isReplace ? "replace" : "append",
                        "inserted", inserted, "rowCount", finalRowCount));

        JSONObject out = new JSONObject();
        out.put("tableName", physicalName);
        out.put("rowCount", finalRowCount);
        out.put("inserted", inserted);
        out.put("mode", isReplace ? "replace" : "append");
        return out;
    }

    /** 在 Map 中按 key 忽略大小写查找值 */
    private Object findValueIgnoreCase(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return null;
        }
        if (map.containsKey(key)) {
            return map.get(key);
        }
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (key.equalsIgnoreCase(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 把 JSON 解析出的值转成目标列类型（兼容 Number / Boolean / String）。 */
    private Object convertValue(Object value, String type) {
        if (value == null) {
            return null;
        }
        if (type == null) {
            return String.valueOf(value);
        }
        String t = type.trim().toUpperCase();
        if ("BIGINT".equals(t) || "INTEGER".equals(t) || "INT".equals(t)
                || "INT4".equals(t) || "INT8".equals(t) || "SMALLINT".equals(t)) {
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
            return Long.parseLong(String.valueOf(value).trim());
        }
        if (t.startsWith("NUMERIC") || t.startsWith("DECIMAL") || "DOUBLE PRECISION".equals(t)
                || "REAL".equals(t) || "FLOAT".equals(t)) {
            if (value instanceof Number) {
                return new BigDecimal(value.toString());
            }
            return new BigDecimal(String.valueOf(value).trim());
        }
        if ("DATE".equals(t) || "TIMESTAMP".equals(t) || "TIME".equals(t)) {
            String s = String.valueOf(value).trim().replace('/', '-');
            if (s.isEmpty()) {
                return null;
            }
            if ("DATE".equals(t)) {
                return Date.valueOf(s);
            }
            // TIMESTAMP/TIME 暂按字符串透传，让 PG 做隐式转换
            return s;
        }
        if ("BOOLEAN".equals(t) || "BOOL".equals(t)) {
            if (value instanceof Boolean) {
                return value;
            }
            String s = String.valueOf(value).trim().toLowerCase();
            return "true".equals(s) || "1".equals(s) || "yes".equals(s) || "y".equals(s);
        }
        return String.valueOf(value);
    }

    /**
     * 删除沙箱表（前端「数据沙箱」页主动点击，属明确意图操作；统一委托 SandboxQueryService 执行 DDL+元数据+审计）。
     *
     * @param physicalName 物理表名（如 marts__sales）
     */
    public void dropSandboxTable(String physicalName) {
        // 委托给沙箱写服务（单一事实来源，含审计留痕）
        sandboxQueryService.dropSandboxTable(physicalName, "ui");
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
        try {
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
            auditService.logSuccess(SandboxAuditService.OP_DROP_DB, db.getDbKey(), "ui",
                    Map.of("name", db.getName()));
        } catch (Exception e) {
            auditService.logFailure(SandboxAuditService.OP_DROP_DB, db.getDbKey(), "ui", e.getMessage());
            throw e instanceof BizException ? (BizException) e : new BizException("删库失败：" + e.getMessage());
        }
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
