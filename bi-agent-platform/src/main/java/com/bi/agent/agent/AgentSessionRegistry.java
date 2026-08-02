package com.bi.agent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 会话注册表（内存级，会话维度）
 *
 * <p>BiAgentService 每启动一次对话就把 {@link AgentSession} 登记进来（key=sessionId），
 * 直到本次 SSE 流结束再移除；AgentChatController 的 {@code /api/agent/confirm} 端点据此定位
 * 正在运行的会话并唤醒其「写工具确认」的挂起 future。
 *
 * <p>仅用于「Agent 流式推理过程中等待用户确认」这一短暂窗口（秒级），无需持久化；
 * 会话结束自然清理即可。容器重启 / 会话断线则 future 超时自动拒绝，安全降级。
 */
@Component
public class AgentSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionRegistry.class);

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    public void register(AgentSession session) {
        if (session == null) {
            return;
        }
        sessions.put(session.getSessionId(), session);
        log.debug("Agent 会话已登记：{}（在线 {} 个）", session.getSessionId(), sessions.size());
    }

    public void unregister(String sessionId) {
        if (sessionId == null) {
            return;
        }
        sessions.remove(sessionId);
        log.debug("Agent 会话已移除：{}（在线 {} 个）", sessionId, sessions.size());
    }

    public AgentSession get(String sessionId) {
        return sessionId == null ? null : sessions.get(sessionId);
    }

    /** 是否存在该活跃会话（用于 /confirm 前校验，避免用户对一个不存在的会话点确认） */
    public boolean contains(String sessionId) {
        return sessionId != null && sessions.containsKey(sessionId);
    }
}
