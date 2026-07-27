-- ============================================================================
-- agent_bi 平台数据库初始化脚本
-- 数据库：agent_bi（独立于 ai_bi 共用的 ry 库，专供 agent_bi 使用）
-- 内容：系统表（bi_*，与现有 Java 实体列名严格一致）
--       + 多域业务星型模型（销售 / 库存 / 用户 三域综合）
-- 执行方式：
--   1) 建库（幂等）：
--      psql -h localhost -U postgres -d postgres -c "SELECT 'CREATE DATABASE agent_bi' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname='agent_bi')\gexec"
--   2) 建表 + 数据：
--      psql -h localhost -U postgres -d agent_bi -f agent_bi_init.sql
-- 说明：本脚本开头 DROP TABLE IF EXISTS ... CASCADE 保证「重跑即清空重建」，
--       适合开发/演示环境反复灌数。生产环境请移除 DROP 段。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 0. 清空重建（开发演示用，重跑安全）
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS
    fact_monthly_sales, fact_inventory, fact_sales_order,
    dim_customer, dim_product, dim_category, dim_region,
    bi_alert_record, bi_alert_config, bi_knowledge, bi_datasource
CASCADE;

-- ---------------------------------------------------------------------------
-- 1. 系统表（列名须与 Java 实体 / Mapper SELECT 完全一致）
-- ---------------------------------------------------------------------------

-- 1.1 动态数据源配置表（BiDatasource 实体）
CREATE TABLE bi_datasource (
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
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE bi_knowledge (
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

-- 1.3 预警规则配置表（BiAlertRule 实体 → 映射 bi_alert_config；注意列名为 comparison_operator）
CREATE TABLE bi_alert_config (
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

-- 1.4 预警触发记录表（BiAlertRecord 实体 → bi_alert_record；update_time 供 UPDATE 使用）
CREATE TABLE bi_alert_record (
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

-- ---------------------------------------------------------------------------
-- 2. 多域业务星型模型（销售 / 库存 / 用户）
--    维度表 dim_* + 事实表 fact_*，全部位于 public 模式，供 NL2SQL 直查
-- ---------------------------------------------------------------------------

-- 2.1 维度：区域
CREATE TABLE dim_region (
    region_id   INT PRIMARY KEY,
    region_name VARCHAR(50)  NOT NULL,
    province    VARCHAR(50),
    city        VARCHAR(50),
    manager     VARCHAR(50)
);

-- 2.2 维度：商品品类
CREATE TABLE dim_category (
    category_id   INT PRIMARY KEY,
    category_name VARCHAR(50) NOT NULL,
    parent_category VARCHAR(50)
);

-- 2.3 维度：商品
CREATE TABLE dim_product (
    product_id  INT PRIMARY KEY,
    product_name VARCHAR(100) NOT NULL,
    category_id INT REFERENCES dim_category(category_id),
    unit_price  NUMERIC(10,2),
    cost_price  NUMERIC(10,2),
    brand       VARCHAR(50)
);

-- 2.4 维度：客户（用户域）
CREATE TABLE dim_customer (
    customer_id  INT PRIMARY KEY,
    customer_name VARCHAR(100) NOT NULL,
    region_id    INT REFERENCES dim_region(region_id),
    level        VARCHAR(20),     -- 普通 / 银卡 / 金卡 / 钻石
    gender       VARCHAR(10),
    age          INT,
    register_date DATE,
    total_spent  NUMERIC(12,2)
);

-- 2.5 事实：销售订单（核心事实表，~600 行）
CREATE TABLE fact_sales_order (
    order_id    BIGSERIAL PRIMARY KEY,
    order_no    VARCHAR(40)  NOT NULL,
    customer_id INT REFERENCES dim_customer(customer_id),
    product_id  INT REFERENCES dim_product(product_id),
    region_id   INT REFERENCES dim_region(region_id),
    category_id INT REFERENCES dim_category(category_id),
    quantity    INT,
    unit_price  NUMERIC(10,2),
    discount    NUMERIC(5,2),   -- 0~0.3
    amount      NUMERIC(12,2),
    order_date  DATE,
    status      VARCHAR(20),     -- 已完成 / 已退款 / 待发货 / 已取消
    channel     VARCHAR(20),     -- 线上 / 线下 / 小程序
    payment     VARCHAR(20)      -- 微信 / 支付宝 / 银行卡
);

-- 2.6 事实：库存快照（每 商品×区域 一条，最新日期）
CREATE TABLE fact_inventory (
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
CREATE TABLE fact_monthly_sales (
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
-- 3. 索引（外键 / 状态 / 时间 高频过滤字段）
-- ---------------------------------------------------------------------------
CREATE INDEX idx_ds_status        ON bi_datasource(status);
CREATE INDEX idx_know_domain      ON bi_knowledge(business_domain);
CREATE INDEX idx_know_status      ON bi_knowledge(status);
CREATE INDEX idx_alert_ds         ON bi_alert_config(datasource_id);
CREATE INDEX idx_alert_status     ON bi_alert_config(status);
CREATE INDEX idx_record_rule      ON bi_alert_record(rule_id);
CREATE INDEX idx_record_time      ON bi_alert_record(alert_time);
CREATE INDEX idx_record_status    ON bi_alert_record(status);
CREATE INDEX idx_order_date       ON fact_sales_order(order_date);
CREATE INDEX idx_order_region     ON fact_sales_order(region_id);
CREATE INDEX idx_order_category   ON fact_sales_order(category_id);
CREATE INDEX idx_order_status     ON fact_sales_order(status);
CREATE INDEX idx_inv_product      ON fact_inventory(product_id);
CREATE INDEX idx_inv_region       ON fact_inventory(region_id);
CREATE INDEX idx_monthly_ym      ON fact_monthly_sales(year, month);

-- ---------------------------------------------------------------------------
-- 4. 系统表种子数据
-- ---------------------------------------------------------------------------

-- 4.1 数据源：id=10 指向 agent_bi 自身（同一 PG 实例，证明动态 JDBC 链路）
INSERT INTO bi_datasource (id, name, type, host, port, database_name, username, password, status, remark)
VALUES (10, 'LocalPG-AgentBI', 'postgresql', 'localhost', 5432, 'agent_bi', 'postgres', 'postgres123', 1,
        'agent_bi 专属库：系统表 + 业务星型表同库')
ON CONFLICT (id) DO UPDATE SET
    name=EXCLUDED.name, type=EXCLUDED.type, host=EXCLUDED.host, port=EXCLUDED.port,
    database_name=EXCLUDED.database_name, username=EXCLUDED.username,
    password=EXCLUDED.password, status=EXCLUDED.status, remark=EXCLUDED.remark;

-- 4.2 RAG 知识库（content_vector 暂置 NULL，演示期走关键词兜底检索）
INSERT INTO bi_knowledge (title, content, content_vector, source_type, source_url, business_domain, tags, chunk_index, total_chunks, status, create_by, create_time, update_by, update_time, remark) VALUES
('销售额指标口径', '销售额 = 订单数量 × 单价 × (1 - 折扣)，仅统计 status=''已完成'' 的订单。', NULL, 'manual', NULL, '销售', '销售额,口径,指标', 0, 1, 1, 'admin', NOW(), 'admin', NOW(), '核心指标定义'),
('库存预警规则', '当 fact_inventory.stock_qty < safety_stock 时视为库存不足，需及时补货。', NULL, 'manual', NULL, '库存', '库存,预警,安全库存', 0, 1, 1, 'admin', NOW(), 'admin', NOW(), '库存口径'),
('会员等级定义', '客户等级分为 普通 / 银卡 / 金卡 / 钻石，等级越高消费力越强，可作用户分群分析。', NULL, 'manual', NULL, '用户', '会员,等级,用户', 0, 1, 1, 'admin', NOW(), 'admin', NOW(), '用户维度口径');

-- 4.3 预警规则（datasource_id=10 指向 agent_bi）
INSERT INTO bi_alert_config (name, datasource_id, table_name, metric_field, condition_sql, threshold_value, comparison_operator, check_interval, notify_type, notify_target, status, analysis_enabled, create_by, create_time, remark) VALUES
('库存总量预警', 10, 'fact_inventory', 'stock_qty', 'SELECT SUM(stock_qty) FROM fact_inventory', 1000, '<', 60, 'email', 'admin@example.com', 1, 1, 'admin', NOW(), '库存总量低于 1000 触发'),
('当月销售额监控', 10, 'fact_sales_order', 'amount', 'SELECT SUM(amount) FROM fact_sales_order WHERE status=''已完成'' AND order_date >= date_trunc(''month'', CURRENT_DATE)', 50000, '<', 1440, 'email', 'admin@example.com', 1, 0, 'admin', NOW(), '当月销售额低于 5 万触发');

-- ---------------------------------------------------------------------------
-- 5. 维度表显式数据
-- ---------------------------------------------------------------------------
INSERT INTO dim_region (region_id, region_name, province, city, manager) VALUES
(1, '华东', '上海市', '上海市', '张明'),
(2, '华北', '北京市', '北京市', '李华'),
(3, '华南', '广东省', '广州市', '王芳'),
(4, '西南', '四川省', '成都市', '刘强'),
(5, '华中', '湖北省', '武汉市', '陈静'),
(6, '东北', '辽宁省', '沈阳市', '赵伟');

INSERT INTO dim_category (category_id, category_name, parent_category) VALUES
(1, '食品', '生鲜'),
(2, '数码', '3C'),
(3, '家居', '家装'),
(4, '服饰', '服装'),
(5, '美妆', '个护');

-- 商品：30 个，品类/价格/成本/品牌由数组派生（product_id 1..30）
INSERT INTO dim_product (product_id, product_name, category_id, unit_price, cost_price, brand)
SELECT
    g AS product_id,
    (ARRAY['有机牛奶','进口香蕉','蓝牙耳机','智能手表','布艺沙发','实木餐桌','男士T恤','女士连衣裙','精华液','补水面膜',
             '天然矿泉水','每日坚果','机械键盘','4K显示器','护眼台灯','记忆抱枕','轻薄羽绒服','弹力牛仔裤','丝绒口红','清爽防晒霜',
             '风味酸奶','手工饼干','大容量移动电源','千兆路由器','乳胶床垫','折叠收纳柜','缓震运动鞋','连帽卫衣','氨基酸洁面乳','滋润护手霜'])[g] AS product_name,
    (ARRAY[1,1,2,2,3,3,4,4,5,5,
            1,1,2,2,3,3,4,4,5,5,
            1,1,2,2,3,3,4,4,5,5])[g] AS category_id,
    (10 + (g*7 % 90)*10)::NUMERIC(10,2) AS unit_price,
    (5  + (g*3 % 60)*5)::NUMERIC(10,2) AS cost_price,
    (ARRAY['优鲜','田园','声学','极客','美家','简居','风尚','衣品','妍丽','净颜'])[g] AS brand
FROM generate_series(1,30) AS g;

-- 客户：200 个
INSERT INTO dim_customer (customer_id, customer_name, region_id, level, gender, age, register_date, total_spent)
SELECT
    g AS customer_id,
    '客户' || lpad(g::text, 4, '0') AS customer_name,
    (1 + floor(random()*6))::INT AS region_id,
    (ARRAY['普通','普通','银卡','金卡','钻石'])[1 + floor(random()*5)] AS level,
    (ARRAY['男','女'])[1 + floor(random()*2)] AS gender,
    (18 + floor(random()*50))::INT AS age,
    ('2023-01-01'::DATE + floor(random()*700)::INT) AS register_date,
    (100 + floor(random()*20000))::NUMERIC(12,2) AS total_spent
FROM generate_series(1,200) AS g;

-- ---------------------------------------------------------------------------
-- 6. 事实表数据（循环生成）
-- ---------------------------------------------------------------------------

-- 6.1 销售订单：~600 条，2024-01-01 ~ 2025-12-31，保持维度引用一致性
WITH base AS (
    SELECT
        s AS s,
        (1 + floor(random()*200))::INT AS cid,
        (1 + floor(random()*30))::INT  AS pid,
        ('2024-01-01'::DATE + floor(random()*730)::INT) AS odate,
        (1 + floor(random()*5))::INT   AS qty,
        (10 + floor(random()*990))::NUMERIC(10,2) AS up,
        (random()*0.3)::NUMERIC(5,2)  AS disc,
        (ARRAY['已完成','已完成','已完成','已退款','待发货','已取消'])[1 + floor(random()*6)] AS st,
        (ARRAY['线上','线下','小程序'])[1 + floor(random()*3)] AS ch,
        (ARRAY['微信','支付宝','银行卡'])[1 + floor(random()*3)] AS pay
    FROM generate_series(1,600) AS s
)
INSERT INTO fact_sales_order (order_no, customer_id, product_id, region_id, category_id, quantity, unit_price, discount, amount, order_date, status, channel, payment)
SELECT
    'SO' || to_char(b.odate, 'YYYYMMDD') || '-' || lpad(b.s::text, 4, '0'),
    b.cid, b.pid,
    c.region_id, p.category_id,
    b.qty, b.up, b.disc,
    b.qty * b.up * (1 - b.disc),
    b.odate, b.st, b.ch, b.pay
FROM base b
JOIN dim_customer c ON c.customer_id = b.cid
JOIN dim_product  p ON p.product_id  = b.pid;

-- 6.2 库存快照：每 商品×区域 一条（180 行），部分低于安全库存以便演示预警
INSERT INTO fact_inventory (product_id, region_id, warehouse, stock_qty, safety_stock, snapshot_date, inbound_qty, outbound_qty)
SELECT
    p.product_id, r.region_id,
    '中心仓-' || r.region_id,
    floor(random()*500)::INT AS stock_qty,
    50 AS safety_stock,
    '2025-12-31'::DATE AS snapshot_date,
    floor(random()*200)::INT AS inbound_qty,
    floor(random()*150)::INT AS outbound_qty
FROM dim_product p
CROSS JOIN dim_region r;

-- 6.3 月度聚合：从已完成订单派生
INSERT INTO fact_monthly_sales (year, month, region_id, category_id, product_id, total_orders, total_quantity, total_amount, total_cost, total_profit)
SELECT
    EXTRACT(YEAR  FROM f.order_date)::INT,
    EXTRACT(MONTH FROM f.order_date)::INT,
    f.region_id, f.category_id, f.product_id,
    COUNT(*)                                              AS total_orders,
    SUM(f.quantity)                                       AS total_quantity,
    SUM(f.amount)                                          AS total_amount,
    SUM(f.quantity * p.cost_price)                        AS total_cost,
    SUM(f.amount) - SUM(f.quantity * p.cost_price)        AS total_profit
FROM fact_sales_order f
JOIN dim_product p ON p.product_id = f.product_id
WHERE f.status = '已完成'
GROUP BY EXTRACT(YEAR FROM f.order_date), EXTRACT(MONTH FROM f.order_date), f.region_id, f.category_id, f.product_id;

-- ---------------------------------------------------------------------------
-- 完成提示
-- ---------------------------------------------------------------------------
SELECT 'agent_bi 初始化完成：' AS msg,
       (SELECT COUNT(*) FROM fact_sales_order) AS sales_orders,
       (SELECT COUNT(*) FROM fact_inventory)   AS inventory_rows,
       (SELECT COUNT(*) FROM fact_monthly_sales) AS monthly_rows,
       (SELECT COUNT(*) FROM dim_customer)     AS customers,
       (SELECT COUNT(*) FROM dim_product)      AS products;
