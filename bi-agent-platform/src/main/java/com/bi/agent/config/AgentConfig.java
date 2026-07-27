package com.bi.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Agent 层配置
 *
 * <p>仅提供一个虚拟线程执行器（Java 21 {@link Executors#newVirtualThreadPerTaskExecutor()}）。
 * {@code /api/agent/chat} 的 Servlet 线程立即返回 {@code SseEmitter}，真正的
 * 多步 ReAct 推理 + SSE 推送在虚拟线程中异步进行，避免阻塞 Web 线程。
 */
@Configuration
public class AgentConfig {

    @Bean(name = "agentTaskExecutor")
    public Executor agentTaskExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
