package com.bi.agent.controller.agent;

import com.bi.agent.agent.BiAgentService;
import com.bi.agent.common.Result;
import cn.dev33.satoken.stp.StpUtil;
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

    private final BiAgentService agentService;

    public AgentChatController(BiAgentService agentService) {
        this.agentService = agentService;
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
        agentService.run(req.query(), req.sessionId(), userId, emitter);
        return emitter;
    }

    /**
     * 非流式探活端点（便于调试 / 健康检查）。真正的对话请走 {@code /chat}。
     */
    @PostMapping("/chat/sync")
    public Result<String> chatSync(@Valid @RequestBody ChatReq req) {
        return Result.ok("Agent 已切换为 SSE 流式端点 /api/agent/chat；sync 仅用于探活。query=" + req.query());
    }
}
