# agent_bi v1.0.0 发布说明

- **发布日期**：2026-08-05
- **里程碑**：首个正式发布（Tag：`v1.0.0`）
- **分支**：`main`
- **仓库**：github.com/WWD499/agent_bi
- **技术栈**：Spring Boot 3.4.5 / Java 21 / 直连 OpenAI 兼容网关（deepseek-v3 + BAAI/bge-m3）/ PostgreSQL 15+（pgvector）/ MySQL 8 / Redis 5 / Vue 3 + Vite 6 + Element Plus + ECharts 5

---

## 一、概述

agent_bi 是 AI 智能 BI 数据分析平台的 Agent 化重构版。用户用自然语言即可完成「**问数 → 取数 → 制图 → 预警 → 编排大屏**」的闭环。

本版本为首个正式里程碑，整合了此前各阶段工作：

- **P0 安全**：沙箱写操作确认护栏（服务端 `trusted-mode` 强制，客户端无法绕过）。
- **P1 测试**：大屏工具、LLM 网关冒烟、确认流 + `MAX_STEPS` 边界回归，共 19 用例全绿。
- **P2 质量**：异常堆栈不再吞噬、`SimpleDateFormat` 全面迁移 `java.time`、CORS 源外部化、消灭状态魔法数字。
- **P3 功能**：查询历史、站内信通知。
- **方案 B**：启动自动建表（`spring.sql.init` + 幂等 `schema.sql`/`data.sql`）。

本版本额外补齐工程化基础设施：**GitHub Actions CI 门禁**、**前后端版本号对齐**。

---

## 二、核心能力

| 能力 | 说明 |
| --- | --- |
| 自然语言转 SQL（NL2SQL） | 生成 SQL 前先做 schema / 数据探查，根治时间窗口 mismatch 导致 0 行的问题 |
| RAG 业务知识库 | BAAI/bge-m3（1024 维）向量召回 + 关键词兜底，已接入 NL2SQL 主流程 |
| 手写 ReAct Agent | 工具调用循环（不引入 Spring AI，零新依赖、协议完全可控）；SSE 流式输出；确认流 + 自我修正；`MAX_STEPS` 兜底 |
| 数据沙箱 | 逻辑命名空间隔离；按库（db_id）作用域解析，根治跨库列名串扰；Excel/CSV 导入（中文列名/显示名） |
| 智能图表生成 | 基于查询结果自动选型 ECharts 图表 |
| BI 大屏 | 拖拽编排（grid-layout-plus），支持导出 |
| 数据异常预警 | 规则引擎（阈值/波动）+ 通知；服务端 `trusted-mode` 强制确认护栏 |
| 通知中心 | 站内信（`bi_notify` 表）+ 邮件通道（配 `spring.mail.host` 时启用，否则自动降级） |
| 查询历史 | 按用户隔离的查询记录（SQL / 耗时 / 行数 / 状态） |
| OCR 文档识别 | 独立 Web 能力（PaddleOCR 本地服务） |
| 鉴权与记忆 | Sa-Token 鉴权 + Redis 持久化会话 / 记忆 |

---

## 三、工程化与质量

- **启动自动建表**：`spring.sql.init` 驱动 `schema.sql` + `data.sql`（幂等，全部 `IF NOT EXISTS` / `ON CONFLICT`）。建库后启动即自动建表 + 灌数，消除「代码与 DDL 脱节」导致的漏表问题。
- **P2 代码质量清理**：
  - 异常堆栈不再吞噬（21 处 catch 改为 `log.error("...", e)` + 3 处 rethrow 带 cause 链）；
  - `SimpleDateFormat` 全面迁移到 `java.time`（`DateTimeFormatter` + `LocalDateTime`/`LocalDate`）；
  - CORS 源外部化（`app.cors.allowed-origins`，不再硬编码 `localhost:5173`）；
  - 新增 `EnableFlag` 枚举消灭状态魔法数字（`setStatus(1)` 等）；
  - `wasNull()` 缺失项已合规。
- **CI 门禁**：GitHub Actions 最小流水线（后端 `mvn verify` + 前端 `npm run build` + 前后端版本号一致性校验），任一失败阻断合入。
- **前后端版本对齐**：后端（pom）与前端（package.json）统一为 `1.0.0`，并由 CI 校验一致性。

---

## 四、部署与升级

### 前置依赖

- JDK 21、Node 22
- PostgreSQL 15+（建议 17，启用 `pgvector` 扩展）
- Redis 5+
- 大模型网关 Key（OpenAI 兼容，deepseek-v3 / bge-m3），通过环境变量 `ARK_API_KEY` 注入

### 后端

```bash
# 1. 建库（仅需一次）
createdb agent_bi            # 或 psql 中执行 CREATE DATABASE agent_bi;

# 2. 本地配置 application.yml（已 gitignore，含真实 Key/密码）
#    spring.sql.init.mode=always
#    agent.sandbox.trusted-mode=false
#    app.cors.allowed-origins=http://localhost:5173,http://127.0.0.1:5173

# 3. 打包运行
cd bi-agent-platform
mvn -B package
java -jar target/agent-bi-platform.jar
# 启动即自动建表 + 灌数
```

### 前端

```bash
cd agent-ui
npm install
npm run build        # 产物 dist/，由 Nginx 托管或随后端静态资源
```

### 校验样例数据

```bash
psql -d agent_bi -f bi-agent-platform/sql/verify.sql   # 4 条 SELECT 核对星型模型
```

---

## 五、已知限制 / 待办

- 邮件通知需配置 `spring.mail.host` 才发送，否则自动降级为站内信 + 日志。
- LLM 网关冒烟测试默认跳过，需 `-Dllm.smoke=true` + 真实 Key 才执行（手动：`bash mvn test -Dllm.smoke=true -Dtest=LlmServiceConnectivityTest`）。
- 运行库中存在 6 张无元数据的孤儿物理表 `default__sales_dm__demo_*`，待人工确认后清理。
- `application.yml` 含本地密钥与密码，按约定不入库；其非密钥配置（`trusted-mode` / `cors` / `sql.init`）建议后续迁到共享模板或提交默认 `application.yml`。

---

## 六、从本版本开始的约定

- **版本号**：前端 `package.json` 与后端 `pom.xml` 必须保持一致（CI 会校验）。
- **提交流程**：由开发者手动 `git commit` + `git tag vX.Y.Z` + `git push --tags`（本版本即按此流程发布）。
- **CI 门禁**：PR 合入 `main` 前必须通过 build + test 门禁。
