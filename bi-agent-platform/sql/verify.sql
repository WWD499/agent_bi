-- 验证查询（UTF-8 文件，经 psql -f 执行，规避 shell 中文编码问题）
\echo '=== 各区域销售额（已完成）==='
SELECT r.region_name, COUNT(*) AS orders, SUM(f.amount)::numeric(12,2) AS sales
FROM fact_sales_order f JOIN dim_region r ON r.region_id=f.region_id
WHERE f.status='已完成' GROUP BY r.region_name ORDER BY sales DESC;

\echo '=== 各品类销售占比 ==='
SELECT c.category_name, SUM(f.amount)::numeric(12,2) AS sales
FROM fact_sales_order f JOIN dim_category c ON c.category_id=f.category_id
WHERE f.status='已完成' GROUP BY c.category_name ORDER BY sales DESC;

\echo '=== 库存低于安全库存（预警演示用）前 10 行 ==='
SELECT product_id, region_id, stock_qty, safety_stock
FROM fact_inventory WHERE stock_qty < safety_stock ORDER BY stock_qty ASC LIMIT 10;

\echo '=== 月度销售趋势（2025）前 12 行 ==='
SELECT year, month, SUM(total_amount)::numeric(14,2) AS amount
FROM fact_monthly_sales WHERE year=2025 GROUP BY year, month ORDER BY month;
