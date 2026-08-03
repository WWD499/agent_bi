# 数据沙箱 · 逻辑命名空间「沙箱库」设计

> 演进目标：在「扁平表池」式沙箱基础上，支持**先建库、再导入表、按库选择分析与报表**。
> 方案选型：**逻辑命名空间**（而非物理多 database），零 PG 连接改动，复用既有 `sandbox.` 安全边界。

---

## 1. 核心理念

- 物理上仍只有**一个 `sandbox` schema**，所有沙箱表都落在 `sandbox."物理名"` 下。
- 逻辑上引入「沙箱库」（`bi_sandbox_db`）作为分组，表与库是多对一关系。
- 物理表名直接等于用户给定的**短名**（如 `sales`），全沙箱短名全局唯一；**不再拼接 `db_key`**，
  既保留 `sandbox.` 安全边界，又彻底避免模型自行拼接 `dbkey__表名` 时丢失双下划线而找不到自己建的表。

> 为什么不用物理多 database：改连接、改权限、破坏已有的 `assertAllTablesInSandbox` 边界校验，风险高；
> 逻辑命名空间只新增元数据表 + 命名约定，安全边界与单连接架构完全不动。

---

## 2. 数据模型

### `bi_sandbox_db`（沙箱库 / 逻辑命名空间）
| 列 | 说明 |
|----|------|
| id | 主键 |
| db_key | 物理前缀键（英文/数字/下划线，唯一），如 `marts`、`sales_dm` |
| name | 展示名（可中文，如「销售主题域」） |
| owner / remark | 预留多用户 / 备注 |
| create_time / update_time | 时间戳 |

### `bi_sandbox_table`（沙箱表元数据）
| 列 | 说明 |
|----|------|
| id | 主键 |
| db_id | 所属沙箱库 id（指向 `bi_sandbox_db.id`） |
| table_name | 短名（全沙箱唯一，即用户给定的表名） |
| physical_name | 物理表名，新表 == 短名；历史遗留表可能为旧拼接名 `db_key__表名` |
| columns_json / row_count / source_type / remark | 同原设计 |

唯一约束：`(table_name)` —— 全沙箱短名唯一（**跨库不允许同名**，由代码 `countByTableName` 在写前校验）。

---

## 3. 物理命名约定

- 建表：`CREATE TABLE sandbox."{table_name}" (...)`（物理名即短名，不再拼接库前缀）。
- 查询/NL2SQL：一律 `sandbox."{table_name}"`（表名即 list_tables 返回的短名，由模型原样引用，无需任何拼接）。
- 边界校验 `assertAllTablesInSandbox` 要求所有 `FROM/JOIN` 必须 `sandbox.` 前缀，杜绝越权访问 `public` 业务表 / `bi_*` 系统表。

---

## 4. 数据源编码（前端 ↔ 后端）

`datasourceId` 统一承载「分析作用域」：

| 值 | 含义 |
|----|------|
| `> 0` | 业务数据源（原逻辑，不变） |
| `== 0` | 全部沙箱（`sandbox` schema 下所有库） |
| `< 0` | 具体沙箱库，`dbId = -datasourceId` |

- `BiAgentService` 解码：负 id → `sandboxDbId = -id`，传给 5 个沙箱写工具 `Sandbox*Tool`；
  `0` → `sandboxDbId = null`（全部沙箱）。
- NL2SQL / `list_tables` 按 `sandboxDbId` 收敛作用域（仅暴露该库表结构）。
- 前端 `ChatView` 下拉：除业务源外，追加
  - `数据沙箱（全部）` → id `0`
  - 每个沙箱库 `沙箱·{name}` → id `-dbId`
- `SandboxView.analyzeInChat` 写入 `localStorage.bi_ds_id = -(selectedDbId)`，跳转对话即锁定该库。

---

## 5. 后端接口（`/api/bi/sandbox`）

| 方法 & 路径 | 说明 |
|----|------|
| `POST /db` | 新建沙箱库 `{name, dbKey, remark?}`（dbKey 需唯一合法标识符） |
| `GET /db` | 列出全部沙箱库 |
| `DELETE /db/{id}` | 删除沙箱库（级联删物理表 + 元数据，默认库 `default` 禁止删除） |
| `POST /import` | 粘贴导入，`body` 可带 `dbId` |
| `POST /import-datasource` | 数据源克隆导入，`body` 可带 `dbId` |
| `POST /import-file` | **（M3）** 上传文件导入，表单 `file`（.csv/.xlsx/.xls）+ `tableName` + `dbId`；后端 POI / commons-csv 解析、自动推断列类型、建表写入 |
| `GET /audit?limit=` | **（M3）** 审计日志，返回最近 N 条（`operator / operation / target / detail / success / fail_reason / create_time`） |
| `GET /tables?dbId=` | 列出沙箱表（不传 dbId 则全部库；返回含 `physicalName/displayName/dbId/dbKey`） |
| `GET /tables/{physicalName}/columns` | 列结构（按物理名） |
| `GET /tables/{physicalName}/data?limit=` | 预览（按物理名） |
| `POST /execute` | 沙箱内只读 SQL |
| `DELETE /tables/{physicalName}` | 删表（按物理名） |

> 注：表相关端点现以**物理名**定位（原 `name` 即短名已升级为物理名），前端调用统一传 `physicalName`。

---

## 6. 存量兼容迁移（`sql/sandbox_init.sql`）

1. 种子默认库 `default`（`db_key='default'`）。
2. `DO $$` 块把已有 `sandbox.*` 物理表重命名为 `default__*`（幂等，已带前缀则跳过）。
3. `bi_sandbox_table` 存量行补 `db_id=1`、`physical_name='default__' || table_name`。
4. 唯一索引由 `uk_sandbox_table_name` 改为 `(db_id, table_name)`。

---

## 7. 已知限制 / 后续

- 表短名、库 dbKey 当前规范化为 ASCII 标识符；库展示名可中文。后续可做中文友好名 + 拼音/哈希 key。
- 多用户（`owner`）仅留字段，未接鉴权隔离。
- **M2（写工具）已落地**：Agent 沙箱写工具 `SandboxCreateTableTool`(建表) / `SandboxUpdateTableTool`(改名·改表) / `SandboxImportDataTool`(数据源导入) / `SandboxMaterializeTool`(CTAS 落表·物化视图) / `SandboxDropTableTool`(删表) 均已接入 `requiresConfirmation=true` 确认循环（`AgentSession` 状态机 + `AgentChatController /confirm` 端点）；工具执行失败时可进入**自我修正循环（最多 3 次）**自动调整参数重试，仍未成功则向用户报告错误。
- **M3（Excel 上传 / 审计）已落地**：`SandboxImportService.importFromFile`（POI + commons-csv 解析）支持 .csv/.xlsx/.xls 上传导入；`SandboxAuditService` + `bi_sandbox_audit` 表对全部导入 / 写表 / 删库操作留痕；前端「数据沙箱」页提供上传入口与审计日志抽屉。
