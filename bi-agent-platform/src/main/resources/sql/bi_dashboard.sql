-- BI大屏配置表（对齐 ai_bi 的 BiDashboard 实体）
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

COMMENT ON TABLE bi_dashboard IS 'BI大屏配置表';
COMMENT ON COLUMN bi_dashboard.name IS '大屏名称';
COMMENT ON COLUMN bi_dashboard.description IS '描述';
COMMENT ON COLUMN bi_dashboard.config_json IS '布局+图表+数据源配置JSON';
COMMENT ON COLUMN bi_dashboard.thumbnail IS '缩略图(base64)';
COMMENT ON COLUMN bi_dashboard.status IS '状态 0停用 1启用';
COMMENT ON COLUMN bi_dashboard.is_public IS '是否公开 0否 1是';
COMMENT ON COLUMN bi_dashboard.access_token IS '公开访问令牌';
