package com.bi.agent.bi.service;

import com.alibaba.fastjson2.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 数据沙箱审计留痕服务（M3）。
 *
 * <p>所有会改变沙箱物理数据 / 结构的操作（粘贴导入、数据源导入、文件上传导入、
 * Agent 写工具建表 / 落表 / 删表、删库）均在此留痕，落 {@code bi_sandbox_audit} 表，
 * 便于事后追溯「谁·在何时·对哪张表·做了什么·是否成功」。
 *
 * <p>审计本身是「旁路」：任何审计写入异常都不应阻断主业务流程（仅记录日志），
 * 故所有写入包在 try/catch 内，失败只 warn 不抛。
 */
@Service
public class SandboxAuditService {

    private static final Logger log = LoggerFactory.getLogger(SandboxAuditService.class);

    /** 操作类型常量 */
    public static final String OP_IMPORT_TEXT = "IMPORT_TEXT";
    public static final String OP_IMPORT_DATASOURCE = "IMPORT_DATASOURCE";
    public static final String OP_IMPORT_FILE = "IMPORT_FILE";
    public static final String OP_CREATE_TABLE = "CREATE_TABLE";
    public static final String OP_MATERIALIZE = "MATERIALIZE";
    public static final String OP_DROP_TABLE = "DROP_TABLE";
    public static final String OP_DROP_DB = "DROP_DB";
    public static final String OP_IMPORT_DATA = "IMPORT_DATA";

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public SandboxAuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 记录一条审计。
     *
     * @param operation 操作类型（用本类 OP_* 常量）
     * @param target    操作对象（物理表名 / 源表名 / 库名等）
     * @param operator  操作人（Sa-Token 登录 id；未知时传 "anonymous"）
     * @param detail    结构化详情（JSON 字符串，如列定义、行数、来源等）
     * @param success   是否成功
     * @param failReason 失败原因（成功传 null）
     */
    public void log(String operation, String target, String operator, String detail,
                    boolean success, String failReason) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO bi_sandbox_audit (operator, operation, target, detail, success, fail_reason, create_time) "
                            + "VALUES (?, ?, ?, ?, ?, ?, NOW())",
                    operator == null ? "anonymous" : operator,
                    operation,
                    target == null ? "" : target,
                    detail == null ? "" : detail,
                    success ? 1 : 0,
                    failReason == null ? "" : failReason);
        } catch (Exception e) {
            // 审计失败绝不影响主流程
            log.warn("沙箱审计写入失败：op={}, target={}", operation, target, e);
        }
    }

    /** 便捷：成功记录（自动构造 JSON detail） */
    public void logSuccess(String operation, String target, String operator, Map<String, Object> detail) {
        log(operation, target, operator, detail == null ? null : JSON.toJSONString(detail), true, null);
    }

    /** 便捷：失败记录 */
    public void logFailure(String operation, String target, String operator, String failReason) {
        log(operation, target, operator, null, false, failReason);
    }

    /**
     * 查询最近的审计记录（供前端审计页展示）。
     *
     * @param limit 返回条数上限
     * @return 每行一个 Map（id / operator / operation / target / detail / success / failReason / createTime）
     */
    public List<Map<String, Object>> listRecent(int limit) {
        int n = Math.max(1, Math.min(limit, 500));
        try {
            return jdbcTemplate.queryForList(
                    "SELECT id, operator, operation, target, detail, success, fail_reason, "
                            + "to_char(create_time, 'YYYY-MM-DD HH24:MI:SS') AS create_time "
                            + "FROM bi_sandbox_audit ORDER BY id DESC LIMIT " + n);
        } catch (Exception e) {
            log.warn("沙箱审计查询失败", e);
            return java.util.Collections.emptyList();
        }
    }
}
