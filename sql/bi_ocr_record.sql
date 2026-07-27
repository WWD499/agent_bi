-- OCR 识别历史记录表（PostgreSQL）
-- 在系统主库（ry）执行。

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

CREATE INDEX IF NOT EXISTS idx_ocr_record_ds ON bi_ocr_record (ds_id);
CREATE INDEX IF NOT EXISTS idx_ocr_record_ct ON bi_ocr_record (create_time DESC);
