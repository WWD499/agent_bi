package com.bi.agent.bi.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.vo.DbColumnVo;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据沙箱导入「集成测试」（连真实本地 PostgreSQL，验证此前中文列名导入 bug 的修复与周边逻辑）。
 *
 * <p>说明：
 * <ul>
 *   <li>使用 @SpringBootTest(webEnvironment = NONE) 加载真实 DataSource / MyBatis / JdbcTemplate，
 *       不启 Tomcat；本机 PG（localhost:5432/agent_bi）已在运行（见运行日志 HikariPool 连接成功）。</li>
 *   <li>所有测试表名以 {@code itx} 前缀（纯字母数字，无下划线），@BeforeEach 先清空历史残留，
 *       @AfterEach 再清理本次创建的表与元数据，避免污染业务库。</li>
 *   <li>覆盖核心修复：中文表头不再被「仅英文/数字/下划线」规则拒绝，而是 sanitize 成 ASCII 物理列名
 *       并把原始中文表头保留为 label 写入 columns_json；Excel / CSV / 粘贴三条导入路径行为一致。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SandboxImportIntegrationTest {

    @Autowired
    private SandboxImportService importService;

    @Autowired
    private SandboxQueryService queryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本次测试创建的物理短名集合，用于 @AfterEach 清理 */
    private final Set<String> createdTables = new HashSet<>();

    @BeforeEach
    void setUp() {
        ensureDefaultDb();
        cleanItTables();
    }

    @AfterEach
    void tearDown() {
        for (String t : createdTables) {
            try {
                jdbcTemplate.execute("DROP TABLE IF EXISTS sandbox.\"" + t + "\"");
            } catch (Exception ignored) {
            }
            try {
                jdbcTemplate.update("DELETE FROM bi_sandbox_table WHERE table_name = ?", t);
            } catch (Exception ignored) {
            }
        }
        createdTables.clear();
    }

    // ---- 辅助 ----

    private void ensureDefaultDb() {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bi_sandbox_db WHERE db_key='default'", Integer.class);
        if (c == null || c == 0) {
            jdbcTemplate.update(
                    "INSERT INTO bi_sandbox_db(db_key,name,remark) VALUES('default','默认库','默认库')");
        }
    }

    /** 清空所有 itx 前缀的沙箱物理表与元数据（跨运行/跨方法隔离） */
    private void cleanItTables() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema='sandbox' AND table_name LIKE 'itx%'",
                String.class);
        for (String t : tables) {
            jdbcTemplate.execute("DROP TABLE IF EXISTS sandbox.\"" + t + "\"");
        }
        jdbcTemplate.update("DELETE FROM bi_sandbox_table WHERE table_name LIKE 'itx%'");
    }

    /** 生成唯一的 itx 前缀表名（已为合法 ASCII 标识，sanitize 后不变），并登记待清理 */
    private String newTableName() {
        String t = "itx" + UUID.randomUUID().toString().replace("-", "");
        createdTables.add(t);
        return t;
    }

    private void assertHasLabel(JSONArray cols, String label) {
        boolean found = false;
        for (int i = 0; i < cols.size(); i++) {
            JSONObject o = cols.getJSONObject(i);
            if (label.equals(o.getString("label"))) {
                found = true;
                break;
            }
        }
        assertTrue(found, "期望 columns 中存在 label=" + label);
    }

    // ---- 测试用例 ----

    /** 核心修复：粘贴文本含中文表头，应 sanitize 成 ASCII 物理列名并保留中文 label，且真实建表写入 */
    @Test
    void importFromText_chineseHeaders_keepsLabelAndAsciiPhysical() {
        String tableName = newTableName();
        String csv = "序号,姓名,入职日期\n1,张三,2024-01-15\n2,李四,2023-06-01";
        JSONObject res = importService.importFromText(csv, tableName, ",", null);

        assertEquals(2, res.getInteger("rowCount"));
        JSONArray cols = res.getJSONArray("columns");
        assertEquals(3, cols.size());
        assertHasLabel(cols, "序号");
        assertHasLabel(cols, "姓名");
        assertHasLabel(cols, "入职日期");

        // 所有物理列名必须是 ASCII 合法标识
        for (int i = 0; i < cols.size(); i++) {
            String n = cols.getJSONObject(i).getString("name");
            assertTrue(n.matches("[A-Za-z_][A-Za-z0-9_]*"), "物理列名必须 ASCII：" + n);
        }

        // 物理表真实存在且写入 2 行
        String physical = res.getString("tableName");
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sandbox.\"" + physical + "\"", Integer.class);
        assertEquals(2, cnt);

        // 端到端：list_columns 回填中文 label
        List<DbColumnVo> vos = queryService.listSandboxColumns(physical);
        assertTrue(vos.stream().anyMatch(v ->
                Arrays.asList("序号", "姓名", "入职日期").contains(v.getLabel())));
    }

    /** 类型推断：整型→BIGINT、小数→NUMERIC(18,2)、日期→DATE、文本→TEXT */
    @Test
    void importFromText_infersColumnTypes() {
        String tableName = newTableName();
        String csv = "id,age,score,hire,memo\n1,30,99.5,2024-01-01,hello\n2,25,88.0,2023-02-02,world";
        JSONObject res = importService.importFromText(csv, tableName, ",", null);

        Map<String, String> typeByName = new HashMap<>();
        for (int i = 0; i < res.getJSONArray("columns").size(); i++) {
            JSONObject o = res.getJSONArray("columns").getJSONObject(i);
            typeByName.put(o.getString("name"), o.getString("type"));
        }
        // 这些列名本身已是 ASCII，sanitize 后不变
        assertEquals("BIGINT", typeByName.get("id"));
        assertEquals("BIGINT", typeByName.get("age"));
        assertEquals("NUMERIC(18,2)", typeByName.get("score"));
        assertEquals("DATE", typeByName.get("hire"));
        assertEquals("TEXT", typeByName.get("memo"));
    }

    /** 重复表头应自动去重（a, a → a, a_1） */
    @Test
    void importFromText_duplicateHeaders_deduped() {
        String tableName = newTableName();
        String csv = "a,a,b\n1,2,3\n4,5,6";
        JSONObject res = importService.importFromText(csv, tableName, ",", null);

        List<String> names = res.getJSONArray("columns").stream()
                .map(o -> ((JSONObject) o).getString("name"))
                .collect(Collectors.toList());
        assertEquals(Arrays.asList("a", "a_1", "b"), names);
    }

    /** 空表头应兜底为 col1/col2…，物理名 sanitize 为空串时回落 tbl */
    @Test
    void importFromText_emptyHeader_fallsBackToColN() {
        String tableName = newTableName();
        String csv = ",姓名,年龄\n,张三,30\n,李四,25";
        JSONObject res = importService.importFromText(csv, tableName, ",", null);

        JSONObject first = res.getJSONArray("columns").getJSONObject(0);
        assertEquals("tbl", first.getString("name"));
        assertEquals("col1", first.getString("label"));
    }

    /** CSV 文件上传：中文表头同样应导入成功（与粘贴路径一致） */
    @Test
    void importFromFile_csv_chineseHeaders() {
        String tableName = newTableName();
        String content = "序号,姓名,金额\n1,张三,100.5\n2,李四,200\n";
        MockMultipartFile file = new MockMultipartFile("file", "emp.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
        JSONObject res = importService.importFromFile(file, tableName, null);

        assertEquals(2, res.getInteger("rowCount"));
        assertHasLabel(res.getJSONArray("columns"), "序号");
        assertHasLabel(res.getJSONArray("columns"), "姓名");
        assertHasLabel(res.getJSONArray("columns"), "金额");
    }

    /** ★ 原始 bug 场景：Excel(.xlsx) 含中文表头，修复前抛「列名非法」被拒，修复后应成功导入 */
    @Test
    void importFromFile_excel_chineseHeaders() throws Exception {
        String tableName = newTableName();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("s1");
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("序号");
            h.createCell(1).setCellValue("姓名");
            h.createCell(2).setCellValue("金额");
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue(1);
            r1.createCell(1).setCellValue("张三");
            r1.createCell(2).setCellValue(100.5);
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue(2);
            r2.createCell(1).setCellValue("李四");
            r2.createCell(2).setCellValue(200.0);
            wb.write(bos);
        }
        MockMultipartFile file = new MockMultipartFile("file", "员工.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bos.toByteArray());
        JSONObject res = importService.importFromFile(file, tableName, null);

        assertEquals(2, res.getInteger("rowCount"));
        assertHasLabel(res.getJSONArray("columns"), "序号");
        assertHasLabel(res.getJSONArray("columns"), "姓名");
        assertHasLabel(res.getJSONArray("columns"), "金额");

        String physical = res.getString("tableName");
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sandbox.\"" + physical + "\"", Integer.class);
        assertEquals(2, cnt);
    }

    /** 表名含中文应被 sanitize 成 ASCII 物理名，且表真实创建 */
    @Test
    void importFromText_chineseTableName_sanitized() {
        String csv = "id,name\n1,张三\n2,李四";
        JSONObject res = importService.importFromText(csv, "员工表", ",", null);
        createdTables.add(res.getString("tableName"));

        String physical = res.getString("tableName");
        assertTrue(physical.matches("[A-Za-z_][A-Za-z0-9_]*"), "物理表名应 ASCII：" + physical);
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sandbox.\"" + physical + "\"", Integer.class);
        assertEquals(2, cnt);
    }

    /** 已建表后追加数据，rowCount 应正确累加 */
    @Test
    void importDataIntoTable_appendIncreasesRowCount() {
        String tableName = newTableName();
        String csv = "id,name\n1,张三\n2,李四";
        JSONObject res = importService.importFromText(csv, tableName, ",", null);
        String physical = res.getString("tableName");

        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new HashMap<>();
        row.put("id", 3);
        row.put("name", "王五");
        rows.add(row);

        JSONObject append = importService.importDataIntoTable(physical, rows, null, "append", "test");
        assertEquals(3, append.getInteger("rowCount"));
        assertEquals(1, append.getInteger("inserted"));

        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sandbox.\"" + physical + "\"", Integer.class);
        assertEquals(3, cnt);
    }
}
