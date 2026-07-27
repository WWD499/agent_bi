package com.bi.agent.controller.bi;

import com.bi.agent.bi.service.BiQueryService;
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

        log.info("收到 NL2SQL 请求：dsId={}, query={}", req.getDatasourceId(), req.getQuery());
        QueryResultVo vo = biQueryService.naturalLanguageQuery(
                req.getQuery().trim(),
                req.getDatasourceId(),
                req.getTableName());
        return Result.ok(vo);
    }
}
