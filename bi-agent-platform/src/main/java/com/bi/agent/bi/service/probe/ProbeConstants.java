package com.bi.agent.bi.service.probe;

/**
 * 数据探查相关常量。
 *
 * <p>取值可由 application.yml 的 {@code bi.probe.*} 覆盖（见 DataProbeService 的字段注入），
 * 此处为兜底默认值，保证无配置也能编译运行。
 */
public final class ProbeConstants {

    private ProbeConstants() {
    }

    /** 单条探查 SQL 的超时时间（秒），防止慢查询拖垮主流程 */
    public static final int PROBE_TIMEOUT_SECONDS = 3;

    /** 枚举列基数阈值：去重计数 < 该值时才进一步统计各取值（避免高基数列无意义） */
    public static final int ENUM_CARDINALITY_THRESHOLD = 50;

    /** 枚举列取值 TOP N：GROUP BY 后仅取计数最高的前 N 个 */
    public static final int ENUM_TOP_N = 20;

    /** 探查结果缓存 TTL（分钟），由 T8 Caffeine 缓存使用 */
    public static final int CACHE_TTL_MINUTES = 10;
}
