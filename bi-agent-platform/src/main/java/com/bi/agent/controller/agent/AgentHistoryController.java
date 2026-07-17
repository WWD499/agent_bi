package com.bi.agent.controller.agent;

import cn.dev33.satoken.stp.StpUtil;
import com.bi.agent.agent.AgentMemory;
import com.bi.agent.common.Result;
import com.bi.agent.vo.PageResult;
import com.bi.agent.vo.SessionDetailVo;
import com.bi.agent.vo.SessionSummaryVo;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 历史记录接口。
 *
 * <p>userId 仅在 Controller 层由 Sa-Token 取出，绝不接收客户端传入、不向工具层渗透，
 * 保证用户隔离与越权防护。
 */
@RestController
@RequestMapping("/api/agent/history")
public class AgentHistoryController {

    private final AgentMemory agentMemory;

    public AgentHistoryController(AgentMemory agentMemory) {
        this.agentMemory = agentMemory;
    }

    /** 当前用户会话列表（倒序分页） */
    @GetMapping("/list")
    public Result<PageResult<SessionSummaryVo>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String userId = String.valueOf(StpUtil.getLoginId());
        return Result.ok(agentMemory.listSessions(userId, page, size));
    }

    /** 单个会话详情（消息流）；无历史也返回 200 + 空 messages */
    @GetMapping("/{sid}")
    public Result<SessionDetailVo> detail(@PathVariable String sid) {
        String userId = String.valueOf(StpUtil.getLoginId());
        return Result.ok(agentMemory.getSession(userId, sid));
    }

    /** 删除单条会话 */
    @DeleteMapping("/{sid}")
    public Result<Void> delete(@PathVariable String sid) {
        String userId = String.valueOf(StpUtil.getLoginId());
        agentMemory.clear(userId, sid);
        return Result.ok();
    }

    /** 清空当前用户全部会话 */
    @DeleteMapping("/clear")
    public Result<Void> clearAll() {
        String userId = String.valueOf(StpUtil.getLoginId());
        agentMemory.clearAll(userId);
        return Result.ok();
    }
}
