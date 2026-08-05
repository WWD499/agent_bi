package com.bi.agent.agent;

import com.bi.agent.bi.service.BiQueryService;
import com.bi.agent.bi.service.IBiAlertRuleService;
import com.bi.agent.bi.service.IBiDashboardService;
import com.bi.agent.bi.service.IBiDatasourceService;
import com.bi.agent.bi.service.IBiKnowledgeService;
import com.bi.agent.bi.service.SandboxImportService;
import com.bi.agent.bi.service.SandboxQueryService;
import com.bi.agent.bi.service.llm.LlmService;
import com.bi.agent.bi.service.probe.DataProbeService;
import com.bi.agent.bi.service.sql.ChartSelector;
import com.bi.agent.agent.AgentSession;
import com.bi.agent.agent.AgentSessionRegistry;
import com.bi.agent.bi.domain.BiDatasource;
import com.bi.agent.bi.vo.DbTableVo;
import com.bi.agent.bi.vo.DataProfile;
import com.bi.agent.bi.vo.QueryResultVo;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BiAgentService 手写 ReAct 编排的单测（Phase 2）
 *
 * <p>不依赖真实 LLM / 数据库：用 Mockito 把 LlmService 与各个业务 service 全部 mock，
 * 验证核心链路——模型返回 tool_calls → 工具被真正调用 → tool 结果回填 →
 * 模型给出最终文本 → 逐字 token / done 事件推送给 SSE → 记忆落盘。
 *
 * <p>虚拟线程执行器在测试中被替换成「同步直跑」执行器，便于断言。
 */
@ExtendWith(MockitoExtension.class)
class BiAgentServiceTest {

    @Mock
    private LlmService llmService;
    @Mock
    private AgentMemory memory;
    @Mock
    private IBiDatasourceService datasourceService;
    @Mock
    private BiQueryService queryService;
    @Mock
    private IBiKnowledgeService knowledgeService;
    @Mock
    private IBiAlertRuleService alertRuleService;
    @Mock
    private IBiDashboardService dashboardService;
    @Mock
    private ChartSelector chartSelector;
    @Mock
    private DataProbeService dataProbeService;
    @Mock
    private SandboxQueryService sandboxQueryService;
    @Mock
    private SandboxImportService sandboxImportService;
    @Mock
    private AgentSessionRegistry sessionRegistry;
    @Mock
    private SseEmitter emitter;
    @Mock
    private Executor agentExecutor;

    @InjectMocks
    private BiAgentService service;

    @BeforeEach
    void setup() {
        // 把异步执行器替换成「同步直跑」，便于断言；参数列表变化不再影响本测试
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(agentExecutor).execute(any());
        when(memory.get(anyString(), anyString())).thenReturn(List.of());
    }

    /** 多步推理：模型先调 list_tables，再基于结果给最终答案 */
    @Test
    void multiStepReact_dispatchesToolAndStreamsAnswer() throws java.io.IOException {
        // 第一步：模型决定调用 list_tables（arguments 是 JSON 字符串）
        String step1 = "{"
                + "\"choices\":[{\"message\":{"
                + "\"role\":\"assistant\","
                + "\"content\":\"我先看一下库里有哪些表。\","
                + "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"list_tables\","
                + "\"arguments\":\"{\\\"datasourceId\\\":1}\"}}]"
                + "}}]}";
        // 第二步：无 tool_calls，给出最终答案
        String step2 = "{"
                + "\"choices\":[{\"message\":{"
                + "\"role\":\"assistant\","
                + "\"content\":\"库里有 demo_employee、demo_department 等表，可用于人员与部门分析。\""
                + "}}]}";

        when(llmService.chatRaw(anyList(), anyString())).thenReturn(step1, step2);

        DbTableVo t1 = new DbTableVo();
        t1.setTableName("demo_employee");
        t1.setRemarks("员工表");
        DbTableVo t2 = new DbTableVo();
        t2.setTableName("demo_department");
        t2.setRemarks("部门表");
        when(datasourceService.listTables(1L)).thenReturn(List.of(t1, t2));

        // 同步执行（direct executor）
        service.run("库里有哪些业务表？", "sess-1", "unit-test", null, emitter, false, false);

        // 1. 工具被真正调用
        verify(datasourceService).listTables(1L);
        // 2. SSE 事件被推送（tool_call / tool_result / token / done 等）
        verify(emitter, atLeastOnce()).send(ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        // 3. 记忆落盘（本轮 user + 最终 assistant）
        verify(memory).add(eq("unit-test"), eq("sess-1"), any(), any());
    }

    /** 单步直答：模型首轮就返回最终文本（无工具调用），仍应流式推送并落盘 */
    @Test
    void singleStepReact_streamsDirectAnswer() throws java.io.IOException {
        String direct = "{"
                + "\"choices\":[{\"message\":{"
                + "\"role\":\"assistant\","
                + "\"content\":\"你好，我是 BI 数据分析智能体，可以帮你查数、选图、分析预警。\""
                + "}}]}";
        when(llmService.chatRaw(anyList(), anyString())).thenReturn(direct);

        service.run("你是谁？", "sess-2", "unit-test", null, emitter, false, false);

        verify(emitter, atLeastOnce()).send(ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        verify(memory).add(eq("unit-test"), eq("sess-2"), any(), any());
    }

    /** 步数上限保护：连续 8 步都返回 tool_calls 时，应触发兜底总结调用且不死循环 */
    @Test
    void stepLimitInvokesFallbackSummary() throws java.io.IOException {
        String loopStep = "{"
                + "\"choices\":[{\"message\":{"
                + "\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"c\",\"type\":\"function\","
                + "\"function\":{\"name\":\"list_tables\",\"arguments\":\"{\\\"datasourceId\\\":1}\"}}]"
                + "}}]}";
        String fallback = "{"
                + "\"choices\":[{\"message\":{"
                + "\"role\":\"assistant\","
                + "\"content\":\"已达步数上限，请拆分问题后重试。\""
                + "}}]}";
        // 前 8 次都返回 tool_calls，第 9 次（兜底、无 tools）返回总结
        when(llmService.chatRaw(anyList(), anyString()))
                .thenReturn(loopStep, loopStep, loopStep, loopStep,
                        loopStep, loopStep, loopStep, loopStep, fallback);

        when(datasourceService.listTables(1L)).thenReturn(List.of(new DbTableVo()));

        service.run("复杂问题", "sess-3", "unit-test", null, emitter, false, false);

        // 工具最多被调 8 次（步数上限），不会无限循环
        verify(datasourceService, org.mockito.Mockito.atMost(8)).listTables(1L);
        verify(emitter, atLeastOnce()).send(ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
    }

    /**
     * 【Bug A 回归】前端锁定数据源（datasourceId != null）时，必须触发真实数据探查
     * （dataProbeService.probe）并把真实时间覆盖注入 system prompt，
     * 否则模型会绕过 nl2sql 用系统日期推算"上季度"→ 编造 2023/2022 等区间外年份 → 0 行。
     */
    @Test
    void whenDatasourceLocked_probesRealDataRange() throws java.io.IOException {
        BiDatasource ds = org.mockito.Mockito.mock(BiDatasource.class);
        org.mockito.Mockito.when(ds.getType()).thenReturn("postgresql");
        org.mockito.Mockito.when(datasourceService.selectBiDatasourceById(10L)).thenReturn(ds);

        DbTableVo t = new DbTableVo();
        t.setTableName("fact_monthly_sales");
        org.mockito.Mockito.when(datasourceService.listTables(10L)).thenReturn(List.of(t));

        // 构造一张已探查到整型年月组合的画像（覆盖 2024-01~2025-12，最新季度 2025-Q4）
        DataProfile profile = new DataProfile();
        profile.setTableName("fact_monthly_sales");
        profile.setRowCount(1200);
        DataProfile.TimeRange tr = new DataProfile.TimeRange(
                "year+month（整型年月组合）", "2024-01", "2025-12", "2025-Q4");
        java.util.Map<String, DataProfile.TimeRange> tm = new java.util.LinkedHashMap<>();
        tm.put("year+month", tr);
        profile.setTimeColumns(tm);
        org.mockito.Mockito.when(dataProbeService.probe(org.mockito.ArgumentMatchers.eq(ds), anyList(), org.mockito.ArgumentMatchers.eq("postgresql")))
                .thenReturn(java.util.Map.of("fact_monthly_sales", profile));

        String direct = "{"
                + "\"choices\":[{\"message\":{"
                + "\"role\":\"assistant\","
                + "\"content\":\"各区域销售额趋势如下...\""
                + "}}]}";
        org.mockito.Mockito.when(llmService.chatRaw(anyList(), anyString())).thenReturn(direct);

        service.run("上季度各区域销售额趋势", "sess-4", "unit-test", 10L, emitter, false, false);

        // 关键断言：锁定数据源时确实调用了探查（根治编年份的根因修复已接线）
        verify(dataProbeService).probe(org.mockito.ArgumentMatchers.eq(ds), anyList(), org.mockito.ArgumentMatchers.eq("postgresql"));
        verify(emitter, atLeastOnce()).send(ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
    }

    /**
     * 【图表意图兜底回归】用户明确要求出图，但模型首轮偷懒直接文字回答（未调 select_chart），
     * 应触发一次系统提醒重试，最终必须调用 select_chart 并产出图表。
     */
    @Test
    void whenModelSkipsChart_onChartIntent_triggersRetryAndSelectChart() throws java.io.IOException {
        // 第一轮：模型偷懒，直接文字回答（无 tool_calls）
        String lazyText = "{"
                + "\"choices\":[{\"message\":{"
                + "\"role\":\"assistant\","
                + "\"content\":\"产品销售额柱状图显示 USB-C 扩展坞最高。\""
                + "}}]}";
        // 第二轮：经兜底提醒后，模型调用 select_chart
        String chartToolCall = "{"
                + "\"choices\":[{\"message\":{"
                + "\"role\":\"assistant\",\"content\":\"我先查数据并出图。\","
                + "\"tool_calls\":[{\"id\":\"call_chart\",\"type\":\"function\","
                + "\"function\":{\"name\":\"select_chart\","
                + "\"arguments\":\"{\\\"sql\\\":\\\"SELECT product_name, sum(amount) as total FROM sales GROUP BY product_name ORDER BY total DESC LIMIT 10\\\"}\"}}]"
                + "}}]}";
        // 第三轮：给出带 {{chart:0}} 的最终答案
        String finalWithPlaceholder = "{"
                + "\"choices\":[{\"message\":{"
                + "\"role\":\"assistant\","
                + "\"content\":\"销售额柱状图：按产品排名。{{chart:0}}\""
                + "}}]}";

        when(llmService.chatRaw(anyList(), anyString())).thenReturn(lazyText, chartToolCall, finalWithPlaceholder);

        QueryResultVo vo = new QueryResultVo();
        vo.setColumns(List.of("product_name", "total"));
        com.alibaba.fastjson2.JSONObject row = new com.alibaba.fastjson2.JSONObject();
        row.put("product_name", "USB-C扩展坞");
        row.put("total", 3200);
        vo.setData(List.of(row));
        vo.setRowCount(1);
        when(queryService.runReadOnlySql(any(), anyString())).thenReturn(vo);
        when(chartSelector.selectChart(anyList(), anyList(), anyString(), ArgumentMatchers.isNull()))
                .thenReturn(ChartSelector.ChartType.BAR);
        com.alibaba.fastjson2.JSONObject option = new com.alibaba.fastjson2.JSONObject();
        option.put("xAxis", "product_name");
        option.put("series", List.of());
        when(chartSelector.generateEChartsOption(eq(ChartSelector.ChartType.BAR), anyList(), anyList()))
                .thenReturn(option);

        service.run("生成销售额柱状图", "sess-chart", "unit-test", null, emitter, false, false);

        // 关键断言：select_chart 最终被调用，且图表被收集
        verify(queryService).runReadOnlySql(any(), anyString());
        verify(chartSelector).selectChart(anyList(), anyList(), anyString(), ArgumentMatchers.isNull());
        verify(emitter, atLeastOnce()).send(ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
    }

    // ============================ P1：确认流 + MAX_STEPS 边界 ============================

    private static final String DASHBOARD_ARGS =
            "{\"name\":\"销售大屏\",\"datasourceId\":0,\"widgets\":[{\"title\":\"t\",\"chartType\":\"bar\",\"sql\":\"SELECT 1\"}]}";

    /** 拼一个「模型返回单个 tool_call」的 LLM 响应（arguments 为 JSON 字符串，需再转义一层） */
    private String toolCall(String toolName, String argsJson) {
        String argsStr = JSON.toJSONString(argsJson);
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"" + toolName + "\",\"arguments\":" + argsStr + "}}]}}]}";
    }

    /** 拼一个「模型直接返回最终文本」的 LLM 响应 */
    private String finalText(String text) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + text + "\"}}]}";
    }

    /** 反射设置 BiAgentService.trustedMode（@Value 在单测中不会被 Spring 注入） */
    private void setTrustedMode(boolean v) throws Exception {
        var f = BiAgentService.class.getDeclaredField("trustedMode");
        f.setAccessible(true);
        f.set(service, v);
    }

    /** 轮询等待会话进入「等待确认」状态（避免主线程检查早于后台线程设状态造成竞态） */
    private void waitForAwaitingConfirm(AgentSession s, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (s.isAwaitingConfirm()) {
                return;
            }
            Thread.sleep(20);
        }
    }

    /**
     * 【P0 护栏回归】trustedMode 默认 false 时，即使客户端传 skipConfirm=true，
     * 写工具（create_dashboard 需确认）仍必须走确认流程——绝不能免确认直执行。
     * 这里断言：会话进入了等待确认状态，且用户拒绝后写操作未被执行。
     */
    @Test
    void skipConfirmIgnoredWhenTrustedModeFalse_writeToolBlocked() throws Exception {
        String step1 = toolCall("create_dashboard", DASHBOARD_ARGS);
        String step2 = finalText("已为你生成大屏（被拒绝分支后的最终答复）");
        when(llmService.chatRaw(anyList(), anyString())).thenReturn(step1, step2);

        // run 内部会阻塞在 awaitConfirmation，必须在后台线程跑，主线程来 resolve
        Thread t = new Thread(() -> service.run("帮我创建销售大屏", "sess-p0", "u", null, emitter, false, true));
        t.start();
        ArgumentCaptor<AgentSession> cap = ArgumentCaptor.forClass(AgentSession.class);
        verify(sessionRegistry, timeout(5000)).register(cap.capture());
        AgentSession session = cap.getValue();
        // 先等会话真正进入「等待确认」再断言/放行，避免检查早于状态设置造成竞态
        waitForAwaitingConfirm(session, 5000);
        assertTrue(session.isAwaitingConfirm(), "写工具未进入等待确认状态，P0 护栏可能已被绕过");
        session.resolveConfirmation(false); // 模拟用户在对话框点「拒绝」
        t.join(5000);

        verify(dashboardService, never()).insertBiDashboard(any());
    }

    /**
     * 服务端开启 trusted-mode 且客户端 skipConfirm=true 时，写工具应免确认直接执行。
     * （这是护栏的「授权放行」分支，与上面的「拦截」分支成对。）
     */
    @Test
    void trustedModeTrueAndSkipConfirmTrue_writeToolExecutesDirectly() throws Exception {
        setTrustedMode(true);
        try {
            String step1 = toolCall("create_dashboard", DASHBOARD_ARGS);
            String step2 = finalText("大屏已创建");
            when(llmService.chatRaw(anyList(), anyString())).thenReturn(step1, step2);
            when(dashboardService.insertBiDashboard(any())).thenReturn(1);

            service.run("帮我创建销售大屏", "sess-trust", "u", null, emitter, false, true);

            verify(dashboardService).insertBiDashboard(any());
        } finally {
            setTrustedMode(false);
        }
    }

    /**
     * 默认（trustedMode=false, skipConfirm=false）下，写工具请求确认且用户「同意」后应真正执行。
     */
    @Test
    void confirmationApproved_writeToolExecutes() throws Exception {
        String step1 = toolCall("create_dashboard", DASHBOARD_ARGS);
        String step2 = finalText("大屏已创建");
        when(llmService.chatRaw(anyList(), anyString())).thenReturn(step1, step2);
        when(dashboardService.insertBiDashboard(any())).thenReturn(1);

        Thread t = new Thread(() -> service.run("帮我创建销售大屏", "sess-approve", "u", null, emitter, false, false));
        t.start();
        ArgumentCaptor<AgentSession> cap = ArgumentCaptor.forClass(AgentSession.class);
        verify(sessionRegistry, timeout(5000)).register(cap.capture());
        AgentSession session = cap.getValue();
        waitForAwaitingConfirm(session, 5000); // 等进入等待确认后再「同意」，否则 future 尚未创建、resolve 无效
        session.resolveConfirmation(true); // 模拟用户「同意」
        t.join(5000);

        verify(dashboardService).insertBiDashboard(any());
    }

    /**
     * 只读工具（requiresConfirmation=false）应直接执行，且不进入「等待确认」状态。
     * 与上面的写工具确认流形成对照。
     */
    @Test
    void readonlyToolExecutesWithoutEnteringConfirm() throws Exception {
        String step1 = toolCall("list_tables", "{\"datasourceId\":1}");
        String step2 = finalText("表清单如下");
        when(llmService.chatRaw(anyList(), anyString())).thenReturn(step1, step2);
        when(datasourceService.listTables(1L)).thenReturn(List.of(new DbTableVo()));

        ArgumentCaptor<AgentSession> cap = ArgumentCaptor.forClass(AgentSession.class);
        service.run("有哪些表", "sess-ro", "u", null, emitter, false, false);
        verify(sessionRegistry).register(cap.capture());

        assertFalse(cap.getValue().isAwaitingConfirm(), "只读工具不应进入等待确认状态");
        verify(datasourceService).listTables(1L);
    }

    /**
     * 【MAX_STEPS 边界】循环实际以常量 MAX_STEPS=15 为上界（AgentSession.MAX_TOOL_CALLS=8 未接入循环）。
     * 连续 15 步都返回 tool_calls 时应恰好执行 15 次工具调用，第 16 次为无工具的兜底总结，不死循环。
     * （已有 stepLimitInvokesFallbackSummary 只 loop 8 次，未触达真实边界，这里补齐。）
     */
    @Test
    void maxStepsIsFifteen_invokesFallbackSummary() throws java.io.IOException {
        String loopStep = toolCall("list_tables", "{\"datasourceId\":1}");
        String fallback = finalText("已达步数上限，请拆分问题后重试。");
        when(llmService.chatRaw(anyList(), anyString()))
                .thenReturn(loopStep, loopStep, loopStep, loopStep, loopStep,
                        loopStep, loopStep, loopStep, loopStep, loopStep,
                        loopStep, loopStep, loopStep, loopStep, loopStep, fallback);
        when(datasourceService.listTables(1L)).thenReturn(List.of(new DbTableVo()));

        service.run("一个会触发无限工具调用的复杂问题", "sess-maxsteps", "u", null, emitter, false, false);

        verify(datasourceService, times(15)).listTables(1L);
        verify(llmService, times(16)).chatRaw(anyList(), any());
    }
}
