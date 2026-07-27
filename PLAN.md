# AI 智能 BI 数据分析平台 · Agent 化重构实施计划

> 文档状态：待评审（用户确认后进入 Phase 0 编码）
> 目标工程目录：`D:/个人项目/agent_bi`
> 制定人：Senior Developer（高级开发工程师）
> 制定日期：2026-07-14

---

## 0. 为什么做这次重构（动机与"亮眼"策略）

用户原始诉求有两条，且都明确：

1. **接入 Agent 框架更顺** —— 现版 ai_bi 是若依基础版（Spring Boot 2.6 / Java 8），只做"单次大模型直调"（NL2SQL / RAG / 预警分析 / 图表选型），无规划、无工具调用、无记忆。要升级成 Agent 形态，若依那套旧栈对 Spring AI / LangChain4j 不友好。
2. **让面试官眼前一亮** —— 这是作品集 / 求职导向，不是技术洁癖。

### 关键认知（决定资源投放）

- ❌ **脱离若依本身不亮眼**：若依在国内太常见，面试官看到"若依二开"反而降档。
- ✅ **真正亮眼 = 现代栈标签 + 真能跑的 Agent 多步推理（流式可见）+ 干净架构**。
- 因此：**全部惊艳感堆在 Agent 层**；若依那套系统管理（用户/角色/菜单/字典/日志）**全砍，用 Sa-Token 最简鉴权顶替**——重写它是 2~4 周时间黑洞且面试官无感。

### 面试可讲的故事线（埋点）

> "我把一个若依二开的 BI 系统，重构为 Spring Boot 3 + Java 21 的 Agent 平台。核心是一个基于 Spring AI 的单 Agent 通用助手：用户用自然语言提问，Agent 自主规划，通过工具调用（NL2SQL、RAG 检索、图表选型、数据预警分析）多步推理，结合 pgvector 向量库做业务知识增强、Redis 做会话记忆，最终流式返回带推理轨迹的答案。"

---

## 1. 技术栈总览

| 维度 | 选型 | 版本 | 理由 |
|------|------|------|------|
| 语言 | **Java 21** | LTS | 虚拟线程、record、模式匹配——面试能聊的点 |
| 框架 | **Spring Boot 3.4.x** | 3.4 | Jakarta 命名空间，现代基线 |
| 构建 | **Maven**（默认，待确认） | 3.9.x | 与现有 ai_bi 一致，风险最低 |
| Agent | **Spring AI 1.0.x** | 官方 BOM 锁版 | 最 Java 原生、Spring 面试官买账；ChatClient + ToolCalling + VectorStore + Advisors |
| 鉴权 | **Sa-Token** | spring-boot3 starter | 最简登录拦截，砍掉若依系统管理 |
| RAG 向量 | **pgvector**（复用现有 PG） | 0.3.x | 零新组件，已有 PG+pgvector |
| 缓存/记忆 | **Redis 5**（复用现有） | 5.x | 会话记忆 + 业务缓存 |
| 多数据源 | **dynamic-datasource** | spring-boot3 starter | 替代若依的动态数据源 |
| 邮件 | **spring-boot-starter-mail** | SB3 自带 jakarta.mail | 预警通知 |
| 前端 | **Vue 3**（脱若依壳） | 3.5 | 保留 Vue 技术栈，重写路由/登录/对话页 |

> **LangGraph 说明**：LangGraph 目前仅官方支持 Python / TypeScript，Java 无一等公民版。本项目用 **Spring AI 的 Advisors + 手写 ReAct 循环** 实现等价的"图状态机式"多步推理，是最 Java 原生、最易被 Spring 面试官认可的路径。若后续执着"真 LangGraph"，再考虑拆 Python 微服务（不在本期）。

### 模型接入（复用现有网关）

```
spring.ai.openai.base-url=https://ai-api-prod.qingjiao.art/v1
spring.ai.openai.api-key=${AI_API_KEY}        # 走环境变量，不进仓库
spring.ai.openai.chat.options.model=deepseek-v3
```

现有 OpenAI 兼容网关 + deepseek-v3 **已确认支持 `tools` / function calling**（早前实测：NL2SQL、RAG、预警分析均已通）。Agent 层只需把现有直调壳升级为带 `tools` 的调用。

---

## 2. 目标目录结构

```
D:/个人项目/agent_bi/                         ← 新工程根（独立于 ai_bi）
├── PLAN.md                                  ← 本文件
├── pom.xml                                  ← SB3.4 + Java21 parent
├── Dockerfile                               ← 后期（Phase 5）多阶段构建
├── docker-compose.yml                       ← 后期：app + pg + redis
└── src/main/java/com/bi/agent/
    ├── BiAgentPlatformApplication.java
    ├── config/
    │   ├── SaTokenConfig.java              ← 最简鉴权（登录 + 拦截器）
    │   ├── SpringAiConfig.java             ← OpenAI 兼容网关 ChatClient bean
    │   ├── RedisConfig.java
    │   ├── CorsConfig.java
    │   ├── DynamicDsConfig.java           ← 多数据源（dynamic-datasource）
    │   └── GlobalExceptionHandler.java
    ├── common/
    │   ├── Result.java                    ← 替代 AjaxResult 的统一返回
    │   └── BizException.java
    ├── controller/
    │   ├── HealthController.java          ← 探活
    │   ├── auth/LoginController.java      ← Sa-Token 登录
    │   └── agent/AgentChatController.java ← /api/agent/chat（SSE 流式）
    ├── agent/                             ← Phase 2 填充
    │   ├── BiAgentService.java           ← ReAct 编排器
    │   ├── tool/                         ← 各 Agent 工具（@Tool）
    │   │   ├── Nl2SqlTool.java
    │   │   ├── RunSqlTool.java
    │   │   ├── RagSearchTool.java
    │   │   ├── ListTablesTool.java
    │   │   ├── ListColumnsTool.java
    │   │   ├── SelectChartTool.java
    │   │   └── AnalyzeAlertTool.java
    │   ├── memory/AgentMemory.java       ← Redis 会话记忆
    │   └── rag/RagService.java          ← pgvector 向量检索 + 增强
    ├── bi/                                ← Phase 1：从 ruoyi-bi 原样搬的业务逻辑
    │   ├── service/BiQueryService.java   ← NL2SQL 主流程
    │   ├── service/BiKnowledgeService.java← RAG 检索
    │   ├── service/BiDatasourceService.java← 动态数据源 + listTables/listColumns
    │   ├── service/BiAlertService.java   ← 预警 + AI 分析
    │   ├── service/ChartSelector.java    ← 智能图表选型
    │   ├── domain/                       ← 实体（去 BaseEntity，自写轻量基类）
    │   └── mapper/                       ← MyBatis mapper（PG 系统库 + 业务源）
    └── resource/
        ├── application.yml
        └── mapper/*.xml

前端（独立目录，建议 D:/个人项目/agent_bi/agent-ui 或并入）
└── src/
    ├── views/agent/ChatView.vue          ← 流式对话 + 推理轨迹面板
    ├── api/agent.js                       ← SSE 消费
    └── router/（自管静态路由，去掉 sys_menu 驱动）
```

**迁移策略**：`ruoyi-bi` 的零若依依赖业务逻辑（NL2SQL / RAG / 预警 / 图表 / 数据源）**原样搬入 `com.bi.agent.bi`**，只改：
- `javax.*` → `jakarta.*`（业务层仅 3 处，可忽略）
- 实体去 `BaseEntity` 继承，自写 5 字段轻量基类
- `ServiceException` / `RedisCache` / `StringUtils` 平替为自定义 / RedisTemplate / Apache commons
- BI 的 controller 从 `ruoyi-admin` 迁回本工程，改用 `@RestController` + `Result<T>`
- **不动现有 ai_bi**，作为可回退的可用版本。

---

## 3. 数据库与中间件（全部复用现有，零新装）

| 组件 | 现状 | 本项目用法 |
|------|------|-----------|
| PostgreSQL 17 + pgvector | `ry` 库，bi_knowledge 已有向量 | RAG 向量库（PgVectorVectorStore 直连） |
| MySQL 8 | 业务数据源 localhost:3306/ry，demo_employee | 动态数据源接入 |
| Redis 5 | 缓存 + 会话 | Agent 会话记忆（RedisChatMemory） |
| 大模型网关 | OpenAI 兼容 /v1，deepseek-v3 | ChatClient base-url |

> 无需新建任何中间件，降低环境风险。

---

## 4. 分阶段实施计划

### Phase 0 — SB3 + Java21 空骨架（0.5 天）
- **目标**：能 `mvn spring-boot:run` 起来的空壳，端口 8080，Sa-Token 登录拦截生效，/api/agent/chat 先返回占位。
- **产出**：pom.xml（SB3.4 + Java21）、Application、config/*、common/Result、HealthController、LoginController(stub)、AgentChatController(SSE stub)。
- **验收**：启动无错；`curl /health` 返回 ok；未登录访问 /api/agent/chat 被 Sa-Token 拦截返回 401。

### Phase 1 — 搬 BI 业务层（2 天）
- **目标**：NL2SQL / RAG 检索 / 动态数据源 / 预警 / 图表选型 全部可用，纯 service 层，不接 Agent。
- **产出**：`com.bi.agent.bi.*` 全套（逻辑原样，仅去若依依赖）。
- **验收**：写单测 / 临时接口验证 NL2SQL 生成、RAG 召回、listTables/listColumns 正常。

### Phase 2 — Agent 层（核心，3 天）★ 亮眼区
- **目标**：Spring AI ChatClient + @Tool 工具集 + ReAct 循环 + RAG(pgvector) + 记忆(Redis)。
- **产出**：
  - `BiAgentService.run(userQuery, sessionId)`：调 ChatClient → 解析 tool_calls → 分发工具 → 结果回填 → 循环至最终答案。带步数上限（8）、超时、异常兜底。
  - 7 个工具适配器（见 §5）。
  - `RagService`：PgVectorVectorStore 检索 + 上下文增强（复用现有 bge-m3 向量）。
  - `AgentMemory`：Redis 按 sessionId 存对话历史。
- **验收**：给一个复杂问题（"分析上季度各区域销售趋势并解释异常"），Agent 自主调用 nl2sql→rag_search→select_chart 多步，产出带推理过程的答案。

### Phase 3 — 流式对话前端（2 天）★ 亮眼区
- **目标**：Vue3 脱若依壳，流式对话页，工具调用轨迹实时可视化。
- **产出**：ChatView.vue（SSE 消费，逐字流式 + 「推理轨迹」折叠面板：🔧调用 nl2sql… / 📊选定图表 / 🧠综合结论）、登录页、自管路由。
- **验收**：提问后答案逐字流出，中间步骤可见，可中断。

### Phase 4 — 预警/图表接入 Agent（1 天）
- **目标**：数据预警、智能图表选型也挂到 Agent 工具链。
- **验收**：Agent 能主动调用 analyze_alert / select_chart 完成复合任务。

### Phase 5 — Docker 与部署（可选，1 天）
- **产出**：多阶段 Dockerfile + docker-compose（app + pg + redis），一键起。
- 用户已提"后面考虑有 Docker"，列为可选阶段。

---

## 5. Agent 工具清单（接现有代码，零从零写）

| 工具名 | 接哪个现有逻辑 | 入参 Schema |
|--------|----------------|-------------|
| `nl2sql` | BiQueryService（NL2SQL 主流程 + RAG 上下文） | `{question: string, datasourceId?: long}` |
| `run_sql` | 执行生成的 SQL 取数（需确认/新增"执行并返回结果"方法） | `{sql: string, datasourceId: long}` |
| `rag_search` | BiKnowledgeServiceImpl.searchSimilar / buildRagContext | `{query: string, topK?: int}` |
| `list_tables` | BiDatasourceServiceImpl.listTables | `{datasourceId: long}` |
| `list_columns` | BiDatasourceServiceImpl.listColumns | `{datasourceId: long, table: string}` |
| `select_chart` | ChartSelector | `{dataShape: object}` |
| `analyze_alert` | BiAlertRuleServiceImpl（预警 AI 分析） | `{ruleContext: object}` |

每个工具提供：① 给模型看的自然语言描述 ② 参数 JSON Schema。Spring AI 用 `@Tool` / `ToolCallback` 注册。

---

## 6. 安全边界（Agent 能执行 SQL，必须有）

- **SQL 只读校验**：拦截 INSERT/UPDATE/DELETE/DDL，只允许 SELECT。
- **工具白名单**：只允许调用注册过的工具。
- **资源上限**：结果行数上限、返回字符裁剪、单轮步数上限（防死循环）。
- **高危操作人工确认**：如全量导出（如有）需二次确认。

---

## 7. 风险与对策

| 风险 | 对策 |
|------|------|
| Spring AI 版本 API 变动 | 用官方 BOM 锁版；Phase 2 先写最小探针验证 ChatClient + tools 通路 |
| `run_sql` 现有代码可能无"执行取数"方法 | Phase 1 核实，若无则新增一个只读执行器 |
| 现有 ai_bi 被改坏 | 不动 ai_bi，新工程平行开发，ai_bi 作回退基线 |
| 若依 Quartz 定时任务丢失 | 预警改用 `@Scheduled`（简化，失去动态 cron UI）——本期可接受 |
| Docker 后期才做 | 列为 Phase 5 可选，不阻塞主线 |

---

## 8. 工作量与里程碑

| 阶段 | 工作量 | 累计 | 交付物 |
|------|--------|------|--------|
| Phase 0 | 0.5 天 | 0.5 | 可启动空壳 |
| Phase 1 | 2 天 | 2.5 | BI 业务层搬完 |
| Phase 2 ★ | 3 天 | 5.5 | Agent 多步推理可跑 |
| Phase 3 ★ | 2 天 | 7.5 | 流式对话前端 |
| Phase 4 | 1 天 | 8.5 | 预警/图表接入 |
| Phase 5（可选） | 1 天 | 9.5 | Docker 化 |

**总估：约 8.5~9.5 人天（单人）。** 亮眼核心（Phase 2+3）占 ~5 天，应优先保证质量。

---

## 9. 待确认项

1. **构建工具**：默认 Maven（与 ai_bi 一致）。如需 Gradle 请指明。
2. **前端位置**：建议 `D:/个人项目/agent_bi/agent-ui`（Vue3 独立前端）。是否同意？
3. **run_sql 方法**：Phase 1 核实现有是否已有"执行 SQL 返回结果"，决定是否需要新增。
4. **Docker**：确认纳入 Phase 5 可选，还是暂不排期。

---

## 10. 确认后即开工顺序

1. 建 `D:/个人项目/agent_bi` 目录 + pom.xml + 骨架（Phase 0）
2. 本地 `mvn spring-boot:run` 验证空壳
3. 进入 Phase 1，逐层搬运并单测
4. Phase 2 先写 ChatClient + tools 最小探针，验证后再扩工具
5. 前端 Phase 3 并行
6. 汇总为 git 提交（agent_bi 独立仓库，不污染 ai_bi）
