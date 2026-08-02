package com.bi.agent.agent.tool;

/**
 * Agent 工具统一接口（手写 ReAct 工具抽象，不依赖 Spring AI）
 *
 * <p>每个工具提供：名字（与模型 {@code tool_calls.name} 对应）、给模型看的自然语言
 * 描述、OpenAI 格式的参数 JSON Schema，以及真正的执行逻辑
 * {@link #call(String)}（入参为模型传来的参数 JSON 字符串，返回结果 JSON 字符串）。
 *
 * <p>工具不持有会话上下文——事件发射（tool_call / tool_result）统一由
 * {@link com.bi.agent.agent.BiAgentService} 在调用前后完成，职责更清晰。
 */
public interface AgentTool {

    /** 工具名（英文、无空格，与模型 function-calling 的 name 对应） */
    String name();

    /** 给模型看的自然语言描述（决定模型何时调用） */
    String description();

    /** OpenAI 格式的参数 JSON Schema（parameters 对象，可含 properties / required） */
    String jsonSchema();

    /**
     * 执行工具
     *
     * @param argsJson 模型传来的参数 JSON 字符串（如 {@code {"datasourceId":1}}）
     * @return 结果 JSON 字符串（供模型回填上下文）
     */
    String call(String argsJson);

    /**
     * 是否需要「用户确认」后才执行（M2 写工具用）。
     *
     * <p>返回 true 的工具属于「危险操作」（如建表/落表/删表）：Agent 在真正调用
     * {@link #call(String)} 之前会先经 SSE 推一个 confirm 事件给前端，阻塞等待用户
     * 在对话框里「同意 / 拒绝」；用户拒绝则工具不执行、直接把拒绝结果回填上下文。
     *
     * <p>默认 false：历史只读工具无需确认，照旧直接执行。
     */
    default boolean requiresConfirmation() {
        return false;
    }
}
