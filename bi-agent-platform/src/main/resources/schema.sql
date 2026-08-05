-- ============================================================================
-- agent_bi 平台数据库 Schema（单一可信源 / 幂等）
-- ----------------------------------------------------------------------------
-- 本文件取代原先散落在三处的 DDL：
--   1) 仓库根目录 sql/*.sql（bi_notify / bi_query_history / bi_ocr_record 增量补丁）
--   2) bi-agent-platform/sql/agent_bi_init.sql（系统表 + 星型模型，含破坏性 DROP）
--   3) bi-agent-platform/sql/sandbox_init.sql（沙箱元数据，含破坏性重命名迁移）
--   4) src/main/resources/sql/bi_dashboard.sql（单表）
--
-- 由 Spring Boot 的 spring.sql.init（mode=always）在应用启动时自动执行，
-- 全程使用 CREATE ... IF NOT EXISTS / ADD COLUMN IF NOT EXISTS /
-- CREATE INDEX IF NOT EXISTS，对已有库为安全 no-op，可反复重跑。
--
-- 注意：本脚本只建「已存在的数据库」内的对象，不会 CREATE DATABASE。
-- 首次部署前请先建库（一次性）：
--   psql -h localhost -U postgres -d postgres -c \
--     "SELECT 'CREATE DATABASE agent_bi' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname='agent_bi')\gexec"
-- 后续所有建表/加列/加索引，启动即自动补齐，不再有人工漏执行的问题。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 0. 向量扩展（RAG 语义检索依赖，建库级扩展，幂等）
-- ---------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------------
-- 1. 系统表（列名须与 Java 实体 / Mapper SELECT 完全一致）
-- ---------------------------------------------------------------------------

-- 1.1 动态数据源配置表（BiDatasource 实体）
CREATE TABLE IF NOT EXISTS bi_datasource (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    type         VARCHAR(20)  NOT NULL,
    host         VARCHAR(200) NOT NULL,
    port         INT           NOT NULL DEFAULT 5432,
    database_name VARCHAR(100) NOT NULL,
    username     VARCHAR(100) NOT NULL,
    password     VARCHAR(500),
    jdbc_url     VARCHAR(500),
    status       INT           DEFAULT 0,
    remark       VARCHAR(500),
    create_by    VARCHAR(64),
    create_time  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    update_by    VARCHAR(64),
    update_time  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
);

-- 1.2 RAG 业务知识库表（BiKnowledge 实体，需要 vector 扩展）
CREATE TABLE IF NOT EXISTS bi_knowledge (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(500) NOT NULL,
    content         TEXT          NOT NULL,
    content_vector  vector(1024),
    source_type     VARCHAR(20),
    source_url      VARCHAR(500),
    business_domain VARCHAR(100),
    tags            VARCHAR(500),
    chunk_index     INT,
    total_chunks    INT,
    status          INT           DEFAULT 1,
    create_by       VARCHAR(64),
    create_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    update_by       VARCHAR(64),
    update_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    remark          TEXT
);

-- 1.3 预警规则配置表（BiAlertRule 实体 → bi_alert_config；列名 comparison_operator）
CREATE TABLE IF NOT EXISTS bi_alert_config (
    id                   BIGSERIAL PRIMARY KEY,
    name                 VARCHAR(200) NOT NULL,
    datasource_id        BIGINT,
    table_name           VARCHAR(100),
    metric_field         VARCHAR(100),
    condition_sql        TEXT,
    threshold_value      DOUBLE PRECISION,
    comparison_operator VARCHAR(10),
    check_interval       INT,
    notify_type          VARCHAR(100),
    notify_target        VARCHAR(500),
    status               INT           DEFAULT 0,
    analysis_enabled     INT           DEFAULT 0,
    last_check_time      TIMESTAMP WITHOUT TIME ZONE,
    last_alert_time      TIMESTAMP WITHOUT TIME ZONE,
    create_by            VARCHAR(64),
    create_time          TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    update_by            VARCHAR(64),
    update_time          TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    remark               TEXT
);

-- 1.4 预警触发记录表（BiAlertRecord 实体 → bi_alert_record）
CREATE TABLE IF NOT EXISTS bi_alert_record (
    id                BIGSERIAL PRIMARY KEY,
    rule_id           BIGINT,
    rule_name         VARCHAR(200),
    datasource_id     BIGINT,
    table_name        VARCHAR(100),
    check_sql         TEXT,
    threshold_value   DOUBLE PRECISION,
    actual_value      DOUBLE PRECISION,
    comparison_operator VARCHAR(10),
    alert_message     TEXT,
    analysis_result   TEXT,
    alert_level       VARCHAR(20),
    alert_time        TIMESTAMP WITHOUT TIME ZONE,
    status            VARCHAR(20)   DEFAULT 'pending',
    handled_by        VARCHAR(64),
    handled_time      TIMESTAMP WITHOUT TIME ZONE,
    handled_remark    TEXT,
    update_time       TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
);

-- 1.5 NL2SQL 查询历史表（BiQueryHistory 实体 → bi_query_history）
CREATE TABLE IF NOT EXISTS bi_query_history (
    id            BIGSERIAL PRIMARY KEY,
    user_id       VARCHAR(64),
    datasource_id BIGINT,
    query         TEXT,
    sql           TEXT,
    row_count     INT,
    duration_ms   BIGINT,
    status        VARCHAR(20),
    error_msg     TEXT,
    create_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 1.6 站内信 / 用户通知表（BiNotify 实体 → bi_notify）
CREATE TABLE IF NOT EXISTS bi_notify (
    id          BIGSERIAL PRIMARY KEY,
    user_id     VARCHAR(64),
    rule_id     BIGINT,
    record_id   BIGINT,
    title       VARCHAR(200),
    content     TEXT,
    level       VARCHAR(20),
    is_read     INT           DEFAULT 0,
    create_time TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
);

-- 1.7 BI 大屏配置表（BiDashboard 实体 → bi_dashboard）
CREATE TABLE IF NOT EXISTS bi_dashboard (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    config_json TEXT,
    thumbnail TEXT,
    status VARCHAR(1) DEFAULT '1',
    is_public VARCHAR(1) DEFAULT '0',
    access_token VARCHAR(64),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP
);

-- 1.8 OCR 识别历史记录表（BiOcrRecord 实体 → bi_ocr_record）
CREATE TABLE IF NOT EXISTS bi_ocr_record (
    id            BIGSERIAL PRIMARY KEY,
    ds_id         BIGINT,
    image_path    VARCHAR(512),
    raw_text      TEXT,
    structured_json TEXT,
    source        VARCHAR(64) DEFAULT 'upload',
    create_by     VARCHAR(64),
    create_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ---------------------------------------------------------------------------
-- 2. 多域业务星型模型（销售 / 库存 / 用户）
--    维度表 dim_* + 事实表 fact_*，全部位于 public 模式，供 NL2SQL 直查
--    创建顺序保证被引用的维度表先于事实表存在（内联 REFERENCES 需要）
-- ---------------------------------------------------------------------------

-- 2.1 维度：区域
CREATE TABLE IF NOT EXISTS dim_region (
    region_id   INT PRIMARY KEY,
    region_name VARCHAR(50)  NOT NULL,
    province    VARCHAR(50),
    city        VARCHAR(50),
    manager     VARCHAR(50)
);

-- 2.2 维度：商品品类
CREATE TABLE IF NOT EXISTS dim_category (
    category_id   INT PRIMARY KEY,
    category_name VARCHAR(50) NOT NULL,
    parent_category VARCHAR(50)
);

-- 2.3 维度：商品（引用 dim_category）
CREATE TABLE IF NOT EXISTS dim_product (
    product_id  INT PRIMARY KEY,
    product_name VARCHAR(100) NOT NULL,
    category_id INT REFERENCES dim_category(category_id),
    unit_price  NUMERIC(10,2),
    cost_price  NUMERIC(10,2),
    brand       VARCHAR(50)
);

-- 2.4 维度：客户（用户域，引用 dim_region）
CREATE TABLE IF NOT EXISTS dim_customer (
    customer_id  INT PRIMARY KEY,
    customer_name VARCHAR(100) NOT NULL,
    region_id    INT REFERENCES dim_region(region_id),
    level        VARCHAR(20),
    gender       VARCHAR(10),
    age          INT,
    register_date DATE,
    total_spent  NUMERIC(12,2)
);

-- 2.5 事实：销售订单（核心事实表）
CREATE TABLE IF NOT EXISTS fact_sales_order (
    order_id    BIGSERIAL PRIMARY KEY,
    order_no    VARCHAR(40)  NOT NULL,
    customer_id INT REFERENCES dim_customer(customer_id),
    product_id  INT REFERENCES dim_product(product_id),
    region_id   INT REFERENCES dim_region(region_id),
    category_id INT REFERENCES dim_category(category_id),
    quantity    INT,
    unit_price  NUMERIC(10,2),
    discount    NUMERIC(5,2),
    amount      NUMERIC(12,2),
    order_date  DATE,
    status      VARCHAR(20),
    channel     VARCHAR(20),
    payment     VARCHAR(20)
);

-- 2.6 事实：库存快照
CREATE TABLE IF NOT EXISTS fact_inventory (
    snapshot_id  BIGSERIAL PRIMARY KEY,
    product_id   INT REFERENCES dim_product(product_id),
    region_id    INT REFERENCES dim_region(region_id),
    warehouse    VARCHAR(50),
    stock_qty    INT,
    safety_stock INT,
    snapshot_date DATE,
    inbound_qty  INT,
    outbound_qty INT
);

-- 2.7 事实：月度销售聚合（从 fact_sales_order 派生）
CREATE TABLE IF NOT EXISTS fact_monthly_sales (
    id             BIGSERIAL PRIMARY KEY,
    year           INT,
    month          INT,
    region_id      INT REFERENCES dim_region(region_id),
    category_id    INT REFERENCES dim_category(category_id),
    product_id     INT REFERENCES dim_product(product_id),
    total_orders   INT,
    total_quantity INT,
    total_amount   NUMERIC(14,2),
    total_cost     NUMERIC(14,2),
    total_profit   NUMERIC(14,2)
);

-- ---------------------------------------------------------------------------
-- 3. 数据沙箱元数据（逻辑命名空间「沙箱库」，物理隔离靠 sandbox schema 前缀）
--    bi_sandbox_* 元数据表本身落在 public，与系统表统一管理
-- ---------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS sandbox;

-- 3.1 沙箱库（逻辑命名空间）：用户可新建多个「沙箱库」对表分组
--     db_key 仅用于逻辑分组与展示；新表物理名 == 短名（不再拼接 db_key）
CREATE TABLE IF NOT EXISTS bi_sandbox_db (
    id           BIGSERIAL PRIMARY KEY,
    db_key       VARCHAR(64) NOT NULL UNIQUE,
    name         VARCHAR(128) NOT NULL,
    owner        VARCHAR(64),
    remark       VARCHAR(500),
    create_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 3.2 沙箱表元数据（系统表统一管理）
CREATE TABLE IF NOT EXISTS bi_sandbox_table (
    id            BIGSERIAL PRIMARY KEY,
    db_id         BIGINT NOT NULL DEFAULT 1,
    table_name    VARCHAR(200) NOT NULL,
    physical_name VARCHAR(200) NOT NULL,
    display_name  VARCHAR(200),
    owner         VARCHAR(64),
    columns_json  TEXT,
    row_count     INT DEFAULT 0,
    source_type   VARCHAR(20) DEFAULT 'paste',
    remark        VARCHAR(500),
    create_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 3.2.1 兼容旧版（旧脚本无 db_id / physical_name / display_name 列）：补齐，幂等
ALTER TABLE bi_sandbox_table
    ADD COLUMN IF NOT EXISTS db_id BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS physical_name VARCHAR(200),
    ADD COLUMN IF NOT EXISTS display_name VARCHAR(200);

-- 唯一约束：同一沙箱库内表名不可重复（跨库允许同名）
CREATE UNIQUE INDEX IF NOT EXISTS uk_sandbox_table_uniq ON bi_sandbox_table(db_id, table_name);

-- 3.3 沙箱操作审计表（所有改变物理数据/结构的操作留痕）
CREATE TABLE IF NOT EXISTS bi_sandbox_audit (
    id           BIGSERIAL PRIMARY KEY,
    operator     VARCHAR(64) NOT NULL DEFAULT 'anonymous',
    operation    VARCHAR(32) NOT NULL,
    target       VARCHAR(256) NOT NULL DEFAULT '',
    detail       TEXT,
    success      SMALLINT NOT NULL DEFAULT 1,
    fail_reason  VARCHAR(500),
    create_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sandbox_audit_time ON bi_sandbox_audit(create_time DESC);
CREATE INDEX IF NOT EXISTS idx_sandbox_audit_op ON bi_sandbox_audit(operation);

-- ---------------------------------------------------------------------------
-- 4. 索引（外键 / 状态 / 时间 高频过滤字段）
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_ds_status        ON bi_datasource(status);
CREATE INDEX IF NOT EXISTS idx_know_domain      ON bi_knowledge(business_domain);
CREATE INDEX IF NOT EXISTS idx_know_status      ON bi_knowledge(status);
CREATE INDEX IF NOT EXISTS idx_alert_ds         ON bi_alert_config(datasource_id);
CREATE INDEX IF NOT EXISTS idx_alert_status     ON bi_alert_config(status);
CREATE INDEX IF NOT EXISTS idx_record_rule      ON bi_alert_record(rule_id);
CREATE INDEX IF NOT EXISTS idx_record_time      ON bi_alert_record(alert_time);
CREATE INDEX IF NOT EXISTS idx_record_status    ON bi_alert_record(status);
CREATE INDEX IF NOT EXISTS idx_query_history_user ON bi_query_history (user_id);
CREATE INDEX IF NOT EXISTS idx_query_history_ct   ON bi_query_history (create_time DESC);
CREATE INDEX IF NOT EXISTS idx_notify_user        ON bi_notify (user_id);
CREATE INDEX IF NOT EXISTS idx_notify_user_read   ON bi_notify (user_id, is_read);
CREATE INDEX IF NOT EXISTS idx_ocr_record_ds      ON bi_ocr_record (ds_id);
CREATE INDEX IF NOT EXISTS idx_ocr_record_ct      ON bi_ocr_record (create_time DESC);
CREATE INDEX IF NOT EXISTS idx_order_date       ON fact_sales_order(order_date);
CREATE INDEX IF NOT EXISTS idx_order_region     ON fact_sales_order(region_id);
CREATE INDEX IF NOT EXISTS idx_order_category   ON fact_sales_order(category_id);
CREATE INDEX IF NOT EXISTS idx_order_status     ON fact_sales_order(status);
CREATE INDEX IF NOT EXISTS idx_inv_product      ON fact_inventory(product_id);
CREATE INDEX IF NOT EXISTS idx_inv_region       ON fact_inventory(region_id);
CREATE INDEX IF NOT EXISTS idx_monthly_ym       ON fact_monthly_sales(year, month);
