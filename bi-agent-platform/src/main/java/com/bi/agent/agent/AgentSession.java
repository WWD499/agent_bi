package com.bi.agent.agent;

import com.alibaba.fastjson2.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 单次会话上下文
 *
 * <p>持有 SSE 发射器（{@link SseEmitter}）与会话 ID，并提供一组
 * 语义化发射方法（tool_call / tool_result / token / reasoning / done / error），
 * 供 Agent 编排器与各个 {@code @Tool} 工具在推理过程中向客户端实时推送事件。
 *
 * <p>工具调用的步数上限在本类内强制：超过 {@link #MAX_TOOL_CALLS} 后
 * {@link #canCallMoreTools()} 返回 false，工具侧据此拒绝继续调用，防止模型死循环。
 */
public class AgentSession {

    private static final Logger log = LoggerFactory.getLogger(AgentSession.class);

    /** 单轮对话允许的最大工具调用次数（防死循环 / 防资源耗尽） */
    private static final int MAX_TOOL_CALLS = 8;

    private final String sessionId;
    private final SseEmitter emitter;
    private int toolCallCount = 0;

    public AgentSession(String sessionId, SseEmitter emitter) {
        this.sessionId = sessionId;
        this.emitter = emitter;
    }

    public String getSessionId() {
        return sessionId;
    }

    /** 是否已用尽本轮工具调用额度 */
    public boolean canCallMoreTools() {
        return toolCallCount < MAX_TOOL_CALLS;
    }

    /** 记录一次工具调用（在工具真正执行前调用） */
    public void markToolCalled() {
        toolCallCount++;
    }

    public void emitToolCall(String tool, String args) {
        send("tool_call", Map.of("tool", tool, "args", safe(args)));
    }

    public void emitToolResult(String tool, String result) {
        send("tool_result", Map.of("tool", tool, "result", safe(result)));
    }

    public void emitReasoning(String text) {
        send("reasoning", safe(text));
    }

    public void emitToken(String token) {
        send("token", safe(token));
    }

    public void emitDone() {
        send("done", "");
    }

    public void emitCharts(List<Map<String, Object>> charts) {
        send("charts", charts == null ? new ArrayList<>() : charts);
    }

    public void emitError(String message) {
        send("error", safe(message));
    }

    private void send(String event, Object data) {
        try {
            String payload = (data instanceof String s) ? s : JSON.toJSONString(data);
            emitter.send(SseEmitter.event().name(event).data(payload));
        } catch (Exception e) {
            // 客户端可能已断开；吞掉异常，不阻断 Agent 主流程
            log.debug("SSE 事件发送失败（客户端可能已断开）：event={}", event, e);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
