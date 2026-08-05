package com.bi.agent.controller.bi;

import cn.dev33.satoken.stp.StpUtil;
import com.bi.agent.bi.domain.BiNotify;
import com.bi.agent.bi.service.IBiNotifyService;
import com.bi.agent.common.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 站内信 / 用户通知控制器（/api/bi/notify）。
 *
 * <p>鉴权：命中 Sa-Token 的 /api/** 规则，需登录态；所有读写均按当前登录用户隔离
 * （userId 取自 StpUtil.getLoginId()，绝不接收客户端传入），删除/标记已读额外校验归属。
 */
@RestController
@RequestMapping("/api/bi/notify")
public class BiNotifyController {

    @Autowired
    private IBiNotifyService notifyService;

    @GetMapping("/list")
    public Result<List<BiNotify>> list(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        String userId = String.valueOf(StpUtil.getLoginId());
        return Result.ok(notifyService.list(userId, unreadOnly, page, size));
    }

    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        String userId = String.valueOf(StpUtil.getLoginId());
        return Result.ok(notifyService.unreadCount(userId));
    }

    @GetMapping("/{id}")
    public Result<BiNotify> get(@PathVariable Long id) {
        return Result.ok(notifyService.getById(id));
    }

    @PostMapping("/{id}/read")
    public Result<Integer> markRead(@PathVariable Long id) {
        String userId = String.valueOf(StpUtil.getLoginId());
        return Result.ok(notifyService.markRead(id, userId));
    }

    @PostMapping("/read-all")
    public Result<Integer> markAllRead() {
        String userId = String.valueOf(StpUtil.getLoginId());
        return Result.ok(notifyService.markAllRead(userId));
    }

    /**
     * 删除（单条或批量）。
     * 路径变量绑定为 {@code List<Long>}：单条路径 /5 自动包成 [5]，批量 /1,2,3 包成 [1,2,3]，
     * 与前端 http.delete(`${BASE}/${ids.join(',')}`) 完全兼容。
     */
    @DeleteMapping("/{ids}")
    public Result<Integer> delete(@PathVariable List<Long> ids) {
        String userId = String.valueOf(StpUtil.getLoginId());
        return Result.ok(notifyService.deleteByIds(userId, ids));
    }
}
