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
import com.bi.agent.agent.tool.SandboxListTablesTool;
import com.bi.agent.agent.tool.SandboxListColumnsTool;
import com.bi.agent.agent.tool.SandboxNl2SqlTool;
import com.bi.agent.agent.tool.SandboxRunSqlTool;
import com.bi.agent.agent.tool.SandboxSelectChartTool;
import com.bi.agent.agent.tool.SandboxMaterializeTool;
import com.bi.agent.agent.tool.SandboxCreateTableTool;
import com.bi.agent.agent.tool.SandboxUpdateTableTool;
import com.bi.agent.agent.tool.SandboxImportDataTool;
import com.bi.agent.agent.tool.SandboxDropTableTool;
import com.bi.agent.agent.tool.CreateDashboardTool;
import com.bi.agent.agent.tool.UpdateDashboardTool;
import com.bi.agent.agent.AgentSessionRegistry;
import com.bi.agent.bi.service.BiQueryService;
import com.bi.agent.bi.service.SandboxQueryService;
import com.bi.agent.bi.service.SandboxImportService;
import com.bi.agent.bi.service.IBiAlertRuleService;
import com.bi.agent.bi.service.IBiDatasourceService;
import com.bi.agent.bi.service.IBiKnowledgeService;
import com.bi.agent.bi.service.IBiDashboardService;
import com.bi.agent.bi.service.llm.LlmService;
import com.bi.agent.bi.service.sql.ChartSelector;
import com.bi.agent.bi.domain.BiDatasource;
import com.bi.agent.bi.service.probe.DataProbeService;
import com.bi.agent.bi.vo.DataProfile;
import com.bi.agent.bi.vo.DbTableVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
    private static final int MAX_STEPS = 15;

    /** 数据沙箱特殊数据源标志：前端传 datasourceId=0 表示锁定沙箱（sandbox schema） */
    public static final Long SANDBOX_DS_ID = 0L;

    /** 服务端「可信模式」开关：开启后客户端请求的 skipConfirm 才生效（写操作免确认直接执行）。
     *  默认关闭——任何客户端都无法自行绕过写操作确认护栏（安全默认）。 */
    @Value("${agent.sandbox.trusted-mode:false}")
    private boolean trustedMode;

    private final LlmService llmService;
    private final AgentMemory memory;
    private final Executor agentTaskExecutor;
    private final IBiDatasourceService datasourceService;
    private final BiQueryService queryService;
    private final IBiKnowledgeService knowledgeService;
    private final IBiAlertRuleService alertRuleService;
    private final IBiDashboardService dashboardService;
    private final ChartSelector chartSelector;
    private final SandboxQueryService sandboxQueryService;
    private final SandboxImportService sandboxImportService;
    private final DataProbeService dataProbeService;
    private final AgentSessionRegistry sessionRegistry;

    public BiAgentService(LlmService llmService,
                         AgentMemory memory,
                         @Qualifier("agentTaskExecutor") Executor agentTaskExecutor,
                         IBiDatasourceService datasourceService,
                         BiQueryService queryService,
                         SandboxQueryService sandboxQueryService,
                         SandboxImportService sandboxImportService,
                         IBiKnowledgeService knowledgeService,
                         IBiAlertRuleService alertRuleService,
                         IBiDashboardService dashboardService,
                         ChartSelector chartSelector,
                         DataProbeService dataProbeService,
                         AgentSessionRegistry sessionRegistry) {
        this.llmService = llmService;
        this.memory = memory;
        this.agentTaskExecutor = agentTaskExecutor;
        this.datasourceService = datasourceService;
        this.queryService = queryService;
        this.knowledgeService = knowledgeService;
        this.alertRuleService = alertRuleService;
        this.dashboardService = dashboardService;
        this.chartSelector = chartSelector;
        this.sandboxQueryService = sandboxQueryService;
        this.sandboxImportService = sandboxImportService;
        this.dataProbeService = dataProbeService;
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * 入口：立即返回（Servlet 线程不阻塞），真正的推理在虚拟线程中异步跑，
     * 结果经 SSE 推送给前端。
     */
    public void run(String query, String sessionId, String userId, Long datasourceId, SseEmitter emitter,
                    boolean allowWrite, boolean skipConfirm) {
        agentTaskExecutor.execute(() -> doRun(query, sessionId, userId, datasourceId, emitter, allowWrite, skipConfirm));
    }

    private void doRun(String query, String sessionId, String userId, Long datasourceId, SseEmitter emitter,
                       boolean allowWrite, boolean skipConfirm) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }
        AgentSession session = new AgentSession(sessionId, emitter);
        sessionRegistry.register(session);

        // 写操作确认护栏：客户端请求的 skipConfirm 仅当服务端开启 agent.sandbox.trusted-mode
        // 时才真正生效；否则任何客户端都无法自行绕过确认（安全默认）。
        boolean effectiveSkipConfirm = skipConfirm && trustedMode;
        if (skipConfirm && !trustedMode) {
            log.warn("客户端请求 skipConfirm，但服务端 agent.sandbox.trusted-mode=false，已忽略；"
                    + "写操作仍将要求用户确认（session={}）", sessionId);
        } else if (skipConfirm) {
            log.info("skipConfirm 已获服务端 trusted-mode 授权，写操作免确认执行（session={}）", sessionId);
        }
        try {
            // 1. 组装对话历史（含记忆回填 + 本轮 user）
            List<Map<String, Object>> messages = new ArrayList<>();
            // 解析沙箱作用域：datasourceId == 0 表示全部沙箱；< 0 表示具体沙箱库（dbId = -id）
            Long sandboxDbId = (datasourceId != null && datasourceId < 0) ? -datasourceId : null;
            String sysPrompt = buildSystemPrompt();
        if (datasourceId != null) {
            if (SANDBOX_DS_ID.equals(datasourceId) || datasourceId < 0) {
                // 沙箱模式：用沙箱专属系统提示词（暴露 5 个沙箱只读工具，
                // 避免模型调用未注册的 rag_search / analyze_alert 而报未知工具）
                sysPrompt = buildSandboxSystemPrompt(sandboxDbId, allowWrite, effectiveSkipConfirm);
            } else {
                StringBuilder prefix = new StringBuilder();
                prefix.append("【当前用户已在前端锁定数据源 ID=").append(datasourceId)
                        .append("，所有 DB 工具必须直接用它，禁止更改 datasourceId 参数】\n");
                // 【Bug A 根因修复】单次 NL2SQL 路径已接数据探查，但 Agent 多轮推理时模型常绕过
                // nl2sql、直接用 run_sql 自己拼 SQL，此时无真实数据覆盖约束，便以「系统当前日期」
                // 推算"上季度/本月"等相对时间 → 落到真实数据之外的年份（如 2023/2022）→ 查询 0 行甚至编年份。
                // 故在 Agent 全局 system prompt 注入真实时间覆盖区间，并明确禁止用系统日期推算。
                String dataConstraint = buildDataRangeConstraint(datasourceId);
                if (dataConstraint != null && !dataConstraint.isBlank()) {
                    prefix.append(dataConstraint).append("\n");
                }
                sysPrompt = prefix + sysPrompt;
            }
        }
            messages.add(Map.of("role", "system", "content", sysPrompt));
            for (Map<String, Object> h : memory.get(userId, sessionId)) {
                messages.add(h);
            }
            messages.add(Map.of("role", "user", "content", query));

            // 2. 手写 ReAct 循环（含工具调用 + 推理轨迹），顺便收集 select_chart 图表
            List<Map<String, Object>> charts = new ArrayList<>();
            List<AgentTool> requestTools = buildTools(userId, datasourceId, sandboxDbId, query, allowWrite);
            String finalAnswer = runReactLoop(messages, session, requestTools, charts, effectiveSkipConfirm);
            // 归一化为正常对话样式（去掉 ### 、** 等 Markdown 标记）
            finalAnswer = normalizeAnswer(finalAnswer);

            // 3. 逐字（分片）流式回传最终答案，营造流式效果
            streamAnswer(finalAnswer, session);

            // 3.5 把本轮生成的图表一次性推给前端，使流式过程中就能渲染，避免只在 done 后才出现
            if (!charts.isEmpty()) {
                session.emitCharts(charts);
            }

            // 4. 记忆落盘（本轮 user + 最终 assistant，含图表）
            // 图表随 assistant 消息的 charts 字段一起存 Redis，使刷新/切走再回来时图仍在
            Map<String, Object> assistantMap = new HashMap<>();
            assistantMap.put("role", "assistant");
            assistantMap.put("content", finalAnswer);
            if (!charts.isEmpty()) {
                assistantMap.put("charts", charts);
            }
            memory.add(userId, sessionId,
                    Map.of("role", "user", "content", query),
                    assistantMap);

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
        } finally {
            sessionRegistry.unregister(sessionId);
        }
    }

    /**
     * 按本次请求的数据源构建工具列表（复用 Spring 注入的 service）。
     * <p>userDsId 为用户在前端显式选择的数据源 ID（可为 null）。
     * DB 类工具以 userDsId 作为【最高优先级】缺省数据源；
     * 仅当用户未选择（userDsId == null）时，模型才可在 JSON 参数里通过 datasourceId 指定。
     */
    private List<AgentTool> buildTools(String userId, Long userDsId, Long sandboxDbId, String userQuery, boolean allowWrite) {
        List<AgentTool> t = new ArrayList<>();
        if (userDsId != null && (SANDBOX_DS_ID.equals(userDsId) || userDsId < 0)) {
            // 沙箱模式：注册沙箱专用工具集（名称与业务库一致，但指向 sandbox）
            // sandboxDbId 为 null 表示全部沙箱库；非 null 表示锁定到某一具体沙箱库的作用域
            t.add(new SandboxListTablesTool(sandboxQueryService, sandboxDbId));
            t.add(new SandboxListColumnsTool(sandboxQueryService, sandboxDbId));
            t.add(new SandboxNl2SqlTool(sandboxQueryService, sandboxDbId));
            t.add(new SandboxRunSqlTool(sandboxQueryService));
            t.add(new SandboxSelectChartTool(sandboxQueryService, chartSelector, userQuery));
            // 写工具：仅当「允许写库」主开关开启时才注册（受前端双开关控制）；
            // 关闭时只暴露 5 个只读工具，智能体无法执行任何写操作
            if (allowWrite) {
                t.add(new SandboxCreateTableTool(sandboxQueryService, sandboxDbId, userId));
                t.add(new SandboxUpdateTableTool(sandboxQueryService, sandboxDbId, userId));
                t.add(new SandboxImportDataTool(sandboxImportService, sandboxDbId, userId));
                t.add(new SandboxMaterializeTool(sandboxQueryService, sandboxDbId, userId));
                t.add(new SandboxDropTableTool(sandboxQueryService, userId));
            }
            // 创建大屏在沙箱模式和业务库模式下都可用
            t.add(new CreateDashboardTool(dashboardService, userDsId, userId));
            t.add(new UpdateDashboardTool(dashboardService, userDsId, userId));
            return t;
        }
        t.add(new ListTablesTool(datasourceService, userDsId));
        t.add(new ListColumnsTool(datasourceService, userDsId));
        t.add(new Nl2SqlTool(queryService, userDsId));
        t.add(new RunSqlTool(queryService, userDsId));
        t.add(new RagSearchTool(knowledgeService));
        t.add(new SelectChartTool(queryService, chartSelector, userDsId, userQuery));
        t.add(new AnalyzeAlertTool(alertRuleService));
        // 创建大屏在沙箱模式和业务库模式下都可用
        t.add(new CreateDashboardTool(dashboardService, userDsId, userId));
        t.add(new UpdateDashboardTool(dashboardService, userDsId, userId));
        return t;
    }

    /**
     * 手写 ReAct 工具调用循环。
     * 每轮让模型在「带 tools」上下文下决策：
     * - 返回 tool_calls → 逐个执行工具、回填 tool 角色消息、继续循环；
     * - 返回纯文本（无 tool_calls）→ 即为最终答案，退出循环。
     * 步数超过 {@link #MAX_STEPS} 仍无定论时，强制再做一次无工具的总结调用。
     */
    private String runReactLoop(List<Map<String, Object>> messages, AgentSession session,
                                 List<AgentTool> tools, List<Map<String, Object>> charts, boolean skipConfirm) {
        String toolsJson = buildToolsJson(tools);
        int steps = 0;
        String finalAnswer = null;
        boolean chartToolCalled = false;
        boolean chartRetryDone = false;

        while (true) {
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
                // 同样归一化，剥离模型顺手写的 ###/**/__ 等 Markdown，避免推理面板出现排版符号
                if (content != null && !content.isBlank()
                        && toolCalls != null && !toolCalls.isEmpty()) {
                    session.emitReasoning(normalizeAnswer(content));
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
                    AgentTool tool = findTool(tools, name);
                    String result;
                    if (tool == null) {
                        result = "未知工具：" + name;
                    } else {
                        result = executeToolWithConfirmation(session, tool, name, args, skipConfirm);
                    }
                    session.emitToolResult(name, result);

                    // 收集 select_chart 返回的 ECharts 配置，随最终答案一起落库，
                    // 使「切走再回来 / 刷新」从服务端历史恢复时图表仍在。
                    // 为多图「文字+图交错」渲染，给每张图分配递增的 chartIndex，
                    // 并把这个索引写回给模型看的 tool result，让模型在最终答案里用 {{chart:索引}} 占位。
                    if ("select_chart".equals(name)) {
                        chartToolCalled = true;
                        try {
                            JSONObject rj = JSON.parseObject(result);
                            if (rj != null && rj.containsKey("echartsOption")) {
                                Object opt = rj.get("echartsOption");
                                if (opt instanceof Map) {
                                    int idx = charts.size();
                                    Map<String, Object> chart = (Map<String, Object>) opt;
                                    chart.put("_chartIndex", idx);
                                    charts.add(chart);
                                    // 让模型知道这张图对应的索引，以便在最终答案中插入 {{chart:idx}}
                                    rj.put("chartIndex", idx);
                                    result = rj.toJSONString();
                                }
                            }
                        } catch (Exception ignore) {
                            // 解析失败不影响主流程
                        }
                    }

                    // 回填 tool 角色消息（OpenAI 格式）
                    Map<String, Object> toolMsg = new HashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", id);
                    toolMsg.put("content", result);
                    messages.add(toolMsg);
                }
            }

            // 图表意图兜底：用户明确要求出图，但模型没调 select_chart 也没在答案里留 {{chart:}} 占位，
            // 追加一条强制系统提醒并重试一次（不重置步数，使用剩余步数）。
            if (!chartRetryDone && hasChartIntent(messages)
                    && !chartToolCalled
                    && (finalAnswer == null || !finalAnswer.contains("{{chart:"))) {
                messages.add(Map.of("role", "system", "content",
                        "【强制提醒】用户明确要求生成图表。你必须立即调用 select_chart 工具出真实图表，并在对应文字描述后插入 {{chart:0}} 占位符；禁止只用文字描述或编造数据。"));
                chartRetryDone = true;
                finalAnswer = null;
                continue;
            }
            break;
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

    /** 判断用户请求中是否明确包含图表生成意图（用于兜底重试）。 */
    private boolean hasChartIntent(List<Map<String, Object>> messages) {
        for (Map<String, Object> m : messages) {
            if (!"user".equals(m.get("role"))) {
                continue;
            }
            String content = (String) m.get("content");
            if (content == null || content.isBlank()) {
                continue;
            }
            String lower = content.toLowerCase();
            if (lower.contains("图") || lower.contains("chart") || lower.contains("可视化")
                    || lower.contains("饼图") || lower.contains("柱状图") || lower.contains("折线图")
                    || lower.contains("条形图") || lower.contains("雷达图") || lower.contains("散点图")) {
                return true;
            }
        }
        return false;
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

    private AgentTool findTool(List<AgentTool> tools, String name) {
        for (AgentTool t : tools) {
            if (t.name().equals(name)) {
                return t;
            }
        }
        return null;
    }

    /**
     * 执行工具；若工具 {@link AgentTool#requiresConfirmation()} 为 true（危险写操作），
     * 先经 SSE 推一个 confirm 事件给前端并阻塞等待用户决策（同意/拒绝），
     * 超时（AgentSession.CONFIRM_TIMEOUT_SECONDS）自动视为拒绝，安全降级。
     *
     * @return 工具执行结果（被拒绝时返回一段说明文本，避免模型误以为执行成功）
     */
    private String executeToolWithConfirmation(AgentSession session, AgentTool tool, String name, String args, boolean skipConfirm) {
        if (!tool.requiresConfirmation()) {
            try {
                return tool.call(args);
            } catch (Exception ex) {
                return "工具执行异常：" + ex.getMessage();
            }
        }
        // 写工具：若已开启「跳过确认」子开关，则直接执行，不再弹确认框
        if (skipConfirm) {
            session.emitReasoning("⚠️ 即将执行写操作「" + name + "」（已开启免确认，直接执行）…");
            try {
                return tool.call(args);
            } catch (Exception ex) {
                return "工具执行异常：" + ex.getMessage();
            }
        }
        // 写工具：先请求用户确认
        Map<String, Object> payload = buildConfirmPayload(name, args);
        CompletableFuture<Boolean> future = session.requestConfirmation(payload);
        session.emitReasoning("⚠️ 即将执行写操作「" + name + "」，已暂停，等待你在对话框中确认…");
        boolean approved = session.awaitConfirmation(future);
        if (!approved) {
            session.emitReasoning("你已拒绝该写操作，已跳过执行。");
            return "用户拒绝执行写操作（" + name + "）。该操作未被执行；请改用只读方式，或向用户说明此操作已被取消。";
        }
        session.emitReasoning("已获得确认，开始执行写操作…");
        try {
            return tool.call(args);
        } catch (Exception ex) {
            return "工具执行异常：" + ex.getMessage();
        }
    }

    /** 构造推给前端的「待确认」事件载荷（含操作类型中文标题 + 关键参数摘要） */
    private Map<String, Object> buildConfirmPayload(String name, String args) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("tool", name);
        payload.put("requiresConfirmation", true);
        String title;
        String detail;
        switch (name) {
            case "create_table":
                title = "新建沙箱表";
                detail = summarizeJson(args, "tableName", "columns");
                break;
            case "materialize_table":
                title = "落表（CTAS）";
                detail = summarizeJson(args, "targetTableName", "sql");
                break;
            case "drop_table":
                title = "删除沙箱表";
                detail = summarizeJson(args, "physicalName", null);
                break;
            default:
                title = "写操作";
                detail = args;
        }
        payload.put("title", title);
        payload.put("detail", detail == null ? "" : detail);
        return payload;
    }

    /** 从工具参数 JSON 中抽取若干关键字段拼成单行摘要（便于前端确认框展示） */
    private String summarizeJson(String argsJson, String... keys) {
        if (argsJson == null) {
            return "";
        }
        try {
            JSONObject a = JSON.parseObject(argsJson);
            StringBuilder sb = new StringBuilder();
            for (String k : keys) {
                if (a.containsKey(k) && a.get(k) != null) {
                    if (sb.length() > 0) {
                        sb.append("；");
                    }
                    sb.append(k).append('=').append(String.valueOf(a.get(k)));
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return argsJson;
        }
    }

    /** 把 7 个工具定义成 OpenAI 格式的 tools JSON 字符串 */
    private String buildToolsJson(List<AgentTool> tools) {
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
                - select_chart：执行一条只读 SQL 并生成可直接渲染的 ECharts 图表配置。
                  【图表任务铁律】只要用户问题包含"图/图表/可视化/chart/饼图/柱状图/折线图/条形图"等字样，
                  你必须先调用 list_tables / list_columns 确认表与字段，再调用 select_chart 出真实图表，
                  绝对禁止只用文字描述图表、禁止说"图表已生成"。
                  需要几张图就调用几次 select_chart（每次一张）。本工具返回的 JSON 带有 chartIndex（0,1,2...）。
                  你应当在最终答案的对应文字描述后插入占位符 {{chart:chartIndex}}，例如：
                  "销售额柱状图：按产品排名，USB-C 扩展坞最高。{{chart:0}} 销量占比饼图：各产品销量分布如下。{{chart:1}}"
                - analyze_alert：对指定预警规则（ruleId）做实时异常分析，读取当前指标值、与阈值比对、触发时给出 AI 原因分析。
                - create_dashboard：根据用户描述直接创建一个 BI 数据大屏。当用户说"帮我做一个大屏 / 生成一个数据看板 / 把这些图表放到大屏里"时调用。
                  参数包括 name（大屏名称）、description（可选）、widgets（图表组件数组，每个组件含 title/chartType/sql/x/y/w/h）。
                  调用前你必须先用 run_sql / select_chart 等工具确认 SQL 能正确返回数据；每个 widget 的 sql 必须是只读 SELECT/WITH。
                  此工具会创建一条大屏记录，成功后返回 dashboardId 和 url，你必须在最终答案中把访问链接告诉用户。
                - update_dashboard：在已有的 BI 数据大屏中追加或替换图表组件。当用户说"再加点图 / 在这个大屏里追加图表 / 更新大屏内容 / 把这张图放进刚才的大屏"时调用。
                  参数包括 dashboardId（要更新的大屏 ID，必须从前一次 create_dashboard 的返回或用户提供的链接中获取）、append（true=追加，默认）、widgets（新组件数组）。
                  严禁在没有 dashboardId 或不确定是哪个大屏时声称"已更新"；你必须先调用本工具，拿到成功返回后，再把访问链接告诉用户。

                工作准则：
                1. 接到数据查询类问题，先思考需要哪些信息；务必先用 list_tables / list_columns 摸清库表结构与字段名，再调用 nl2sql（或自己拼 SQL 经 run_sql）取数；用户明确要求可视化时【必须】调用 select_chart 出真实图表，禁止文字替代。
                   【强制前置】只要你要从某张表 SELECT 字段，就必须先用 list_columns 确认该表的真实列名与类型，禁止凭训练记忆臆造列名。例如 fact_sales_order 的真实列是 region_id（不是 region），维度表用 * _id 主键关联；字段名一律以 list_columns 的返回为准，绝不能"觉得应该是这个列"。
                2. 涉及专业术语或口径不清晰时，先 rag_search 补充领域知识。
                3. 数据源使用规则：若用户已在前端选择数据源，所有 DB 类工具（list_tables / list_columns / nl2sql / run_sql / select_chart）默认就用该数据源，【不要】在调用参数里传入其他 datasourceId；只有在用户问题中明确要求查"另一个库 / 其他数据源"时才允许更换。若用户未选数据源，则 datasourceId 可省略（默认 1）。
                4. 只做只读分析，绝不执行任何写操作（INSERT/UPDATE/DELETE/DDL 一律禁止）。
                5. 给出结论时尽量引用工具返回的具体数据（数字、分布），不要凭空编造。
                6. 如果工具返回失败，向用户说明原因并给出可能的解法，不要编造数据。
                7. 推理过程中用中文表达你的思考，让用户看到你是如何一步步拆解问题的。
                8. 最终给用户的回答用自然流畅的中文口语呈现，【不要】使用 Markdown 排版语法：
                   禁止 ### / #### 等标题符号、** 加粗、__ 斜体、> 引用符号；用自然换行和「1. 2. 3.」序号组织内容，
                   让阅读体验像真人在对话框里说话，而不是一篇带格式的文档。
                9. 【严禁编造数据·铁律】当你没有真正拿到数据行时，绝对禁止编造数字填充结论。具体指以下任一情形：
                   - 工具返回失败（如 select_chart / run_sql 报错、表名或字段不存在）；
                   - 查询返回 0 行；
                   - 你根本没有调用取数工具、或调用了但没拿到结果。
                   出现上述任一情况，你必须如实告诉用户「这次没能取到真实数据」，并说明原因（例如字段名写错、SQL 执行失败），【不得】假装分析出了趋势、排名、金额或占比。
                   只有当工具真正返回了数据行，你才可以基于那些真实数字做总结和可视化。
                10. 【趋势类必须带时间维度】当用户意图是"趋势/变化/走势/增长/波动/逐月"时，你写的 SQL 必须包含时间维度——按 月/周/日 分组，或保留日期/月份列——否则根本画不出趋势线。若探查发现当前数据只有区域/品类等汇总、没有时间字段，应如实告知用户「现有数据仅含区域汇总，无法展示随时间的变化趋势」，【不要】把区域排名当成趋势，也【不要】强行画饼图凑数。
                11. 【最终答案禁止写工具自述】给用户的最后回答里，【不要】出现"图表已生成 / 已为您生成图表 / 已调用某某工具 / 以下是工具返回"这类描述你自身执行动作的话。图表会由前端直接渲染，无需用文字说明"图已生成"。直接给数据结论、原因与建议。
                12. 【图表描述须忠于真实类型】若你调用了 select_chart，图表类型由系统按数据与你的意图自动决定。你【不得】在文字里把它说成"占比/饼图"，除非它确实是占比类图表。描述图表时聚焦"数据说明了什么"（例如"从 7 月到 9 月，华东销售额逐月走高"），而不是"我画了什么图"。
                    特别地：当你用文字说"折线图"时，必须确保 select_chart 实际生成的是折线图（可显式传 chartType="line"）；说"饼图"时同理。禁止文字与图表类型不一致。
                13. 【用户指定图表类型时必须显式传递】当用户明确说"我要折线图/柱状图/饼图"或你判断某图表类型最合适时，调用 select_chart 必须同时传入 chartType 参数（line/bar/pie 等）强制指定，不要依赖系统自动猜测。chartType 参数在意图明确时建议使用，以确保文字描述与实际图表完全一致。
                """;
    }

    /**
     * 沙箱模式专属系统提示词：暴露沙箱工具集。
     * 工具集合与确认行为受双开关控制（仅在沙箱模式有意义，业务库模式根本不注册写工具）：
     * - allowWrite=false → 仅注册 5 个只读工具，并在提示词中明确「只读模式、写工具禁用」；
     * - allowWrite=true  → 额外注册 5 个写工具；
     *   - effectiveSkipConfirm=false（默认，或客户端请求但未获服务端 trusted-mode 授权）→ 写工具执行前需用户在对话框确认；
     *   - effectiveSkipConfirm=true（需服务端 agent.sandbox.trusted-mode=true 且客户端请求）→ 写工具直接执行、不再弹确认框（提示词提醒模型谨慎）。
     *
     * @param sandboxDbId 作用域沙箱库 id；为 null 表示全部沙箱库
     * @param allowWrite  是否允许写库（主开关）
     * @param skipConfirm 是否跳过确认直接执行（子开关；仅当服务端 agent.sandbox.trusted-mode=true 时才真正生效，否则写操作始终要求确认）
     */
    private String buildSandboxSystemPrompt(Long sandboxDbId, boolean allowWrite, boolean skipConfirm) {
        String scope = (sandboxDbId == null)
                ? "所有沙箱库（整个 sandbox schema）下的表"
                : "已锁定的某一个沙箱库（dbId=" + sandboxDbId + "）下的表";

        StringBuilder sb = new StringBuilder();
        sb.append("你是一个运行在「数据沙箱」中的 BI 数据分析智能体。用户把自行粘贴或导入的数据放进隔离的 sandbox 库，\n")
          .append("你只能对它做只读或受控分析，源业务数据绝不被触碰。用简洁专业的中文给出结论。\n\n")
          .append("当前作用域：").append(scope).append("。分析前先 list_tables 看清作用域内到底有哪些表，再针对它们提问。\n\n")
          .append("可用工具（全部只针对 sandbox schema）：\n")
          .append("- list_tables：列出当前作用域内数据沙箱的所有表，返回每张表的 tableName（短名，如 sales，即写 SQL 用的 sandbox.\"sales\"）、dbId（沙箱库 id）、displayName（显示名）。分析前先「看一眼」有哪些表。\n")
          .append("- list_columns：列出某张沙箱表的字段（列名、类型）。拼 SQL 前务必用本工具确认真实列名，禁止臆造。传入的 tableName 必须是 list_tables 返回的 tableName（短名，如 sales，即 sandbox.\"sales\"）。\n")
          .append("- nl2sql：把自然语言问题转成 SQL 并在沙箱执行，直接返回 SQL、字段、数据行、推荐图表与数据解读。\n")
          .append("- run_sql：在沙箱内执行一条【只读】SQL（仅 SELECT/WITH），表名必须用 sandbox.\"表名\" 全限定（表名即 list_tables 返回的 tableName），返回字段与数据行。\n")
          .append("- create_dashboard：根据用户描述直接创建一个 BI 数据大屏。当用户说\"帮我做一个大屏 / 生成一个数据看板 / 把这些图表放到大屏里\"时调用。\n")
          .append("  参数：name（大屏名称）、description（可选）、widgets（图表组件数组，每个含 title/chartType/sql/x/y/w/h）。\n")
          .append("  调用前你必须先用 run_sql / select_chart 等工具确认 SQL 能正确返回数据；每个 widget 的 sql 必须是只读 SELECT/WITH，且表名用 sandbox.\\\"表名\\\" 全限定。\n")
          .append("  此工具会创建一条大屏记录，成功后返回 dashboardId 和 url，你必须在最终答案中把访问链接告诉用户。\n")
          .append("- update_dashboard：在已有 BI 数据大屏中追加或替换图表组件。当用户说\"再加点图 / 在这个大屏里追加图表 / 更新大屏内容 / 把这张图放进刚才的大屏\"时调用。\n")
          .append("  参数：dashboardId（要更新的大屏 ID，必须从前一次 create_dashboard 返回或用户提供的链接中获取）、append（true=追加，默认）、widgets（新组件数组）。\n")
          .append("  严禁在没有 dashboardId 或不确定是哪个大屏时声称\"已更新\"；你必须先调用本工具，拿到成功返回后，再把访问链接告诉用户。\n")
          .append("- select_chart：执行一条只读 SQL 并生成可直接渲染的 ECharts 图表配置。\n")
          .append("  【图表任务铁律】只要用户问题包含\"图/图表/可视化/chart/饼图/柱状图/折线图/条形图\"等字样，\n")
          .append("  你必须先调用 list_tables / list_columns 确认表与字段，再调用 select_chart 出真实图表，\n")
          .append("  绝对禁止只用文字描述图表、禁止说\"图表已生成\"。\n")
          .append("  需要几张图就调用几次 select_chart（每次一张）。本工具返回的 JSON 带有 chartIndex（0,1,2...）。\n")
          .append("  你应当在最终答案的对应文字描述后插入占位符 {{chart:chartIndex}}，例如：\n")
          .append("  \"销售额柱状图：按产品排名，USB-C 扩展坞最高。{{chart:0}} 销量占比饼图：各产品销量分布如下。{{chart:1}}\"\n");

        if (!allowWrite) {
            sb.append("\n当前为【只读模式】：写工具（create_table / update_table / import_data / materialize_table / drop_table）已被禁用，\n")
              .append("你不得调用它们，只能做查询与可视化分析；如用户要求写入操作，请告知其需在前端开启「允许写库」开关。\n");
        } else {
            sb.append("\n写工具（以下 5 个已启用，均强制落在 sandbox schema）：\n")
              .append("- create_table：在沙箱内新建一张空表，参数 { tableName, columns:[{name,type}], dbId? }。列类型仅限白名单：BIGINT / INTEGER / NUMERIC(18,2) / VARCHAR(50) / TEXT / DATE / TIMESTAMP / BOOLEAN。\n")
              .append("- update_table：修改一张已有沙箱表的显示名（中文别名），参数 { tableName, displayName, dbId? }；仅改元数据，不影响物理表名与 SQL。\n")
              .append("- import_data：向已有沙箱表导入/追加数据行，参数 { tableName, rows:[{列名:值},...], mode:\"append\"/\"replace\", dbId? }；mode 默认 append（追加），replace 会先清空表再写入。\n")
              .append("- materialize_table：把一条只读 SELECT 落为一张新沙箱表（CTAS），参数 { sql, targetTableName, dbId? }；先 run_sql/nl2sql 验证 SELECT 无误，再调用本工具物化。\n")
              .append("- drop_table：删除一张沙箱表（不可恢复），参数 { dbId?, tableName }（tableName 即 list_tables 返回的 tableName，如 sales）。\n");
            if (skipConfirm) {
                sb.append("\n【免确认模式】你一旦明确调用上述任意写工具，将【直接执行、不再弹出确认框】。请务必在调用前自行确认参数（表名/字段/SQL）完全无误，避免误删或误写数据。\n");
            } else {
                sb.append("\n写工具执行前需用户确认：仅在用户【明确要求】建表/改显示名/导入/落表/删表时才调用，不要自作主张改写数据；\n")
                  .append("你一旦明确要求即【立刻调用对应工具】，不要在最终答案里先问用户\"是否执行\"或等待用户回复——系统会在调用后自动弹出确认框，由用户点击「同意/拒绝」决定。\n")
                  .append("调用 drop_table / update_table / import_data 时优先传入 dbId + tableName（表名，如 sales）；沙箱内物理名与短名已统一，直接传 tableName 即可。\n")
                  .append("若用户拒绝，工具不会执行，你应改用只读方式或如实告知操作被取消。\n");
            }
            sb.append("\n【写工具结果铁律】\n")
              .append("1) 绝对禁止在最终答案里写\"操作已成功完成\"\"数据已导入\"\"表已删除\"等虚假陈述；只有工具确实返回 success=true 且后续 run_sql 验证到非空数据后，才能宣称成功。\n")
              .append("2) 写工具返回失败/异常/未执行时，【不要甩给用户】。你自己拥有 list_tables / list_columns / run_sql 工具：先分析失败原因（表名错？字段错？SQL 语法错？），再调用这些只读工具核对真实表名与字段，修正参数或 SQL 后【自动重试】。\n")
              .append("   你应当像人一样持续推进「思考→执行→失败→思考→修正→再执行」的循环，直至达成用户目标，而不是中途把排查任务丢回给用户。\n")
              .append("3) 单次失败允许重试，但连续重试不超过 3 次；若 3 次仍失败，才在最终答案中如实说明：已尝试了什么、卡在哪个具体错误（如\"SQL 引用了不存在的关联表 demo_order_product\"），以及需要用户补充的真实信息。此时才可暂停，且必须基于真实错误信息，不得编造。\n")
              .append("4) 禁止在回复中写\"建议你检查某表字段并请提供\"\"请提供这些表的字段信息\"这类把查表结构推回给用户的话术——查表结构是你自己的职责。\n");
        }

        // 工作准则（公共部分）
        sb.append("\n工作准则：\n")
          .append("1. 凡要 SELECT 某张表，必须先 list_tables 确认表名（短名）、再 list_columns 确认字段，禁止臆造表名/列名。\n")
          .append("2. 所有 SQL 的表名一律用 sandbox.\"表名\" 形式（表名即 list_tables 返回的 tableName，如 sandbox.\"sales\"），不要自行拼接任何库前缀；禁止访问 public 或其他 schema。\n")
          .append("   沙箱内不做任何越权写操作（绝不触碰 public / bi_* 系统表）。\n")
          .append("3. 数据查询类问题优先用 nl2sql；需要自己核对/取数时用 run_sql；用户要求图表时【必须】直接调用 select_chart 出真实图表，禁止文字替代。\n");
        if (allowWrite) {
            sb.append("4. 写工具仅在用户明确要求时才调用；调用 drop_table / update_table / import_data 优先传 dbId + tableName（表名，如 sales），最不容易出错。\n")
              .append("   对于\"从已有沙箱表计算生成一张新表\"这类需求，优先使用 materialize_table（CTAS）一次性完成，不要拆成 drop_table + create_table + import_data 多步操作，降低中途失败和状态不一致风险；\n")
              .append("   materialize_table 的 sql 必须先经 run_sql 验证能正确返回数据，且表名只能用 list_tables 返回的短名（如 sandbox.\"demo_order\"），禁止臆造表名或字段名。\n");
        }
        int n = allowWrite ? 5 : 4;
        sb.append(n).append(". 结论尽量引用工具返回的真实数字；没有真实数据时如实说明，绝不编造任何数字或趋势。\n");
        sb.append(n + 1).append(". 最终回答用自然流畅的中文口语呈现，不使用 Markdown 排版（禁止 ### /**/__/> 等符号），用自然换行与「1. 2. 3.」序号组织。\n");
        sb.append(n + 2).append(". 不要出现「图表已生成 / 已调用工具」这类自述；图表由前端直接渲染，直接给数据结论、原因与建议。\n");
        sb.append(n + 3).append(". 【图表描述与真实类型必须一致】如果你调用了 select_chart，最终文字里描述图表类型时必须与 select_chart 实际生成的类型一致：折线图就写\"折线图\"，饼图就写\"饼图\"，不要混用。\n");
        return sb.toString();
    }

    /**
     * 探查数据源真实数据时间覆盖，生成强约束文本注入 Agent system prompt（治本）。
     *
     * <p>单次 NL2SQL（{@code BiQueryService}）已接数据探查，但 Agent 多轮推理时模型常绕过
     * nl2sql、直接用 run_sql 自己拼 SQL；此时无真实数据覆盖约束，便以「系统当前日期」推算
     * "上季度/本月/去年同期"等相对时间 → 落到真实数据之外的年份（如 2023/2022）→ 查询 0 行甚至编年份。
     * 故在 Agent 全局 system prompt 注入真实覆盖区间，并明确禁止用系统日期推算。
     *
     * <p>任何异常（连接失败 / 探查降级）均返回 {@code null}，不阻断 Agent 主流程。
     *
     * @param datasourceId 已锁定的数据源 ID
     * @return 多行约束文本，或 null（无约束时）
     */
    private String buildDataRangeConstraint(Long datasourceId) {
        try {
            BiDatasource ds = datasourceService.selectBiDatasourceById(datasourceId);
            if (ds == null) {
                return null;
            }
            // 候选业务表：拉取全表，排除 bi_ 系统表（与 BiQueryService 保持一致）
            List<String> candidateTables = new ArrayList<>();
            for (DbTableVo t : datasourceService.listTables(datasourceId)) {
                String name = t.getTableName();
                if (name != null && !name.toLowerCase().startsWith("bi_")) {
                    candidateTables.add(name);
                }
            }
            if (candidateTables.isEmpty()) {
                return null;
            }
            Map<String, DataProfile> profiles = dataProbeService.probe(ds, candidateTables, ds.getType());
            if (profiles == null || profiles.isEmpty()) {
                return null;
            }
            // 汇总所有表的时间列覆盖，生成约束文本
            StringBuilder sb = new StringBuilder();
            sb.append("【数据真实时间范围（务必以此为准，禁止用系统当前日期推算相对时间）】\n");
            sb.append("本数据源实际数据覆盖如下；回答「上季度 / 本月 / 去年同期」等相对时间时，");
            sb.append("必须映射到下列真实区间之内，绝不要编造区间之外的年份或日期：\n");
            boolean hasTime = false;
            for (Map.Entry<String, DataProfile> e : profiles.entrySet()) {
                DataProfile p = e.getValue();
                if (p == null || p.getTimeColumns() == null || p.getTimeColumns().isEmpty()) {
                    continue;
                }
                hasTime = true;
                sb.append("- 表 ").append(e.getKey()).append("：");
                for (Map.Entry<String, DataProfile.TimeRange> te : p.getTimeColumns().entrySet()) {
                    DataProfile.TimeRange tr = te.getValue();
                    sb.append(te.getKey()).append("=").append(tr.getMin()).append("~").append(tr.getMax());
                    if (tr.getLatestQuarter() != null) {
                        sb.append("（最新可用 ").append(tr.getLatestQuarter()).append("）");
                    }
                    sb.append("；");
                }
                sb.append("\n");
            }
            if (!hasTime) {
                // 有探查但无显式时间列：给一条「不要编年份」的兜底软提示
                sb.append("（探查未发现显式时间列；若涉及时间范围，请先用 list_tables / list_columns 确认字段，切勿臆造年份。）\n");
            }
            sb.append("约束：禁止用 CURRENT_DATE / NOW() / 系统当前日期推算「上季度 / 本月 / 去年」等区间；");
            sb.append("必须基于上面真实覆盖构造 SQL 的 WHERE 时间条件。");
            return sb.toString();
        } catch (Exception ex) {
            log.warn("构建数据时间范围约束失败，降级走无约束逻辑：dsId={}", datasourceId, ex);
            return null;
        }
    }

    /**
     * 把最终答案按 4 字分片、逐片推送，营造流式逐字效果。
     * 其中 {{chart:N}} 占位符用于「文字+图交错」渲染，必须整体一次性推送，
     * 不能被 4 字切片拆散，否则前端无法识别。
     */
    private void streamAnswer(String answer, AgentSession session) {
        if (answer == null || answer.isEmpty()) {
            return;
        }
        List<String> parts = splitAnswerWithPlaceholders(answer);
        for (String part : parts) {
            if (part.startsWith("{{chart:")) {
                session.emitToken(part);
                continue;
            }
            int i = 0;
            int n = part.length();
            while (i < n) {
                int end = Math.min(i + 4, n);
                session.emitToken(part.substring(i, end));
                i = end;
                try {
                    Thread.sleep(8);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * 把最终答案拆成「普通文字」和「{{chart:N}} 占位符」交替的片段列表，
     * 供流式推送时保证占位符整体发送。
     */
    private List<String> splitAnswerWithPlaceholders(String answer) {
        List<String> parts = new ArrayList<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\{\\{chart:\\d+\\}\\}");
        java.util.regex.Matcher m = p.matcher(answer);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                String text = answer.substring(last, m.start());
                if (!text.isEmpty()) {
                    parts.add(text);
                }
            }
            parts.add(m.group());
            last = m.end();
        }
        if (last < answer.length()) {
            String text = answer.substring(last);
            if (!text.isEmpty()) {
                parts.add(text);
            }
        }
        return parts;
    }

    /**
     * 把模型最终答案归一化为「正常对话」样式：去掉 Markdown 标题(###)、加粗(**)、斜体(__)、
     * 行首引用(>) 等标记，保留换行与序号，让前端按纯文本呈现，像真人对话。
     */
    private static String normalizeAnswer(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // 去掉行首的 Markdown 标题（# ~ ######，后跟空格）
        text = text.replaceAll("(?m)^\\s{0,3}#{1,6}\\s+", "");
        // 去掉加粗/斜体标记 ** 或 __（保留中间文字）
        text = text.replaceAll("\\*\\*", "").replaceAll("__", "");
        // 去掉行首引用标记 >（保留引用文字）
        text = text.replaceAll("(?m)^\\s{0,3}>\\s?", "");
        // 兜底：剔除 Agent 自述短语（如"图表已生成，展示了…情况。"），避免泄漏到最终答案
        text = text.replaceAll("图表已生成[，,\\s]*展示了[^。]*。?", "");
        text = text.replaceAll("(已为您生成图表|已生成图表|已调用[^。，,]*工具)[。，,]?", "");
        text = text.replaceAll("以下是工具返回[：:]。?", "");
        text = text.replaceAll("工具返回如下[：:]。?", "");
        return text.strip();
    }
}
