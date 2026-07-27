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
import com.bi.agent.bi.domain.BiDatasource;
import com.bi.agent.bi.service.probe.DataProbeService;
import com.bi.agent.bi.vo.DataProfile;
import com.bi.agent.bi.vo.DbTableVo;
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
    private final IBiDatasourceService datasourceService;
    private final BiQueryService queryService;
    private final IBiKnowledgeService knowledgeService;
    private final IBiAlertRuleService alertRuleService;
    private final ChartSelector chartSelector;
    private final DataProbeService dataProbeService;

    public BiAgentService(LlmService llmService,
                         AgentMemory memory,
                         @Qualifier("agentTaskExecutor") Executor agentTaskExecutor,
                         IBiDatasourceService datasourceService,
                         BiQueryService queryService,
                         IBiKnowledgeService knowledgeService,
                         IBiAlertRuleService alertRuleService,
                         ChartSelector chartSelector,
                         DataProbeService dataProbeService) {
        this.llmService = llmService;
        this.memory = memory;
        this.agentTaskExecutor = agentTaskExecutor;
        this.datasourceService = datasourceService;
        this.queryService = queryService;
        this.knowledgeService = knowledgeService;
        this.alertRuleService = alertRuleService;
        this.chartSelector = chartSelector;
        this.dataProbeService = dataProbeService;
    }

    /**
     * 入口：立即返回（Servlet 线程不阻塞），真正的推理在虚拟线程中异步跑，
     * 结果经 SSE 推送给前端。
     */
    public void run(String query, String sessionId, String userId, Long datasourceId, SseEmitter emitter) {
        agentTaskExecutor.execute(() -> doRun(query, sessionId, userId, datasourceId, emitter));
    }

    private void doRun(String query, String sessionId, String userId, Long datasourceId, SseEmitter emitter) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }
        AgentSession session = new AgentSession(sessionId, emitter);
        try {
            // 1. 组装对话历史（含记忆回填 + 本轮 user）
            List<Map<String, Object>> messages = new ArrayList<>();
            String sysPrompt = buildSystemPrompt();
            if (datasourceId != null) {
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
            messages.add(Map.of("role", "system", "content", sysPrompt));
            for (Map<String, Object> h : memory.get(userId, sessionId)) {
                messages.add(h);
            }
            messages.add(Map.of("role", "user", "content", query));

            // 2. 手写 ReAct 循环（含工具调用 + 推理轨迹），顺便收集 select_chart 图表
            List<Map<String, Object>> charts = new ArrayList<>();
            List<AgentTool> requestTools = buildTools(datasourceId, query);
            String finalAnswer = runReactLoop(messages, session, requestTools, charts);
            // 归一化为正常对话样式（去掉 ### 、** 等 Markdown 标记）
            finalAnswer = normalizeAnswer(finalAnswer);

            // 3. 逐字（分片）流式回传最终答案，营造流式效果
            streamAnswer(finalAnswer, session);

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
        }
    }

    /**
     * 按本次请求的数据源构建工具列表（复用 Spring 注入的 service）。
     * <p>userDsId 为用户在前端显式选择的数据源 ID（可为 null）。
     * DB 类工具以 userDsId 作为【最高优先级】缺省数据源；
     * 仅当用户未选择（userDsId == null）时，模型才可在 JSON 参数里通过 datasourceId 指定。
     */
    private List<AgentTool> buildTools(Long userDsId, String userQuery) {
        List<AgentTool> t = new ArrayList<>();
        t.add(new ListTablesTool(datasourceService, userDsId));
        t.add(new ListColumnsTool(datasourceService, userDsId));
        t.add(new Nl2SqlTool(queryService, userDsId));
        t.add(new RunSqlTool(queryService, userDsId));
        t.add(new RagSearchTool(knowledgeService));
        t.add(new SelectChartTool(queryService, chartSelector, userDsId, userQuery));
        t.add(new AnalyzeAlertTool(alertRuleService));
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
                                 List<AgentTool> tools, List<Map<String, Object>> charts) {
        String toolsJson = buildToolsJson(tools);
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
                    try {
                        result = tool.call(args);
                    } catch (Exception ex) {
                        result = "工具执行异常：" + ex.getMessage();
                    }
                }
                session.emitToolResult(name, result);

                // 收集 select_chart 返回的 ECharts 配置，随最终答案一起落库，
                // 使「切走再回来 / 刷新」从服务端历史恢复时图表仍在
                if ("select_chart".equals(name)) {
                    try {
                        JSONObject rj = JSON.parseObject(result);
                        if (rj != null && rj.containsKey("echartsOption")) {
                            Object opt = rj.get("echartsOption");
                            if (opt instanceof Map) {
                                charts.add((Map<String, Object>) opt);
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

    private AgentTool findTool(List<AgentTool> tools, String name) {
        for (AgentTool t : tools) {
            if (t.name().equals(name)) {
                return t;
            }
        }
        return null;
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
                - select_chart：为一条只读 SQL 的查询结果智能选图，并生成可直接渲染的 ECharts 配置。
                - analyze_alert：对指定预警规则（ruleId）做实时异常分析，读取当前指标值、与阈值比对、触发时给出 AI 原因分析。

                工作准则：
                1. 接到数据查询类问题，先思考需要哪些信息；务必先用 list_tables / list_columns 摸清库表结构与字段名，再调用 nl2sql（或自己拼 SQL 经 run_sql）取数，需要可视化时再 select_chart。
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
                13. 【用户指定图表类型时必须显式传递】当用户明确说"我要折线图/柱状图/饼图"时，调用 select_chart 必须同时传入 chartType 参数（line/bar/pie 等）强制指定，不要依赖系统自动猜测。chartType 参数仅在用户明确指定图表类型时使用；用户未指定时留空，由系统自动选择。
                """;
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
