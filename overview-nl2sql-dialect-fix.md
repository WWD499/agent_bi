# NL2SQL 方言不匹配 Bug 修复总览

## 问题
用户用 dsId=10（PostgreSQL 库 `agent_bi`）查询「分析上季度各区域销售额趋势」时，NL2SQL 生成了 MySQL 语法 SQL（`DATE_FORMAT(so.order_date, '%Y-%m')` 与 `DATE_SUB(..., INTERVAL 3 MONTH)`，后者缺引号），PostgreSQL 执行报语法错 `位置 338`，最终 `BizException: code=500, msg=SQL执行失败`。

## 根因
`PromptBuilder` 的 NL2SQL Prompt 硬编码要求「MySQL 8.0+」语法（第 40/125 行），few-shot 用 MySQL-only 的 `DATE_FORMAT`（第 61 行），导致模型对 PostgreSQL 数据源产出 MySQL-only 函数；`SqlValidator` 只校验注入/表名白名单，未校验方言，放行了错误 SQL。

## 修复（commit e978ac4）
- **`PromptBuilder.java`**：新增私有方法 `appendDialectRules(prompt, dialect)`，按 `datasource.getType()` 动态拼装方言约束与 few-shot——
  - PostgreSQL（含默认）：`使用 PostgreSQL 语法（当前数据源为 PostgreSQL）`、`TO_CHAR(date_field, 'YYYY-MM')`、`date_trunc('month', CURRENT_DATE) - INTERVAL '3 months'`（带引号）
  - MySQL：保留 `使用 MySQL 8.0 语法`、`DATE_FORMAT(...)`、`DATE_SUB(..., INTERVAL 3 MONTH)`
  - dialect 归一化：`null`/空/未知 → 兜底 PostgreSQL
  - `buildNl2SqlPrompt` / `buildRagEnhancedPrompt` 各增加带 `dialect` 的 5 参重载，旧 4 参方法委托传 `null`
- **`BiQueryService.java:84`**：调用改为 `promptBuilder.buildNl2SqlPrompt(userQuery, tableName, allTableSchemas, ragContext, datasource.getType());`
- **新增单测** `src/test/java/com/bi/agent/bi/service/llm/PromptBuilderDialectTest.java`（4 用例：PG / MySQL / null 兜底 / 未知方言含空白兜底）

## 验证
- 主代码 `mvn package -DskipTests`：**BUILD SUCCESS**（67 源文件编译通过）
- 单测 `test -Dtest=PromptBuilderDialectTest`：**Tests run: 4, Failures: 0, Errors: 0**
- 独立扫描全仓：仅 `PromptBuilder` 的 `mysql` 分支含 `DATE_FORMAT`（符合预期）；其余 `NOW()` 均位于 MyBatis 系统表 Mapper（PG 同样支持 `NOW()`），无其它命中 PG 的 MySQL-only 语法；无其他 NL2SQL 调用点硬编码错误方言。

## 注意事项
1. **需重启后端**：改动位于运行时字节码，请在 IDEA 重启 `BiAgentPlatformApplication`（8080 端口）使新逻辑生效；仅重编译不重启不会加载新类。
2. **Git 状态**：仓库刚 `git init`，本次仅提交 4 个修复文件（`PromptBuilder.java` / `BiQueryService.java` / `PromptBuilderDialectTest.java` / 根 `.gitignore`），项目其余文件均为 untracked。如需完整版本控制，建议另做一次全量初始提交。
3. **范围外（留作后续）**：`BiAgentService` 的 Agent 工具调用路径（`llmService.chatRaw`）在让模型生成 SQL 时未注入方言约束，本次未改动；若后续 Agent 直连 PG 出现同类问题可再议。
