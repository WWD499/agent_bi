package com.bi.agent.agent;

import com.bi.agent.bi.service.BiQueryService;
import com.bi.agent.bi.service.IBiAlertRuleService;
import com.bi.agent.bi.service.IBiDatasourceService;
import com.bi.agent.bi.service.IBiKnowledgeService;
import com.bi.agent.bi.service.llm.LlmService;
import com.bi.agent.bi.service.sql.ChartSelector;
import com.bi.agent.bi.vo.DbTableVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
    private ChartSelector chartSelector;
    @Mock
    private SseEmitter emitter;

    private BiAgentService service;

    @BeforeEach
    void setup() {
        Executor direct = Runnable::run; // 同步执行，便于测试断言
        service = new BiAgentService(llmService, memory, direct,
                datasourceService, queryService, knowledgeService, alertRuleService, chartSelector);
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
        service.run("库里有哪些业务表？", "sess-1", "unit-test", emitter);

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

        service.run("你是谁？", "sess-2", "unit-test", emitter);

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

        service.run("复杂问题", "sess-3", "unit-test", emitter);

        // 工具最多被调 8 次（步数上限），不会无限循环
        verify(datasourceService, org.mockito.Mockito.atMost(8)).listTables(1L);
        verify(emitter, atLeastOnce()).send(ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
    }
}
