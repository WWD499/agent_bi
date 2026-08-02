package com.bi.agent.agent;

import com.alibaba.fastjson2.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Agent 单次会话上下文
 *
 * <p>持有 SSE 发射器（{@link SseEmitter}）与会话 ID，并提供一组
 * 语义化发射方法（tool_call / tool_result / token / reasoning / done / error / confirm），
 * 供 Agent 编排器与各个 {@code @Tool} 工具在推理过程中向客户端实时推送事件。
 *
 * <p>工具调用的步数上限在本类内强制：超过 {@link #MAX_TOOL_CALLS} 后
 * {@link #canCallMoreTools()} 返回 false，工具侧据此拒绝继续调用，防止模型死循环。
 *
 * <p>M2 新增「写工具确认状态机」：当 Agent 准备执行一个 {@code requiresConfirmation} 的工具
 * （如建表 / 落表 / 删表）时，会调用 {@link #requestConfirmation(Map)}，本类会推一个
 * {@code confirm} 事件给前端并挂起当前推理，返回一个 {@link CompletableFuture<Boolean>}
 * 供编排器阻塞等待；用户在前端点「同意 / 拒绝」后由 {@link #resolveConfirmation(boolean)}
 * 唤醒。整个机制是纯内存的（会话级），超时（{@link #CONFIRM_TIMEOUT_SECONDS}）自动视为拒绝。
 */
public class AgentSession {

    private static final Logger log = LoggerFactory.getLogger(AgentSession.class);

    /** 单轮对话允许的最大工具调用次数（防死循环 / 防资源耗尽） */
    private static final int MAX_TOOL_CALLS = 8;

    /** 等待用户确认的最长时间（秒）；超时未响应则自动拒绝该写操作 */
    public static final long CONFIRM_TIMEOUT_SECONDS = 120;

    /** 会话当前状态 */
    public enum Status {
        RUNNING,
        AWAITING_CONFIRM
    }

    private final String sessionId;
    private final SseEmitter emitter;
    private int toolCallCount = 0;

    /** 当前是否处于「等待用户确认」状态 */
    private volatile Status status = Status.RUNNING;
    /** 待确认的操作描述（推给前端渲染确认对话框用） */
    private volatile Map<String, Object> pendingConfirmation;
    /** 用户确认结果（同意=true / 拒绝=false）；由 /confirm 端点唤醒 */
    private volatile CompletableFuture<Boolean> confirmFuture;

    public AgentSession(String sessionId, SseEmitter emitter) {
        this.sessionId = sessionId;
        this.emitter = emitter;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isAwaitingConfirm() {
        return status == Status.AWAITING_CONFIRM;
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

    /**
     * 推送「需要用户确认」事件并挂起当前推理。
     *
     * <p>返回 {@link CompletableFuture<Boolean>}，编排器应 {@code get(timeout)} 阻塞等待用户决策；
     * 用户在 {@code /api/agent/confirm} 端点调用 {@link #resolveConfirmation(boolean)} 后该 future 完成。
     *
     * @param payload 待确认操作的描述（含 tool / 摘要 / 风险说明等，前端据此渲染确认框）
     * @return 确认结果 future（true=同意，false=拒绝或超时）
     */
    public CompletableFuture<Boolean> requestConfirmation(Map<String, Object> payload) {
        this.pendingConfirmation = payload;
        this.confirmFuture = new CompletableFuture<>();
        this.status = Status.AWAITING_CONFIRM;
        emitConfirm(payload);
        return confirmFuture;
    }

    /** 用户在前端做出决策后由 /confirm 端点调用：唤醒挂起的确认 future */
    public void resolveConfirmation(boolean approved) {
        if (confirmFuture != null && !confirmFuture.isDone()) {
            confirmFuture.complete(approved);
        }
        this.status = Status.RUNNING;
        this.pendingConfirmation = null;
    }

    /** 是否仍有未决的确认（供 /confirm 端点判定会话是否存在） */
    public boolean hasPendingConfirmation() {
        return confirmFuture != null && !confirmFuture.isDone();
    }

    public Map<String, Object> getPendingConfirmation() {
        return pendingConfirmation;
    }

    /** 阻塞等待用户确认结果（带超时兜底，超时返回 false=拒绝） */
    public boolean awaitConfirmation(CompletableFuture<Boolean> future) {
        try {
            return Boolean.TRUE.equals(future.get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (Exception e) {
            log.warn("等待用户确认超时或被中断 sessionId={}", sessionId);
            // 超时未确认：视为拒绝，并主动唤醒以防 future 泄漏
            future.complete(false);
            return false;
        }
    }

    private void emitConfirm(Map<String, Object> payload) {
        send("confirm", payload == null ? new java.util.HashMap<String, Object>() : payload);
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
