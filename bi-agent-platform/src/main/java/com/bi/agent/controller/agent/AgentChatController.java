package com.bi.agent.controller.agent;

import com.bi.agent.agent.BiAgentService;
import com.bi.agent.agent.AgentSession;
import com.bi.agent.agent.AgentSessionRegistry;
import com.bi.agent.common.Result;
import cn.dev33.satoken.stp.StpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 对话入口（Phase 2 · ★亮眼区）
 *
 * <p>{@code POST /api/agent/chat} 改为 SSE 流式：Agent 每调用一个工具、
 * 每生成一个答案片段，都以 SSE 事件实时推给前端（推理轨迹可见）。
 * 鉴权沿用 Sa-Token（/api/** 拦截），调用方需带登录后的 token。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentChatController {

    private static final Logger log = LoggerFactory.getLogger(AgentChatController.class);

    private final BiAgentService agentService;
    private final AgentSessionRegistry sessionRegistry;

    public AgentChatController(BiAgentService agentService, AgentSessionRegistry sessionRegistry) {
        this.agentService = agentService;
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * SSE 流式对话：立即返回 SseEmitter，真正的多步推理在异步线程中跑，
     * 结果经 SSE 推送（事件名：tool_call / tool_result / token / done / error）。
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatReq req) {
        // 0 表示不设超时，Agent 多步推理可能耗时 10~30s
        SseEmitter emitter = new SseEmitter(0L);
        String userId = String.valueOf(StpUtil.getLoginId());
        boolean allowWrite = Boolean.TRUE.equals(req.allowWrite());
        boolean skipConfirm = Boolean.TRUE.equals(req.skipConfirm());
        log.info("收到 Agent 对话请求：query={}, sessionId={}, datasourceId={}, allowWrite={}, skipConfirm={}",
            req.query(), req.sessionId(), req.datasourceId(), allowWrite, skipConfirm);
        agentService.run(req.query(), req.sessionId(), userId, req.datasourceId(), emitter, allowWrite, skipConfirm);
        return emitter;
    }

    /**
     * 非流式探活端点（便于调试 / 健康检查）。真正的对话请走 {@code /chat}。
     */
    @PostMapping("/chat/sync")
    public Result<String> chatSync(@Valid @RequestBody ChatReq req) {
        return Result.ok("Agent 已切换为 SSE 流式端点 /api/agent/chat；sync 仅用于探活。query=" + req.query());
    }

    /**
     * 写工具确认端点（M2）。前端在对话框弹出的确认框里点「同意 / 拒绝」后调用本端点，
     * 唤醒 Agent 推理中挂起的确认 future，使阻塞的 ReAct 循环继续执行或跳过。
     *
     * <p>body：{ sessionId: String, approved: boolean }
     */
    @PostMapping("/confirm")
    public Result<Boolean> confirm(@Valid @RequestBody ConfirmReq req) {
        AgentSession session = sessionRegistry.get(req.sessionId());
        if (session == null) {
            return Result.fail(404, "未找到该会话（可能已结束或连接已断开）");
        }
        if (!session.hasPendingConfirmation()) {
            return Result.fail(409, "该会话当前没有待确认的操作");
        }
        session.resolveConfirmation(Boolean.TRUE.equals(req.approved()));
        log.info("收到写工具确认：sessionId={}, approved={}", req.sessionId(), req.approved());
        return Result.ok(true);
    }

    /** 写工具确认请求体 */
    public record ConfirmReq(String sessionId, Boolean approved) {
    }
}
