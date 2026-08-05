-- ============================================================================
-- agent_bi 平台种子数据（幂等，可反复重跑）
-- ----------------------------------------------------------------------------
-- 由 spring.sql.init 在 schema.sql 之后自动执行。
-- 所有写入均带幂等保护：
--   * 有主键的维度表用 ON CONFLICT (pk) DO NOTHING
--   * 无自然唯一键的系统表 / 事实表用 WHERE NOT EXISTS 整块跳过
--   * 沙箱默认库用 ON CONFLICT (db_key) DO NOTHING
-- 对已有库为安全 no-op，不会重复灌数，也不会扰动沙箱里用户上传的表。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 沙箱默认库（存量/新导入表默认归入）
-- ---------------------------------------------------------------------------
INSERT INTO bi_sandbox_db (db_key, name, remark)
VALUES ('default', '默认库', '沙箱初始化默认库（存量表自动归入）')
ON CONFLICT (db_key) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. 系统表种子数据
-- ---------------------------------------------------------------------------

-- 2.1 数据源：id=10 指向 agent_bi 自身（同一 PG 实例，证明动态 JDBC 链路）
INSERT INTO bi_datasource (id, name, type, host, port, database_name, username, password, status, remark)
VALUES (10, 'LocalPG-AgentBI', 'postgresql', 'localhost', 5432, 'agent_bi', 'postgres', 'postgres123', 1,
        'agent_bi 专属库：系统表 + 业务星型表同库')
ON CONFLICT (id) DO UPDATE SET
    name=EXCLUDED.name, type=EXCLUDED.type, host=EXCLUDED.host, port=EXCLUDED.port,
    database_name=EXCLUDED.database_name, username=EXCLUDED.username,
    password=EXCLUDED.password, status=EXCLUDED.status, remark=EXCLUDED.remark;

-- 2.2 RAG 知识库（content_vector 暂置 NULL，演示期走关键词兜底检索）
--     整块写入，仅当表为空时执行
INSERT INTO bi_knowledge (title, content, content_vector, source_type, source_url, business_domain, tags, chunk_index, total_chunks, status, create_by, create_time, update_by, update_time, remark)
SELECT v.* FROM (VALUES
    ('销售额指标口径', '销售额 = 订单数量 × 单价 × (1 - 折扣)，仅统计 status=''已完成'' 的订单。', NULL::vector, 'manual', NULL, '销售', '销售额,口径,指标', 0, 1, 1, 'admin', NOW(), 'admin', NOW(), '核心指标定义'),
    ('库存预警规则', '当 fact_inventory.stock_qty < safety_stock 时视为库存不足，需及时补货。', NULL::vector, 'manual', NULL, '库存', '库存,预警,安全库存', 0, 1, 1, 'admin', NOW(), 'admin', NOW(), '库存口径'),
    ('会员等级定义', '客户等级分为 普通 / 银卡 / 金卡 / 钻石，等级越高消费力越强，可作用户分群分析。', NULL::vector, 'manual', NULL, '用户', '会员,等级,用户', 0, 1, 1, 'admin', NOW(), 'admin', NOW(), '用户维度口径')
) AS v(title, content, content_vector, source_type, source_url, business_domain, tags, chunk_index, total_chunks, status, create_by, create_time, update_by, update_time, remark)
WHERE NOT EXISTS (SELECT 1 FROM bi_knowledge);

-- 2.3 预警规则（datasource_id=10 指向 agent_bi）
INSERT INTO bi_alert_config (name, datasource_id, table_name, metric_field, condition_sql, threshold_value, comparison_operator, check_interval, notify_type, notify_target, status, analysis_enabled, create_by, create_time, remark)
SELECT v.* FROM (VALUES
    ('库存总量预警', 10, 'fact_inventory', 'stock_qty', 'SELECT SUM(stock_qty) FROM fact_inventory', 1000, '<', 60, 'email', 'admin@example.com', 1, 1, 'admin', NOW(), '库存总量低于 1000 触发'),
    ('当月销售额监控', 10, 'fact_sales_order', 'amount', 'SELECT SUM(amount) FROM fact_sales_order WHERE status=''已完成'' AND order_date >= date_trunc(''month'', CURRENT_DATE)', 50000, '<', 1440, 'email', 'admin@example.com', 1, 0, 'admin', NOW(), '当月销售额低于 5 万触发')
) AS v(name, datasource_id, table_name, metric_field, condition_sql, threshold_value, comparison_operator, check_interval, notify_type, notify_target, status, analysis_enabled, create_by, create_time, remark)
WHERE NOT EXISTS (SELECT 1 FROM bi_alert_config);

-- ---------------------------------------------------------------------------
-- 3. 维度表显式数据（有主键，用 ON CONFLICT DO NOTHING）
-- ---------------------------------------------------------------------------
INSERT INTO dim_region (region_id, region_name, province, city, manager) VALUES
(1, '华东', '上海市', '上海市', '张明'),
(2, '华北', '北京市', '北京市', '李华'),
(3, '华南', '广东省', '广州市', '王芳'),
(4, '西南', '四川省', '成都市', '刘强'),
(5, '华中', '湖北省', '武汉市', '陈静'),
(6, '东北', '辽宁省', '沈阳市', '赵伟')
ON CONFLICT (region_id) DO NOTHING;

INSERT INTO dim_category (category_id, category_name, parent_category) VALUES
(1, '食品', '生鲜'),
(2, '数码', '3C'),
(3, '家居', '家装'),
(4, '服饰', '服装'),
(5, '美妆', '个护')
ON CONFLICT (category_id) DO NOTHING;

-- 商品：30 个
INSERT INTO dim_product (product_id, product_name, category_id, unit_price, cost_price, brand)
SELECT
    g AS product_id,
    (ARRAY['有机牛奶','进口香蕉','蓝牙耳机','智能手表','布艺沙发','实木餐桌','男士T恤','女士连衣裙','精华液','补水面膜',
             '天然矿泉水','每日坚果','机械键盘','4K显示器','护眼台灯','记忆抱枕','轻薄羽绒服','弹力牛仔裤','丝绒口红','清爽防晒霜',
             '风味酸奶','手工饼干','大容量移动电源','千兆路由器','乳胶床垫','折叠收纳柜','缓震运动鞋','连帽卫衣','氨基酸洁面乳','滋润护手霜'])[g] AS product_name,
    (ARRAY[1,1,2,2,3,3,4,4,5,5,
            1,1,2,2,3,3,4,4,5,5])[g] AS category_id,
    (10 + (g*7 % 90)*10)::NUMERIC(10,2) AS unit_price,
    (5  + (g*3 % 60)*5)::NUMERIC(10,2) AS cost_price,
    (ARRAY['优鲜','田园','声学','极客','美家','简居','风尚','衣品','妍丽','净颜'])[g] AS brand
FROM generate_series(1,30) AS g
ON CONFLICT (product_id) DO NOTHING;

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
FROM generate_series(1,200) AS g
ON CONFLICT (customer_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4. 事实表数据（仅当表为空时整块生成；对已有库为 no-op）
-- ---------------------------------------------------------------------------

-- 4.1 销售订单：~600 条，2024-01-01 ~ 2025-12-31，保持维度引用一致性
INSERT INTO fact_sales_order (order_no, customer_id, product_id, region_id, category_id, quantity, unit_price, discount, amount, order_date, status, channel, payment)
SELECT
    'SO' || to_char(b.odate, 'YYYYMMDD') || '-' || lpad(b.s::text, 4, '0'),
    b.cid, b.pid,
    c.region_id, p.category_id,
    b.qty, b.up, b.disc,
    b.qty * b.up * (1 - b.disc),
    b.odate, b.st, b.ch, b.pay
FROM (
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
) b
JOIN dim_customer c ON c.customer_id = b.cid
JOIN dim_product  p ON p.product_id  = b.pid
WHERE NOT EXISTS (SELECT 1 FROM fact_sales_order);

-- 4.2 库存快照：每 商品×区域 一条（180 行）
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
CROSS JOIN dim_region r
WHERE NOT EXISTS (SELECT 1 FROM fact_inventory);

-- 4.3 月度聚合：从已完成订单派生
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
  AND NOT EXISTS (SELECT 1 FROM fact_monthly_sales)
GROUP BY EXTRACT(YEAR FROM f.order_date), EXTRACT(MONTH FROM f.order_date), f.region_id, f.category_id, f.product_id;

-- ---------------------------------------------------------------------------
-- 5. 沙箱元数据兜底回填（仅修正历史遗留的空值，非破坏性）
--    现状：新表物理名 == 短名；对 physical_name / display_name / db_id 为空的历史行补默认值。
--    已有填写值的行不会被覆盖；全新库无行时为 no-op。
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    def_db_id BIGINT;
BEGIN
    SELECT id INTO def_db_id FROM bi_sandbox_db WHERE db_key = 'default';
    IF def_db_id IS NULL THEN
        def_db_id := 1;
    END IF;

    UPDATE bi_sandbox_table
        SET physical_name = table_name
        WHERE physical_name IS NULL OR physical_name = '';

    UPDATE bi_sandbox_table
        SET display_name = table_name
        WHERE display_name IS NULL OR display_name = '';

    UPDATE bi_sandbox_table
        SET db_id = def_db_id
        WHERE db_id IS NULL;
END $$;
