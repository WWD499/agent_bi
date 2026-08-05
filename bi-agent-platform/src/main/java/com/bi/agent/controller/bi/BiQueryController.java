package com.bi.agent.controller.bi;

import cn.dev33.satoken.stp.StpUtil;
import com.bi.agent.bi.domain.BiQueryHistory;
import com.bi.agent.bi.service.BiQueryService;
import com.bi.agent.bi.service.IBiQueryHistoryService;
import com.bi.agent.bi.vo.BiQueryReq;
import com.bi.agent.bi.vo.QueryResultVo;
import com.bi.agent.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * BI 自然语言查询控制器（Phase 1 核心对外接口）。
 *
 * <p>鉴权：命中 Sa-Token 的 {@code /api/**} 规则，必须登录态（token 放
 * {@code Authorization} 头）。开发期临时验证端点（/api/dev/**）已在 Phase 1 收口时下线。
 *
 * <p>流程：入参校验 → BiQueryService.naturalLanguageQuery（选数据源 → 取表结构
 * → 构建 Prompt → 调 LLM 生成 SQL → 安全校验 → 执行 → 智能选图 → 数据解读）。
 *
 * @author agent-bi
 */
@RestController
@RequestMapping("/api/bi/query")
public class BiQueryController {

    private static final Logger log = LoggerFactory.getLogger(BiQueryController.class);

    @Autowired
    private BiQueryService biQueryService;

    @Autowired
    private IBiQueryHistoryService queryHistoryService;

    /**
     * 自然语言转 SQL 并取数 + 选图。
     *
     * @param req query（必填）、datasourceId（必填）、tableName（可选）
     * @return QueryResultVo：SQL / 列 / 数据 / 图表类型 / ECharts 配置 / 数据解读
     */
    @PostMapping("/nl2sql")
    public Result<QueryResultVo> nl2sql(@RequestBody BiQueryReq req) {
        if (req == null || req.getQuery() == null || req.getQuery().trim().isEmpty()) {
            return Result.fail(400, "query 不能为空");
        }
        if (req.getDatasourceId() == null) {
            return Result.fail(400, "datasourceId 不能为空");
        }

        String userId = String.valueOf(StpUtil.getLoginId());
        long t0 = System.currentTimeMillis();
        try {
            log.info("收到 NL2SQL 请求：dsId={}, query={}", req.getDatasourceId(), req.getQuery());
            QueryResultVo vo = biQueryService.naturalLanguageQuery(
                    req.getQuery().trim(),
                    req.getDatasourceId(),
                    req.getTableName());
            saveHistory(userId, req, vo.getSql(), vo.getRowCount(),
                    System.currentTimeMillis() - t0, "success", null);
            return Result.ok(vo);
        } catch (Exception e) {
            saveHistory(userId, req, null, 0,
                    System.currentTimeMillis() - t0, "failed", e.getMessage());
            throw e;
        }
    }

    /**
     * 落库查询历史（自身任何异常都绝不影响 NL2SQL 主流程）。
     */
    private void saveHistory(String userId, BiQueryReq req, String sql, Integer rowCount,
                             long durationMs, String status, String errorMsg) {
        try {
            BiQueryHistory h = new BiQueryHistory();
            h.setUserId(userId);
            h.setDatasourceId(req.getDatasourceId());
            h.setQuery(req.getQuery().trim());
            h.setSql(sql);
            h.setRowCount(rowCount);
            h.setDurationMs(durationMs);
            h.setStatus(status);
            if (errorMsg != null && errorMsg.length() > 1000) {
                errorMsg = errorMsg.substring(0, 1000);
            }
            h.setErrorMsg(errorMsg);
            queryHistoryService.save(h);
        } catch (Exception ex) {
            log.warn("查询历史落库失败（已忽略）", ex);
        }
    }
}
