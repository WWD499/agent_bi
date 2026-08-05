# AI 智能 BI 数据分析平台（agent_bi）

> 面向企业业务人员的智能数据分析平台。业务人员无需编写 SQL，仅用自然语言提问，即可完成「取数 — 分析 — 可视化 — 异常预警」全流程。

agent_bi 以**单 Agent 推理引擎**为核心：用户用自然语言提问，Agent 自主规划并调用 NL2SQL、RAG 检索、图表选型、数据预警等工具完成多步推理；前端以**流式对话 + 推理轨迹可视化**的方式呈现分析过程与结论。平台把原本依赖数据 / 技术团队的分析能力下沉到业务一线，显著降低数据分析门槛、缩短报表产出周期。

---

## 一、功能特性

| 模块 | 说明 |
|------|------|
| **自然语言分析（Agent）** | 单 Agent 多步推理引擎，自动拆解业务问题、调度工具、结合记忆给出带推理轨迹的答案（SSE 流式输出）。 |
| **NL2SQL + RAG 增强** | 将自然语言转为 SQL 并在 PostgreSQL 业务库执行取数；结合 pgvector 向量库（bge-m3 嵌入）检索业务知识，增强生成质量与准确性。 |
| **智能图表选型** | 依据数据形态（维度 / 指标 / 基数）自动推荐最合适的图表类型，并基于 ECharts 渲染；一次提问可生成多张图表，文字与图表在对话中交错呈现。 |
| **BI 数据大屏** | 基于 grid-layout-plus 的拖拽式大屏编排，支持图表 / 指标卡 / 图片三类组件；大屏可生成 `access_token` 对外**只读分享**，支持导出图片 / PDF。更支持由 **Agent 对话式创建与更新大屏**：调用 `create_dashboard` / `update_dashboard` 工具即可新建大屏、或向已有大屏追加图表，对话内返回的深链可直接跳转打开。 |
| **数据异常预警** | 规则引擎 + AI 双引擎分析，定时扫描关键指标；触发后可通过邮件（SMTP）等通道通知，并沉淀预警记录。 |
| **OCR 文档识别** | 集成 PaddleOCR 本地服务，识别图片 / 文档中的文字，识别结果可一键沉淀为知识库条目。 |
| **数据沙箱** | 用户在隔离的 `sandbox` schema 中自由分析、源业务数据零污染。导入三种方式：① **从数据源勾选克隆**——在「数据沙箱」页选已配置数据源、勾选表后一键克隆进沙箱（每表最多 1 万行）；② **粘贴导入**——直接粘贴 CSV/TSV；③ **上传导入（M3）**——上传 Excel（.xlsx/.xls）或 CSV，系统自动推断列类型建表写入。导入后支持表预览、NL2SQL、拖 SQL、选图、解读，并可一键切到「智能对话」让 Agent 基于沙箱分析。**Agent 写工具（M2）**：在沙箱内建表 / CTAS 落表 / 删表，每次执行前由 Agent 弹出确认框，用户同意才真正执行。**操作审计（M3）**：所有导入 / 写表 / 删库均写 `bi_sandbox_audit` 留痕，可在沙箱页查看「谁·何时·对哪张表·做了什么·是否成功」。 |

---

## 二、技术架构

| 层 | 选型 |
|----|------|
| 后端 | **Spring Boot 3.4.5** · **Java 21** · MyBatis 3.0.3 · Sa-Token 1.44（鉴权）· Caffeine 3.1.8（L1 本地缓存）|
| 前端 | **Vue 3.5** · **Vite 6** · Element Plus 2.9 · ECharts 5.6 · grid-layout-plus · Pinia · vue-router |
| 向量 / 关系库 | **PostgreSQL 17 + pgvector**（系统库 `agent_bi` + 业务向量库）|
| 缓存 / 记忆 | **Redis**（会话记忆 + 多级缓存 L2）|
| 业务数据源 | **MySQL 8**（动态接入，运行时配置）|
| 大模型网关 | OpenAI 兼容网关（本项目接入 `deepseek-v3`，支持 function calling）|
| OCR 服务 | **PaddleOCR**（Python FastAPI，`ocr-service/`）|
| Agent 实现 | **手写 ReAct 工具调用循环**（未引入 Spring AI，规避其 1.0 GA 构件风险，零新增依赖）|

**Agent 工具集**：业务模式注册 9 个工具——7 个业务只读工具（`Nl2SqlTool` / `RunSqlTool` / `RagSearchTool` / `ListTablesTool` / `ListColumnsTool` / `SelectChartTool` / `AnalyzeAlertTool`）+ `CreateDashboardTool` / `UpdateDashboardTool` 两个大屏工具；锁定「数据沙箱」时切换为 12 个沙箱工具——5 个只读（`SandboxListTablesTool` / `SandboxListColumnsTool` / `SandboxNl2SqlTool` / `SandboxRunSqlTool` / `SandboxSelectChartTool`，名称与业务版一致但指向 `sandbox` schema）+ 5 个写工具（`SandboxCreateTableTool` 建表 / `SandboxUpdateTableTool` 改名改表 / `SandboxImportDataTool` 数据源导入 / `SandboxMaterializeTool` 落表 CTAS / `SandboxDropTableTool` 删表，均 `requiresConfirmation=true`，执行前弹确认框）+ 两个大屏工具。Agent 在沙箱与业务两种模式下均支持对话式创建与更新 BI 大屏。

---

## 三、目录结构

```
agent_bi/
├── bi-agent-platform/        # 后端（Spring Boot 3.4.5 / Java 21）
│   ├── sql/verify.sql        # 初始化后自检（SELECT 校验，可选）
│   ├── src/main/resources/
│   │   ├── application.yml   # 主配置（已被 .gitignore 忽略，密钥不入库）
│   │   ├── schema.sql        # 【单一可信源】建表 DDL（幂等 IF NOT EXISTS，spring.sql.init 启动时自动执行）
│   │   └── data.sql          # 【单一可信源】种子数据（幂等，启动自动执行；取代原先散落的 SQL 脚本）
│   └── pom.xml
├── agent-ui/                 # 前端（Vue 3.5 / Vite 6）
├── ocr-service/              # PaddleOCR 服务（Python FastAPI）
│   ├── ocr_server.py
│   ├── requirements.txt
│   └── install_ocr.bat          # Windows 一键安装依赖（独立 venv）
├── PLAN.md                  # 重构实施计划（架构决策依据）
└── README.md
```

---

## 四、环境要求

| 组件 | 版本 / 说明 |
|------|--------------|
| JDK | **21**（LTS） |
| Node.js | **22+**（构建前端） |
| PostgreSQL | **17+**，需启用 **pgvector** 扩展 |
| Redis | 5+（会话记忆 / 缓存） |
| MySQL | 8（业务数据源，可选；无业务库时 NL2SQL 取数不可用） |
| Python | **3.11.x**（PaddleOCR 不支持 3.13；仅 OCR 服务需要） |
| 大模型网关 | 提供 OpenAI 兼容 `/v1` 接口（本项目用 `deepseek-v3`） |

---

## 五、快速开始

### 5.1 准备中间件

- 启动 PostgreSQL（启用 pgvector）、Redis；如需业务取数，准备 MySQL 8 业务库。
- 创建系统库并初始化表（建表/灌数已合并为**单一可信源**，应用启动时由 `spring.sql.init` **自动执行**，无需手动跑脚本）：

```sql
-- 1) 创建系统库（库名固定为 agent_bi；只需这一句，应用启动会自动建表 + 灌种子数据）
CREATE DATABASE agent_bi;
```

> **数据库初始化已自动化**：`src/main/resources/schema.sql`（建表，全程 `IF NOT EXISTS`）
> 与 `data.sql`（种子数据，`ON CONFLICT` / `WHERE NOT EXISTS` 幂等）会在应用启动时自动执行，
> 对已有库安全 no-op，新表缺了自动补齐，不再出现“编译过、运行炸”的缺表问题。
>
> 若需离线/手动初始化（如 CI 预置或新环境），可改用单一可信源脚本：
> `PGCLIENTENCODING=UTF8 psql -h localhost -p 5432 -U postgres -d agent_bi -f bi-agent-platform/src/main/resources/schema.sql`
> 再执行 `... -f bi-agent-platform/src/main/resources/data.sql`（均幂等，可重跑）。
> 手动方式仅用于特殊场景；正常启动应用即完成全部初始化。

### 5.2 配置后端

编辑 `bi-agent-platform/src/main/resources/application.yml`（该文件已被 `.gitignore` 忽略，请在本机维护，**切勿提交密钥**），关键配置段：

| 配置段 | 作用 |
|--------|------|
| `server.port` | 后端端口，默认 `8080` |
| `spring.datasource` | PostgreSQL 系统库 `agent_bi` 连接 |
| `spring.data.redis` | Redis 连接（会话记忆 + 缓存） |
| `ai.ark.*` | 大模型网关：`base-url` / `api-key` / `model`（本项目用 `deepseek-v3`）/ `timeout-ms` |
| `spring.mail.*` | 预警邮件 SMTP（`host` / `port` / `username` / `password`）；`password` 填邮箱**授权码**或 `${MAIL_AUTH_CODE}` 环境变量 |
| `bi.alert.mail.default-recipient` | 预警兜底收件人 |
| `ocr.paddleocr-url` | PaddleOCR 服务地址，默认 `http://localhost:8866` |

### 5.3 启动后端

```bash
cd bi-agent-platform
mvn spring-boot:run          # 或 mvn clean package 后 java -jar target/agent-bi-platform.jar
```

### 5.4 启动前端

```bash
cd agent-ui
npm install
npm run dev                  # 默认 http://localhost:5173
```

> 前端开发态已将 `/api` 代理到 `http://localhost:8080`，无需额外配置 CORS。

### 5.5 启动 OCR 服务（可选，仅当需要文档识别时）

```bash
cd ocr-service
python -m venv venv                 # 必须用 ≤3.12 的 Python 建 venv（PaddleOCR 不支持 3.13）
call venv\Scripts\activate.bat
pip install -r requirements.txt
python ocr_server.py                # 监听 http://localhost:8866
```

> 也可直接双击 `install_ocr.bat`（Windows）一键完成安装与启动。OCR 模型会复用本机既有缓存目录，避免重复下载。

---

## 六、配置说明（安全边界）

- **SQL 只读校验**：Agent 执行的 SQL 经只读校验，拦截 `INSERT/UPDATE/DELETE/DDL`，仅允许 `SELECT`。
- **工具白名单**：Agent 仅可调用已注册的工具（7 业务只读 + 5 沙箱只读 + 5 沙箱写工具 + 2 大屏工具，共 19 个工具类；其中 7 个写/建工具需用户确认），禁止越权。
- **沙箱边界隔离 + 写工具确认（M2）**：数据沙箱复用系统库 `agent_bi` 内的独立 `sandbox` schema，SQL 一律 `sandbox.表名` 全限定；`assertAllTablesInSandbox` 强制所有 FROM/JOIN 表名带 `sandbox.` 前缀，杜绝经由沙箱工具越权访问 `public` 业务表或 `bi_*` 系统表。M2 已开放 5 个沙箱写工具（建表 / 改名改表 / 数据源导入 / 落表 CTAS / 删表），但**写工具均标记 `requiresConfirmation=true`**，Agent 执行前先 emit `confirm` 事件挂起等待，前端弹确认框，用户同意才真正落库；用户拒绝则降级为只读方案。
- **导入与审计（M3）**：沙箱支持 Excel（.xlsx/.xls）/ CSV 上传导入，后端用 Apache POI / commons-csv 解析并自动推断列类型建表；所有导入 / 写表 / 删库操作均写 `bi_sandbox_audit` 审计表（`operator / operation / target / detail / success / fail_reason / create_time`），审计写入异常不影响主流程（旁路容错），可在「数据沙箱」页查看审计日志。
- **资源上限**：单轮推理步数上限、结果行数 / 字符数裁剪，防止死循环与超长返回。
- **多级缓存**：Caffeine（L1 本地）+ Redis（L2）两级缓存，降低向量检索与重复查询开销。
- **鉴权**：Sa-Token 最简登录拦截，未登录访问受保护接口返回 401；大屏分享页通过 `access_token` 免登录只读访问。

---

## 七、测试

后端单元测试（JUnit 5 + Mockito）覆盖预警规则、SQL 校验、图表选型、多级缓存、大屏 CRUD、知识库等核心逻辑：

```bash
cd bi-agent-platform
mvn test
```

当前共 **14 个测试类 / 84 个用例**。

---

## 八、常见问题

- **预警邮件发不出**：检查 `spring.mail.host` 是否为 `spring:` 的**直接子级**（缩进错误会变 `spring.datasource.mail` 导致 JavaMailSender 不创建）；QQ / 163 推荐使用 `465 + SSL`；`password` 须为邮箱授权码。
- **OCR 识别报连接拒绝**：确认 `ocr-service` 的 Python 服务已在 `localhost:8866` 启动，且 `ocr.paddleocr-url` 配置一致。
- **NL2SQL 取数为空**：确认已在「数据源」中配置可用的 MySQL 业务库并测试连通。
- **提交代码前**：`application.yml`、各 `venv/`、`.pem`、构建产物（`target/`、`node_modules/`、`dist/`）均已被 `.gitignore` 忽略，密钥不会入库。

---

## 九、许可证

本项目为个人作品集项目，仅供学习与演示。
