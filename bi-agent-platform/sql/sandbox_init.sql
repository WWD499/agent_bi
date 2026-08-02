-- 数据沙箱初始化脚本（M1 + 逻辑命名空间「沙箱库」）
-- 在现有 agent_bi 库内新增独立 sandbox schema，复用系统库连接，零新增数据源配置。
-- 执行方式：与 agent_bi_init.sql 一起在 agent_bi 库执行（psql -d agent_bi -f sandbox_init.sql）。

-- 1. 独立 schema（物理隔离靠 schema 前缀 sandbox.表名 + 沙箱专属校验器防止跨 schema 越权）
CREATE SCHEMA IF NOT EXISTS sandbox;

-- 2. 沙箱库（逻辑命名空间）——用户可新建多个「沙箱库」对表分组
--    db_key 仅用于逻辑分组与展示，新表物理名 == 短名（不再拼接 db_key）；历史上曾用 db_key || '__' || table_name（见第 5 节迁移）
CREATE TABLE IF NOT EXISTS bi_sandbox_db (
    id           BIGSERIAL PRIMARY KEY,
    db_key       VARCHAR(64) NOT NULL UNIQUE,    -- 物理前缀键（如 marts / sales_dm），用于拼 physical_name
    name         VARCHAR(128) NOT NULL,           -- 库展示名（可中文，如 销售主题域）
    owner        VARCHAR(64),                     -- 用户标识（预留多用户）
    remark       VARCHAR(500),
    create_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 3. 沙箱表元数据（落在 public，作为系统表统一管理）
CREATE TABLE IF NOT EXISTS bi_sandbox_table (
    id            BIGSERIAL PRIMARY KEY,
    db_id         BIGINT NOT NULL DEFAULT 1,      -- 所属沙箱库 id（指向 bi_sandbox_db.id）
    table_name    VARCHAR(200) NOT NULL,          -- 沙箱表短名（全沙箱唯一，即用户给定的表名）
    physical_name VARCHAR(200) NOT NULL,          -- 物理表名：新表 == table_name（短名）；历史兼容迁移表为旧拼接名 db_key__table_name
    display_name  VARCHAR(200),                   -- 用户友好显示名（可中文，如 部门表/员工表）；为空时前端回退到短名
    owner         VARCHAR(64),
    columns_json  TEXT,                           -- JSON: [{"name":"region","type":"TEXT"}, ...]
    row_count     INT DEFAULT 0,
    source_type   VARCHAR(20) DEFAULT 'paste',    -- paste / upload / datasource / agent_create
    remark        VARCHAR(500),
    create_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 3.1 兼容已存在的旧版 bi_sandbox_table（旧脚本无 db_id / physical_name 列）：
--     若表已存在且缺少这两列，则补齐，保证后续建索引 / 迁移语句不报错。
--     （全新安装时列已存在，两条 ADD COLUMN IF NOT EXISTS 均为 no-op，幂等安全。）
ALTER TABLE bi_sandbox_table
    ADD COLUMN IF NOT EXISTS db_id BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS physical_name VARCHAR(200),
    ADD COLUMN IF NOT EXISTS display_name VARCHAR(200);

-- 唯一约束：同一沙箱库内表名不可重复（跨库允许同名）
DROP INDEX IF EXISTS uk_sandbox_table_name;
CREATE UNIQUE INDEX IF NOT EXISTS uk_sandbox_table_uniq ON bi_sandbox_table(db_id, table_name);

-- 6. 沙箱操作审计表（M3）：所有会改变沙箱物理数据/结构的操作均留痕，便于追溯
--    operation：IMPORT_TEXT / IMPORT_DATASOURCE / IMPORT_FILE / CREATE_TABLE / MATERIALIZE / DROP_TABLE / DROP_DB
--    success：1 成功 / 0 失败；fail_reason 仅在失败时填写
CREATE TABLE IF NOT EXISTS bi_sandbox_audit (
    id           BIGSERIAL PRIMARY KEY,
    operator     VARCHAR(64) NOT NULL DEFAULT 'anonymous',  -- 操作人（Sa-Token 登录 id）
    operation    VARCHAR(32) NOT NULL,
    target       VARCHAR(256) NOT NULL DEFAULT '',           -- 操作对象（物理表名 / 源表名 / 库名）
    detail       TEXT,                                       -- JSON：列定义 / 行数 / 来源等
    success      SMALLINT NOT NULL DEFAULT 1,                -- 1 成功 / 0 失败
    fail_reason  VARCHAR(500),
    create_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sandbox_audit_time ON bi_sandbox_audit(create_time DESC);
CREATE INDEX IF NOT EXISTS idx_sandbox_audit_op ON bi_sandbox_audit(operation);

-- 4. 种子默认库（存量表全部归到 default 库，物理名统一加 default__ 前缀）
INSERT INTO bi_sandbox_db (db_key, name, remark)
VALUES ('default', '默认库', '沙箱初始化默认库（存量表自动归入）')
ON CONFLICT (db_key) DO NOTHING;

-- 5. 存量兼容迁移（幂等、可重跑）：
--    5.1 把已存在的 sandbox 物理表重命名为 default__<原名>
DO $$
DECLARE
    r RECORD;
    newname TEXT;
BEGIN
    FOR r IN
        SELECT table_name FROM information_schema.tables
        WHERE table_schema = 'sandbox' AND table_name NOT LIKE 'default__%'
    LOOP
        newname := 'default__' || r.table_name;
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = 'sandbox' AND table_name = newname
        ) THEN
            EXECUTE format('ALTER TABLE sandbox.%I RENAME TO %I', r.table_name, newname);
        END IF;
    END LOOP;
END $$;

--    5.2 补齐 bi_sandbox_table 的 db_id / physical_name（physical_name = default__<table_name>）
--        取真实 default 库 id（不写死 1，避免历史已存在 bi_sandbox_db 时 id 不一致）
DO $$
DECLARE
    def_db_id BIGINT;
BEGIN
    SELECT id INTO def_db_id FROM bi_sandbox_db WHERE db_key = 'default';
    IF def_db_id IS NULL THEN
        def_db_id := 1;
    END IF;
    UPDATE bi_sandbox_table
    SET db_id = def_db_id,
        physical_name = 'default__' || table_name
    WHERE physical_name IS NULL OR physical_name = '';

    -- 5.3 回填显示名：旧表 display_name 为空时回退到短名（table_name），保证前端有可读标签；
    --     已设置过中文显示名的行不会被覆盖（仅改空值）。
    UPDATE bi_sandbox_table
    SET display_name = table_name
    WHERE display_name IS NULL OR display_name = '';
END $$;
