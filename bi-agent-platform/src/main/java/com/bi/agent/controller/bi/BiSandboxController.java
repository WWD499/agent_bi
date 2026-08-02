package com.bi.agent.controller.bi;

import com.alibaba.fastjson2.JSONObject;
import java.util.List;
import java.util.Map;
import com.bi.agent.bi.domain.BiSandboxDb;
import com.bi.agent.bi.service.SandboxImportService;
import com.bi.agent.bi.service.SandboxQueryService;
import com.bi.agent.bi.service.SandboxAuditService;
import com.bi.agent.bi.vo.DbColumnVo;
import com.bi.agent.bi.vo.DbTableVo;
import com.bi.agent.bi.vo.QueryResultVo;
import com.bi.agent.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 数据沙箱控制器（M1）。
 *
 * <p>鉴权：命中 Sa-Token 的 /api/** 规则，需登录态（satoken 头）。
 *
 * <p>接口：
 * <ul>
 *   <li>POST /api/bi/sandbox/db                  新建沙箱库（逻辑命名空间）</li>
 *   <li>GET  /api/bi/sandbox/db                  列出全部沙箱库</li>
 *   <li>DELETE /api/bi/sandbox/db/{id}           删除沙箱库（级联删表）</li>
 *   <li>POST /api/bi/sandbox/import              粘贴文本导入（可指定 dbId）</li>
 *   <li>POST /api/bi/sandbox/import-datasource   从数据源批量导入（可指定 dbId）</li>
 *   <li>GET  /api/bi/sandbox/tables             列出沙箱表（?dbId= 按库过滤）</li>
 *   <li>GET  /api/bi/sandbox/tables/{name}/columns  列出某物理表字段</li>
 *   <li>GET  /api/bi/sandbox/tables/{name}/data     预览某物理表前 N 行</li>
 *   <li>POST /api/bi/sandbox/execute            在沙箱内执行只读 SQL</li>
 *   <li>DELETE /api/bi/sandbox/tables/{name}    删除沙箱物理表</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/bi/sandbox")
public class BiSandboxController {

    private static final Logger log = LoggerFactory.getLogger(BiSandboxController.class);
    private static final Pattern IDENT_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    @Autowired
    private SandboxQueryService sandboxQueryService;

    @Autowired
    private SandboxImportService sandboxImportService;

    @Autowired
    private SandboxAuditService sandboxAuditService;

    // ===== 沙箱库（逻辑命名空间） =====

    @PostMapping("/db")
    public Result<JSONObject> createDb(@RequestBody JSONObject body) {
        try {
            String name = body.getString("name");
            String dbKey = body.getString("dbKey");
            String remark = body.getString("remark");
            BiSandboxDb db = sandboxQueryService.createSandboxDb(name, dbKey, remark);
            JSONObject out = new JSONObject();
            out.put("id", db.getId());
            out.put("dbKey", db.getDbKey());
            out.put("name", db.getName());
            return Result.ok(out);
        } catch (Exception e) {
            log.warn("沙箱库创建失败：{}", e.getMessage());
            return Result.fail(400, e.getMessage());
        }
    }

    @GetMapping("/db")
    public Result<List<BiSandboxDb>> listDbs() {
        try {
            return Result.ok(sandboxQueryService.listSandboxDbs());
        } catch (Exception e) {
            log.warn("沙箱库列表查询失败：{}", e.getMessage());
            return Result.fail(500, e.getMessage());
        }
    }

    @DeleteMapping("/db/{id}")
    public Result<Void> dropDb(@PathVariable Long id) {
        try {
            sandboxImportService.dropSandboxDb(id);
            return Result.ok();
        } catch (Exception e) {
            log.warn("沙箱库删除失败：{}", e.getMessage());
            return Result.fail(400, e.getMessage());
        }
    }

    // ===== 表导入 =====

    @PostMapping("/import")
    public Result<JSONObject> importData(@RequestBody JSONObject body) {
        try {
            String rawText = body.getString("rawText");
            String tableName = body.getString("tableName");
            String separator = body.getString("separator");
            Long dbId = body.getLong("dbId");
            JSONObject res = sandboxImportService.importFromText(rawText, tableName, separator, dbId);
            return Result.ok(res);
        } catch (Exception e) {
            log.warn("沙箱导入失败：{}", e.getMessage());
            return Result.fail(400, e.getMessage());
        }
    }

    /**
     * 从已配置数据源批量导入表进沙箱（替代复制粘贴，前端勾选后一键导入）。
     * body：{ datasourceId: Long, tables: [源表名...], dbId: Long(可选) }
     */
    @PostMapping("/import-datasource")
    public Result<List<JSONObject>> importFromDatasource(@RequestBody ImportDsReq req) {
        try {
            List<JSONObject> res = sandboxImportService.importFromDatasource(req.getDatasourceId(), req.getTables(), req.getDbId());
            return Result.ok(res);
        } catch (Exception e) {
            log.warn("数据源导入沙箱失败：{}", e.getMessage());
            return Result.fail(400, e.getMessage());
        }
    }

    /**
     * 文件上传导入（M3）：支持 Excel(.xlsx/.xls) 与 CSV(.csv)。
     * 解析后自动推断列类型、建表写入沙箱，并登记审计。
     * form-data：file=文件, tableName=表短名, dbId=目标沙箱库id(可选)
     */
    @PostMapping(value = "/import-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<JSONObject> importFromFile(@org.springframework.web.bind.annotation.RequestParam("file") MultipartFile file,
                                             @org.springframework.web.bind.annotation.RequestParam("tableName") String tableName,
                                             @org.springframework.web.bind.annotation.RequestParam(value = "dbId", required = false) Long dbId) {
        try {
            JSONObject res = sandboxImportService.importFromFile(file, tableName, dbId);
            return Result.ok(res);
        } catch (Exception e) {
            log.warn("文件导入沙箱失败：{}", e.getMessage());
            return Result.fail(400, e.getMessage());
        }
    }

    /**
     * 沙箱操作审计列表（M3）：返回最近 N 条审计记录。
     * 参数：?limit=（默认 50，最大 500）
     */
    @GetMapping("/audit")
    public Result<List<Map<String, Object>>> listAudit(@RequestParam(defaultValue = "50") int limit) {
        try {
            return Result.ok(sandboxAuditService.listRecent(limit));
        } catch (Exception e) {
            log.warn("沙箱审计查询失败：{}", e.getMessage());
            return Result.fail(500, e.getMessage());
        }
    }

    /** 数据源批量导入请求体 */
    public static class ImportDsReq {
        private Long datasourceId;
        private List<String> tables;
        private Long dbId;

        public Long getDatasourceId() {
            return datasourceId;
        }

        public void setDatasourceId(Long datasourceId) {
            this.datasourceId = datasourceId;
        }

        public List<String> getTables() {
            return tables;
        }

        public void setTables(List<String> tables) {
            this.tables = tables;
        }

        public Long getDbId() {
            return dbId;
        }

        public void setDbId(Long dbId) {
            this.dbId = dbId;
        }
    }

    @GetMapping("/tables")
    public Result<List<DbTableVo>> listTables(@RequestParam(required = false) Long dbId) {
        try {
            return Result.ok(sandboxQueryService.listSandboxTables(dbId));
        } catch (Exception e) {
            log.warn("沙箱列表查询失败：{}", e.getMessage());
            return Result.fail(500, e.getMessage());
        }
    }

    @GetMapping("/tables/{name}/columns")
    public Result<List<DbColumnVo>> listColumns(@PathVariable String name) {
        if (name == null || !IDENT_PATTERN.matcher(name).matches()) {
            return Result.fail(400, "非法物理表名：" + name);
        }
        try {
            return Result.ok(sandboxQueryService.listSandboxColumns(name));
        } catch (Exception e) {
            log.warn("沙箱列查询失败：{}", e.getMessage());
            return Result.fail(500, e.getMessage());
        }
    }

    @GetMapping("/tables/{name}/data")
    public Result<QueryResultVo> previewData(@PathVariable String name,
                                             @RequestParam(defaultValue = "100") int limit) {
        if (name == null || !IDENT_PATTERN.matcher(name).matches()) {
            return Result.fail(400, "非法物理表名：" + name);
        }
        if (limit <= 0 || limit > 1000) {
            limit = 100;
        }
        try {
            List<DbColumnVo> columns = sandboxQueryService.listSandboxColumns(name);
            String orderCol = resolveDefaultOrderColumn(columns);
            String sql = "SELECT * FROM " + SandboxQueryService.SANDBOX_SCHEMA + ".\"" + name + "\"";
            if (orderCol != null) {
                sql += " ORDER BY \"" + orderCol + "\"";
            }
            sql += " LIMIT " + limit;
            return Result.ok(sandboxQueryService.runSandboxReadOnlySql(sql));
        } catch (Exception e) {
            log.warn("沙箱数据预览失败：{}", e.getMessage());
            return Result.fail(500, e.getMessage());
        }
    }

    /**
     * 为沙箱数据预览选择默认排序列。规则：
     * <ol>
     *   <li>优先精确匹配名为 {@code id} 的列（不区分大小写）；</li>
     *   <li>其次匹配以 {@code _id} 结尾的列（如 product_id，不区分大小写）；</li>
     *   <li>都没有则回退到第一列，保证返回顺序稳定；</li>
     *   <li>空表/无列时不排序。</li>
     * </ol>
     */
    private String resolveDefaultOrderColumn(List<DbColumnVo> columns) {
        if (columns == null || columns.isEmpty()) {
            return null;
        }
        String idCol = null;
        String suffixIdCol = null;
        String firstCol = null;
        for (DbColumnVo c : columns) {
            String col = c.getColumnName();
            if (col == null) {
                continue;
            }
            if (firstCol == null) {
                firstCol = col;
            }
            String lower = col.toLowerCase();
            if ("id".equals(lower)) {
                idCol = col;
                break;
            }
            if (lower.endsWith("_id") && suffixIdCol == null) {
                suffixIdCol = col;
            }
        }
        if (idCol != null) {
            return idCol;
        }
        if (suffixIdCol != null) {
            return suffixIdCol;
        }
        return firstCol;
    }

    @PostMapping("/execute")
    public Result<QueryResultVo> execute(@RequestBody JSONObject body) {
        try {
            String sql = body.getString("sql");
            return Result.ok(sandboxQueryService.runSandboxReadOnlySql(sql));
        } catch (Exception e) {
            log.warn("沙箱 SQL 执行失败：{}", e.getMessage());
            return Result.fail(400, e.getMessage());
        }
    }

    @DeleteMapping("/tables/{name}")
    public Result<Void> drop(@PathVariable String name) {
        if (name == null || !IDENT_PATTERN.matcher(name).matches()) {
            return Result.fail(400, "非法物理表名：" + name);
        }
        try {
            sandboxImportService.dropSandboxTable(name);
            return Result.ok();
        } catch (Exception e) {
            log.warn("沙箱表删除失败：{}", e.getMessage());
            return Result.fail(400, e.getMessage());
        }
    }

    /**
     * 修改沙箱表的用户显示名（如 部门表 / 员工表）。仅改元数据 display_name，不影响物理表名。
     * body：{ displayName: "员工表" }
     */
    @PostMapping("/tables/{name}/display-name")
    public Result<Void> updateDisplayName(@PathVariable String name, @RequestBody JSONObject body) {
        if (name == null || !IDENT_PATTERN.matcher(name).matches()) {
            return Result.fail(400, "非法物理表名：" + name);
        }
        try {
            String displayName = body == null ? null : body.getString("displayName");
            sandboxQueryService.renameSandboxTableDisplay(name, displayName);
            return Result.ok();
        } catch (Exception e) {
            log.warn("沙箱表显示名修改失败：{}", e.getMessage());
            return Result.fail(400, e.getMessage());
        }
    }
}
