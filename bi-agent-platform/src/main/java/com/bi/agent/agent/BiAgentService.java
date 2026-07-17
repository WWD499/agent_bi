package com.bi.agent.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.agent.tool.AnalyzeAlertTool;
import com.bi.agent.agent.tool.AgentTool;
import com.bi.agent.agent.tool.ListColumnsTool;
import com.bi.agent.agent.tool.ListTablesTool;
import com.bi.agent.agent.tool.Nl2SqlTool;
import com.bi.agent.agent.tool.RagSearchTool;
import com.bi.agent.agent.tool.RunSqlTool;
import com.bi.agent.agent.tool.SelectChartTool;
import com.bi.agent.bi.service.BiQueryService;
import com.bi.agent.bi.service.IBiAlertRuleService;
import com.bi.agent.bi.service.IBiDatasourceService;
import com.bi.agent.bi.service.IBiKnowledgeService;
import com.bi.agent.bi.service.llm.LlmService;
import com.bi.agent.bi.service.sql.ChartSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * BI 智能体编排服务（Phase 2 核心 · 手写 ReAct）
 *
 * <p>不依赖 Spring AI：直接基于已验证的 {@link LlmService}（OpenAI 兼容网关 / deepseek-v3，
 * 原生 tools 函数调用 + SSE 流式）实现 ReAct 多步推理——
 * 自主规划 → 调用工具（list_tables / list_columns / nl2sql / run_sql / rag_search /
 * select_chart / analyze_alert）→ 回填结果 → 循环至最终答案，过程中通过 {@link AgentSession}
 * 把「推理思考 / 调用了什么工具 / 工具返回了什么 / 逐字答案」以 SSE 事件实时推给前端，
 * 形成可见的「推理轨迹」。
 *
 * <p>安全边界（PLAN.md §6）：工具全部只读；{@link AgentSession} 强制单轮
 * 工具调用上限（默认 8 次），杜绝模型死循环 / 资源耗尽。
 *
 * <p>为何手写而非 Spring AI：本机 Maven 仓库中 Spring AI 1.0.0 GA 构件模块拆分异常，
 * 核心类（ChatClient / ToolCallback / AssistantMessage）缺失，属 PLAN.md §7 已预警风险，
 * 故采用零新依赖的手写方案，对工具调用协议有完全掌控（面试更显功底）。
 */
@Service
public class BiAgentService {

    private static final Logger log = LoggerFactory.getLogger(BiAgentService.class);

    /** 单轮最大工具调用步数（与 AgentSession 内的上限保持一致） */
    private static final int MAX_STEPS = 8;

    private final LlmService llmService;
    private final AgentMemory memory;
    private final Executor agentTaskExecutor;
    private final List<AgentTool> tools;

    public BiAgentService(LlmService llmService,
                         AgentMemory memory,
                         @Qualifier("agentTaskExecutor") Executor agentTaskExecutor,
                         IBiDatasourceService datasourceService,
                         BiQueryService queryService,
                         IBiKnowledgeService knowledgeService,
                         IBiAlertRuleService alertRuleService,
                         ChartSelector chartSelector) {
        this.llmService = llmService;
        this.memory = memory;
        this.agentTaskExecutor = agentTaskExecutor;
        // 在编排器内组装 7 个工具（工具依赖的 service 均为 Spring Bean）
        this.tools = new ArrayList<>();
        this.tools.add(new ListTablesTool(datasourceService));
        this.tools.add(new ListColumnsTool(datasourceService));
        this.tools.add(new Nl2SqlTool(queryService));
        this.tools.add(new RunSqlTool(queryService));
        this.tools.add(new RagSearchTool(knowledgeService));
        this.tools.add(new SelectChartTool(queryService, chartSelector));
        this.tools.add(new AnalyzeAlertTool(alertRuleService));
    }

    /**
     * 入口：立即返回（Servlet 线程不阻塞），真正的推理在虚拟线程中异步跑，
     * 结果经 SSE 推送给前端。
     */
    public void run(String query, String sessionId, String userId, SseEmitter emitter) {
        agentTaskExecutor.execute(() -> doRun(query, sessionId, userId, emitter));
    }

    private void doRun(String query, String sessionId, String userId, SseEmitter emitter) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }
        AgentSession session = new AgentSession(sessionId, emitter);
        try {
            // 1. 组装对话历史（含记忆回填 + 本轮 user）
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", buildSystemPrompt()));
            for (Map<String, Object> h : memory.get(userId, sessionId)) {
                messages.add(h);
            }
            messages.add(Map.of("role", "user", "content", query));

            // 2. 手写 ReAct 循环（含工具调用 + 推理轨迹）
            String finalAnswer = runReactLoop(messages, session);

            // 3. 逐字（分片）流式回传最终答案，营造流式效果
            streamAnswer(finalAnswer, session);

            // 4. 记忆落盘（本轮 user + 最终 assistant）
            memory.add(userId, sessionId,
                    Map.of("role", "user", "content", query),
                    Map.of("role", "assistant", "content", finalAnswer));

            session.emitDone();
            emitter.complete();
        } catch (Exception e) {
            log.error("Agent 执行异常", e);
            try {
                session.emitError("Agent 执行异常：" + e.getMessage());
                emitter.complete();
            } catch (Exception ignore) {
                // 客户端可能已断开
            }
        }
    }

    /**
     * 手写 ReAct 工具调用循环。
     * 每轮让模型在「带 tools」上下文下决策：
     * - 返回 tool_calls → 逐个执行工具、回填 tool 角色消息、继续循环；
     * - 返回纯文本（无 tool_calls）→ 即为最终答案，退出循环。
     * 步数超过 {@link #MAX_STEPS} 仍无定论时，强制再做一次无工具的总结调用。
     */
    private String runReactLoop(List<Map<String, Object>> messages, AgentSession session) {
        String toolsJson = buildToolsJson();
        int steps = 0;
        String finalAnswer = null;

        while (steps < MAX_STEPS) {
            String raw = llmService.chatRaw(messages, toolsJson);
            JSONObject msg = parseAssistantMessage(raw);
            if (msg == null) {
                return "（模型返回异常，未能解析出结果）";
            }
            // 把 assistant 这轮消息原样回填（含可能的 tool_calls）
            messages.add(msg);

            JSONArray toolCalls = msg.getJSONArray("tool_calls");
            String content = msg.getString("content");

            // 中间思考（有 tool_calls 时 content 通常是推理过程）→ 作为 reasoning 事件推送
            if (content != null && !content.isBlank()
                    && toolCalls != null && !toolCalls.isEmpty()) {
                session.emitReasoning(content);
            }

            if (toolCalls == null || toolCalls.isEmpty()) {
                finalAnswer = (content == null) ? "" : content;
                break;
            }

            // 有工具调用：逐个执行
            steps++;
            for (int i = 0; i < toolCalls.size(); i++) {
                JSONObject tc = toolCalls.getJSONObject(i);
                String id = tc.getString("id");
                JSONObject fn = tc.getJSONObject("function");
                String name = fn.getString("name");
                String args = fn.getString("arguments");
                if (args == null) {
                    args = "{}";
                }

                session.emitToolCall(name, args);
                AgentTool tool = findTool(name);
                String result;
                if (tool == null) {
                    result = "未知工具：" + name;
                } else {
                    try {
                        result = tool.call(args);
                    } catch (Exception ex) {
                        result = "工具执行异常：" + ex.getMessage();
                    }
                }
                session.emitToolResult(name, result);

                // 回填 tool 角色消息（OpenAI 格式）
                Map<String, Object> toolMsg = new HashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", id);
                toolMsg.put("content", result);
                messages.add(toolMsg);
            }
        }

        if (finalAnswer == null) {
            // 步数耗尽仍未定论：做一次无工具的总结调用兜底
            String raw = llmService.chatRaw(messages, null);
            JSONObject msg = parseAssistantMessage(raw);
            finalAnswer = (msg != null && msg.getString("content") != null)
                    ? msg.getString("content")
                    : "（未能在限定步数内得出最终结论，请尝试拆分问题或补充信息。）";
        }
        return finalAnswer;
    }

    /** 从 chatRaw 的网关返回里解析出 choices[0].message（assistant 消息） */
    private JSONObject parseAssistantMessage(String raw) {
        try {
            JSONObject root = JSON.parseObject(raw);
            JSONArray choices = root.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            return choices.getJSONObject(0).getJSONObject("message");
        } catch (Exception e) {
            log.error("解析模型返回失败：{}", raw, e);
            return null;
        }
    }

    private AgentTool findTool(String name) {
        for (AgentTool t : tools) {
            if (t.name().equals(name)) {
                return t;
            }
        }
        return null;
    }

    /** 把 7 个工具定义成 OpenAI 格式的 tools JSON 字符串 */
    private String buildToolsJson() {
        JSONArray arr = new JSONArray();
        for (AgentTool t : tools) {
            JSONObject fn = new JSONObject();
            fn.put("name", t.name());
            fn.put("description", t.description());
            // 参数 JSON Schema：确保是 object 类型
            JSONObject params;
            try {
                params = JSON.parseObject(t.jsonSchema());
            } catch (Exception e) {
                params = new JSONObject().fluentPut("type", "object").fluentPut("properties", new JSONObject());
            }
            if (!params.containsKey("type")) {
                params.put("type", "object");
            }
            fn.put("parameters", params);

            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("function", fn);
            arr.add(tool);
        }
        return arr.toJSONString();
    }

    /** 系统提示词：角色 + 工具使用指引 + 只读安全约束 */
    private String buildSystemPrompt() {
        return """
                你是一个专业的 BI 数据分析智能体（Agent）。你可以自主规划、调用工具，逐步完成用户的复杂数据分析请求，并在最后用简洁专业的中文给出结论。

                可用工具（你应当主动组合使用）：
                - list_tables：列出某数据源的所有表（含注释）。写 SQL 前先「看一眼」库里有哪些表，避免编造表名。
                - list_columns：列出某张表的所有字段（列名、类型、注释）。拼 SQL 前确认字段名/类型时使用。
                - nl2sql：把自然语言问题转成 SQL 并在业务数据库执行，返回 SQL、字段、数据行、推荐图表类型与数据解读。
                - run_sql：在指定数据源上执行一条【只读】SQL（仅 SELECT/WITH），返回字段与数据行。用于你自己拼好 SQL 后取数、或核对数据。
                - rag_search：在业务知识库中做语义检索（RAG），获取业务口径、指标定义、历史查询经验等背景知识。
                - select_chart：为一条只读 SQL 的查询结果智能选图，并生成可直接渲染的 ECharts 配置。
                - analyze_alert：对指定预警规则（ruleId）做实时异常分析，读取当前指标值、与阈值比对、触发时给出 AI 原因分析。

                工作准则：
                1. 接到数据查询类问题，先思考需要哪些信息；必要时先 list_tables / list_columns 了解库表结构，再调用 nl2sql（或自己拼 SQL 经 run_sql）取数，需要可视化时再 select_chart。
                2. 涉及专业术语或口径不清晰时，先 rag_search 补充领域知识。
                3. 默认数据源 ID 为 1；除非用户明确指定其它数据源。
                4. 只做只读分析，绝不执行任何写操作（INSERT/UPDATE/DELETE/DDL 一律禁止）。
                5. 给出结论时尽量引用工具返回的具体数据（数字、分布），不要凭空编造。
                6. 如果工具返回失败，向用户说明原因并给出可能的解法，不要编造数据。
                7. 推理过程中用中文表达你的思考，让用户看到你是如何一步步拆解问题的。
                """;
    }

    /** 把最终答案按 4 字分片、逐片推送，营造流式逐字效果 */
    private void streamAnswer(String answer, AgentSession session) {
        if (answer == null || answer.isEmpty()) {
            return;
        }
        int i = 0;
        int n = answer.length();
        while (i < n) {
            int end = Math.min(i + 4, n);
            session.emitToken(answer.substring(i, end));
            i = end;
            try {
                Thread.sleep(8);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
