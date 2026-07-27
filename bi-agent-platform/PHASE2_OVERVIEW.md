# Phase 2 · 多步推理 Agent 落地总结

> 工程：`D:/个人项目/agent_bi/bi-agent-platform`（Spring Boot 3.4.5 + Java 21）
> 目标：把"单次 LLM 调用"升级为**自主规划 + 工具调用 + 推理轨迹可见**的智能体。

## 一、架构决策（重大转向）
- **放弃 Spring AI**：本机 Maven 仓库中 `spring-ai 1.0.0 GA` 构件模块拆分异常，核心类（`ChatClient` / `ToolCallback` / `AssistantMessage`）在拉取的 jar 中缺失，属 PLAN §7 已预警风险。
- **改为手写 ReAct 循环**：直接基于已验证的 `LlmService`（OpenAI 兼容网关 / deepseek-v3，原生 `tools` 函数调用 + SSE 流式）。**零新依赖、零网络风险**，对工具协议完全掌控。

## 二、交付内容
| 模块 | 文件 | 说明 |
|---|---|---|
| 编排核心 | `agent/BiAgentService.java`（重写） | 手写 ReAct：system+记忆历史+tools → `chatRaw` 解析 `tool_calls` 循环 → 逐个 emit `tool_call`/`tool_result`/`reasoning` → 最终 4 字分片流式 `token` → `done`。步数上限 8，超限走兜底总结；异常兜底 + 内存记忆读写。 |
| LLM 壳 | `bi/service/llm/LlmService.java` | 新增 `chatRaw(messages, toolsJson)`（带 tools 参数，返回原始 JSON）+ `streamChat`（JDK HttpClient 手动解析 SSE）。修复 `HttpResponse` 非 `AutoCloseable`、`optJSONObject` 不存在两处编译坑。 |
| 执行器 | `config/AgentConfig.java` | 暴露虚拟线程执行器 `agentTaskExecutor`，`/api/agent/chat` 立即返回 `SseEmitter`，推理在虚拟线程异步跑。 |
| 7 个工具 | `agent/tool/*.java` | `list_tables` / `list_columns`（←BiDatasourceService）、`nl2sql` / `run_sql`（←BiQueryService）、`rag_search`（←BiKnowledgeService）、`select_chart`（←runReadOnlySql + ChartSelector 生成 ECharts option）、`analyze_alert`（←IBiAlertRuleService.analyzeAlert）。 |
| 新补方法 | `BiQueryService.runReadOnlySql` | SqlValidator 五层防护 + 从 `listTables` 拉全表做表名白名单，只读取数，最多 100 行。 |
| 新补方法 | `IBiAlertRuleService.analyzeAlert(ruleId)` | 复用私有 `executeCheckQuery`/`compare`/`determineAlertLevel`/`analyzeAnomaly`，返回 实际值/阈值/是否触发/级别/触发时 AI 分析 的 JSON。 |

## 三、验证结果
- ✅ `bash mvn -o package` 通过（fat jar 干净 repackage）
- ✅ `bash mvn -o test` **28 例全绿**（原 18 预警 + 7 知识 + 新增 3 例 `BiAgentServiceTest`）
  - 多步推理：模型先调 `list_tables` → 工具被真调用 → 结果回填 → 最终答案流式推回
  - 单步直答：首轮即返回文本仍正确流式 + 记忆落盘
  - 步数上限：连续 8 步 tool_calls 触发兜底总结，**不死循环**
- ✅ 无回归（BiAlertRuleServiceImpl 改动不影响既有预警单测）

## 四、本环境验证限制（必记）
1. **`application.yml` 未配 AI key**（`ai.ark` 块缺失）→ 真实 LLM 调用无法进行，以 mock 驱动 ReAct 单测替代。真要起：`ARK_API_KEY=<key> bash run.sh`。
2. 之前某次 `mvn spring-boot:run` 在随机端口 **55230 留了无关 java 进程**（返回 HTML 前端、`/actuator/health` 报 No mapping），占住端口导致 boot 报 "Port 55230 already in use"。本工程配置是 **8080**，该 stray 非本工程产物，**勿误杀**。

## 五、后续（按 PLAN）
- 前端对话式 UI（`src/views/bi/agent/` 流式渲染 tool_call/tool_result/reasoning/token）
- 可观测（trace / 决策日志）
- `AgentMemory` 由内存版平滑换 Redis
