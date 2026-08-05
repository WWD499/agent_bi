package com.bi.agent.controller.bi;

import cn.dev33.satoken.stp.StpUtil;
import com.bi.agent.bi.domain.BiQueryHistory;
import com.bi.agent.bi.service.IBiQueryHistoryService;
import com.bi.agent.common.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * NL2SQL 查询历史控制器（/api/bi/query/history）。
 *
 * <p>鉴权：命中 Sa-Token 的 /api/** 规则，需登录态；列表/删除均按当前登录用户隔离
 * （userId 取自 StpUtil.getLoginId()，绝不接收客户端传入）。
 */
@RestController
@RequestMapping("/api/bi/query/history")
public class BiQueryHistoryController {

    @Autowired
    private IBiQueryHistoryService queryHistoryService;

    @GetMapping("/list")
    public Result<List<BiQueryHistory>> list(@RequestParam(required = false) Long datasourceId,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        String userId = String.valueOf(StpUtil.getLoginId());
        return Result.ok(queryHistoryService.list(userId, datasourceId, keyword, page, size));
    }

    @GetMapping("/{id}")
    public Result<BiQueryHistory> get(@PathVariable Long id) {
        return Result.ok(queryHistoryService.getById(id));
    }

    /**
     * 删除（单条或批量）。
     * 路径变量绑定为 {@code List<Long>}：单条路径 /5 自动包成 [5]，批量 /1,2,3 包成 [1,2,3]，
     * 与前端 http.delete(`${BASE}/${ids.join(',')}`) 完全兼容。
     */
    @DeleteMapping("/{ids}")
    public Result<Integer> delete(@PathVariable List<Long> ids) {
        return Result.ok(queryHistoryService.deleteByIds(ids));
    }
}
