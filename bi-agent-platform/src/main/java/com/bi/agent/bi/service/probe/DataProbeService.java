package com.bi.agent.bi.service.probe;

import com.bi.agent.bi.domain.BiDatasource;
import com.bi.agent.bi.util.BiDataSourceFactory;
import com.bi.agent.bi.util.JdbcUrlBuilder;
import com.bi.agent.bi.service.sql.SqlValidator;
import com.bi.agent.bi.vo.DataProfile;
import com.bi.agent.cache.MultiLevelCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据探查服务（NL2SQL 前置）
 *
 * <p>在调用 LLM 生成 SQL 之前，对候选业务表做<b>只读</b>探查，得到真实数据画像：
 * <ul>
 *   <li>行数（COUNT(*)）；</li>
 *   <li>时间列的真实覆盖 MIN/MAX 与推算最新季度（根治「CURRENT_DATE 推算的时间窗口与真实数据不重叠 → 0 行」）；</li>
 *   <li>枚举列（低基数字符串列）的真实取值与计数（根治「LLM 凭空硬写不存在的枚举值」）。</li>
 * </ul>
 *
 * <p><b>安全铁律</b>：探查 SQL 的表名/列名<b>仅取自 DatabaseMetaData</b>（候选表名来自调用方传入，
 * 列名来自 meta.getColumns），<b>绝不拼接 userQuery</b>；标识符按方言转义；全部为只读 SELECT，
 * 复用 {@link BiDataSourceFactory} 的 Hikari 连接池；单表异常/超时仅降级跳过，不阻断整体查询。
 */
@Service
public class DataProbeService {

    private static final Logger log = LoggerFactory.getLogger(DataProbeService.class);

    private final BiDataSourceFactory dataSourceFactory;
    private final SqlValidator sqlValidator;

    /** 单条探查 SQL 超时（秒），可由配置覆盖，默认见 ProbeConstants */
    @Value("${bi.probe.timeout-seconds:3}")
    private int probeTimeoutSeconds = ProbeConstants.PROBE_TIMEOUT_SECONDS;

    /**
     * 真·多级缓存（L1 Caffeine + L2 Redis 复用）的探查命名空间实例。
     * key = 数据源ID:表名；TTL 10 分钟（ProbeConstants.CACHE_TTL_MINUTES）。
     * 取代原进程内 ConcurrentHashMap，重启不丢、多实例共享 L2。
     */
    private final MultiLevelCache<String, DataProfile> probeCache;

    @Autowired
    public DataProbeService(BiDataSourceFactory dataSourceFactory, SqlValidator sqlValidator,
                            MultiLevelCache<String, DataProfile> probeCache) {
        this.dataSourceFactory = dataSourceFactory;
        this.sqlValidator = sqlValidator;
        this.probeCache = probeCache;
    }

    /**
     * 对候选表批量探查，返回 表名 -> 数据画像 的映射。
     *
     * <p>任何整体异常（含无法获取连接）都返回<b>空 Map</b>，让调用方走「无探查」原逻辑，
     * 绝不让探查失败阻断用户查询。
     *
     * @param ds              数据源配置（连接信息）
     * @param candidateTables 候选表名（已排除系统表）
     * @param dialect         方言（"postgresql" / "mysql"），用于标识符转义
     * @return 表名 -> DataProfile；空或异常时返回空 Map
     */
    public Map<String, DataProfile> probe(BiDatasource ds, List<String> candidateTables, String dialect) {
        Map<String, DataProfile> result = new LinkedHashMap<>();
        if (candidateTables == null || candidateTables.isEmpty()) {
            return result;
        }

        long start = System.currentTimeMillis();
        String d = (dialect == null) ? "postgresql" : dialect.trim().toLowerCase();
        try (Connection conn = dataSourceFactory.getDataSource(ds).getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String catalog = JdbcUrlBuilder.catalog(ds);
            String schemaPattern = JdbcUrlBuilder.schemaPattern(ds);

            for (String t : candidateTables) {
                if (t == null || t.trim().isEmpty()) {
                    continue;
                }
                // 真·多级缓存：先查 L1/L2，未命中回源探查并回填两级（TTL = ProbeConstants.CACHE_TTL_MINUTES）。
                // cacheKey 保持原 数据源ID:表名，命中行为与原 ConcurrentHashMap 一致，不破坏既有调用方。
                String cacheKey = ds.getId() + ":" + t;
                DataProfile profile = probeCache.get("probe", cacheKey,
                        Duration.ofMinutes(ProbeConstants.CACHE_TTL_MINUTES), DataProfile.class,
                        k -> probeSingleTable(conn, meta, catalog, schemaPattern, ds, t, d));
                result.put(t, profile);
            }
        } catch (Exception e) {
            // 整体异常：仅告警，返回已探查的部分结果（或空 Map），不抛
            log.warn("数据探查整体失败，降级走原无探查逻辑：dsId={}", ds.getId(), e);
            return result;
        }

        long cost = System.currentTimeMillis() - start;
        log.info("数据探查完成：tables={}, cost={}ms", result.size(), cost);
        return result;
    }

    /**
     * 探查单张表：行数 + 时间列 MIN/MAX + 枚举列取值。
     * 单表异常 → probeSkipped=true + skipReason + 降级，不影响其它表。
     */
    private DataProfile probeSingleTable(Connection conn, DatabaseMetaData meta, String catalog,
                                          String schemaPattern, BiDatasource ds, String tableName, String dialect) {
        DataProfile profile = new DataProfile();
        profile.setDatasourceId(ds.getId());
        profile.setTableName(tableName);
        profile.setProbedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")));
        profile.setProbed(false);

        try {
            // 收集列元信息（列名 + 类型）
            List<ColumnMeta> columns = new ArrayList<>();
            try (ResultSet cols = meta.getColumns(catalog, schemaPattern, tableName, null)) {
                while (cols.next()) {
                    columns.add(new ColumnMeta(cols.getString("COLUMN_NAME"), cols.getString("TYPE_NAME")));
                }
            }

            // 1. 行数
            profile.setRowCount(countRows(conn, dialect, tableName));

            // 2. 时间列：MIN/MAX
            Map<String, DataProfile.TimeRange> timeRanges = new LinkedHashMap<>();
            for (ColumnMeta c : columns) {
                if (isTimeType(c.typeName)) {
                    TimeBound bound = queryMinMax(conn, dialect, tableName, c.colName);
                    if (bound != null) {
                        String latestQuarter = latestQuarterOf(bound.max);
                        DataProfile.TimeRange tr = new DataProfile.TimeRange(
                                c.colName, bound.min, bound.max, latestQuarter);
                        timeRanges.put(c.colName, tr);
                    }
                }
            }
            // 2.5 整型 year+month 组合（企业事实表最常见时序建模）：
            //     fact_monthly_sales(year int, month int) 非 DATE 类型，isTimeType 漏判；
            //     需单独探查 MIN(year*100+month)/MAX(...) 换算 YYYY-MM 注入 Prompt。
            detectYearMonthCombo(conn, dialect, tableName, columns, timeRanges);

            profile.setTimeColumns(timeRanges);

            // 3. 枚举列：先 COUNT(DISTINCT)，低基数再 GROUP BY 取 TOP N
            Map<String, List<DataProfile.EnumValue>> enums = new LinkedHashMap<>();
            for (ColumnMeta c : columns) {
                if (isStringType(c.typeName)) {
                    long distinct = countDistinct(conn, dialect, tableName, c.colName);
                    if (distinct > 0 && distinct < ProbeConstants.ENUM_CARDINALITY_THRESHOLD) {
                        List<DataProfile.EnumValue> values = queryTopEnumValues(conn, dialect, tableName, c.colName);
                        if (!values.isEmpty()) {
                            enums.put(c.colName, values);
                        }
                    }
                }
            }
            profile.setEnumColumns(enums);

            profile.setProbed(true);
        } catch (SQLException e) {
            profile.setProbeSkipped(true);
            profile.setSkipReason(truncate(e.getMessage(), 200));
            log.warn("数据探查单表降级：table={}", tableName, e);
        } catch (RuntimeException e) {
            profile.setProbeSkipped(true);
            profile.setSkipReason(truncate(e.getMessage(), 200));
            log.warn("数据探查单表降级：table={}", tableName, e);
        }

        profile.setCostMillis(0); // 单表耗时在主流程已整体统计，此处不单独计
        return profile;
    }

    // ==================== 各探查 SQL（只读 SELECT，标识符来自 meta） ====================

    private long countRows(Connection conn, String dialect, String table) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + escapeId(table, dialect);
        sqlValidator.validate(sql, Collections.singleton(table));
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(probeTimeoutSeconds);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    private TimeBound queryMinMax(Connection conn, String dialect, String table, String column) throws SQLException {
        String sql = "SELECT MIN(" + escapeId(column, dialect) + "), MAX(" + escapeId(column, dialect)
                + ") FROM " + escapeId(table, dialect);
        sqlValidator.validate(sql, Collections.singleton(table));
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(probeTimeoutSeconds);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    Object minObj = rs.getObject(1);
                    Object maxObj = rs.getObject(2);
                    if (minObj == null && maxObj == null) {
                        return null;
                    }
                    return new TimeBound(formatDate(minObj), formatDate(maxObj));
                }
            }
        }
        return null;
    }

    /** 整型类型判定（INTEGER / INT / BIGINT / NUMBER / SMALLINT / DECIMAL 系列） */
    private boolean isIntegerType(String typeName) {
        if (typeName == null) return false;
        String t = typeName.toUpperCase();
        return t.contains("INT") || t.contains("DECIMAL") || t.contains("NUMBER") || t.contains("NUMERIC");
    }

    /**
     * 检测 year(int) + month(int) 整型组合作为时间维度，探查真实覆盖区间。
     *
     * <p>企业事实表（如 fact_monthly_sales）常以「年整型列 + 月整型列」建模时序，
     * 而非 DATE/TIMESTAMP 类型；{@link #isTimeType} 仅认日期类型会漏判，
     * 导致该表时间范围未被探查、未注入 Prompt，LLM 用 CURRENT_DATE 推算窗口与真实数据不重叠 → 0 行。
     *
     * <p>本方法将组合探明的 MIN/MAX 换算为 YYYY-MM 格式写入 {@link DataProfile.TimeRange}
     * （column 标为 "year+month（整型年月组合）"），复用既有的 Prompt 注入与 latestQuarter 推算逻辑。
     */
    private void detectYearMonthCombo(Connection conn, String dialect, String tableName,
                                        List<ColumnMeta> columns, Map<String, DataProfile.TimeRange> timeRanges) {
        ColumnMeta yearCol = null, monthCol = null;
        for (ColumnMeta c : columns) {
            if (!isIntegerType(c.typeName)) continue;
            String cn = c.colName == null ? "" : c.colName.toLowerCase();
            if ("year".equals(cn)) yearCol = c;
            else if ("month".equals(cn)) monthCol = c;
        }
        if (yearCol == null || monthCol == null) return;

        String sql = "SELECT MIN(" + escapeId(yearCol.colName, dialect) + "*100 + "
                + escapeId(monthCol.colName, dialect) + "), MAX("
                + escapeId(yearCol.colName, dialect) + "*100 + "
                + escapeId(monthCol.colName, dialect) + ") FROM " + escapeId(tableName, dialect);
        sqlValidator.validate(sql, Collections.singleton(tableName));
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(probeTimeoutSeconds);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    int minYm = rs.getInt(1);
                    int maxYm = rs.getInt(2);
                    if (rs.wasNull()) return;
                    int minYear = minYm / 100, minMonth = minYm % 100;
                    int maxYear = maxYm / 100, maxMonth = maxYm % 100;
                    String min = String.format("%04d-%02d", minYear, minMonth);
                    String max = String.format("%04d-%02d", maxYear, maxMonth);
                    String latestQuarter = latestQuarterOf(max);
                    DataProfile.TimeRange tr = new DataProfile.TimeRange(
                            "year+month（整型年月组合）", min, max, latestQuarter);
                    timeRanges.put("year+month", tr);
                    log.info("探查到整型年月组合：table={}, 覆盖 {}~{}（最新季度 {}）",
                            tableName, min, max, latestQuarter);
                }
            }
        } catch (SQLException e) {
            log.warn("整型年月组合探查失败，跳过：table={}", tableName, e);
        }
    }

    private long countDistinct(Connection conn, String dialect, String table, String column) throws SQLException {
        String sql = "SELECT COUNT(DISTINCT " + escapeId(column, dialect) + ") FROM " + escapeId(table, dialect);
        sqlValidator.validate(sql, Collections.singleton(table));
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(probeTimeoutSeconds);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    private List<DataProfile.EnumValue> queryTopEnumValues(Connection conn, String dialect,
                                                            String table, String column) throws SQLException {
        String sql = "SELECT " + escapeId(column, dialect) + ", COUNT(*) AS cnt FROM " + escapeId(table, dialect)
                + " GROUP BY " + escapeId(column, dialect)
                + " ORDER BY cnt DESC LIMIT " + ProbeConstants.ENUM_TOP_N;
        sqlValidator.validate(sql, Collections.singleton(table));
        List<DataProfile.EnumValue> values = new ArrayList<>();
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(probeTimeoutSeconds);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    String v = rs.getString(1);
                    long cnt = rs.getLong(2);
                    values.add(new DataProfile.EnumValue(v, cnt));
                }
            }
        }
        return values;
    }

    // ==================== 工具方法 ====================

    /** 按方言转义标识符：MySQL 用反引号，其它（含 PostgreSQL）用双引号 */
    private String escapeId(String id, String dialect) {
        if (dialect != null && dialect.contains("mysql")) {
            return "`" + id + "`";
        }
        return "\"" + id + "\"";
    }

    /** 时间类型判定（DATE / TIME / TIMESTAMP 系列） */
    private boolean isTimeType(String typeName) {
        if (typeName == null) return false;
        String t = typeName.toUpperCase();
        return t.contains("DATE") || t.contains("TIME");
    }

    /** 字符串类型判定（CHAR / VARCHAR / TEXT 系列） */
    private boolean isStringType(String typeName) {
        if (typeName == null) return false;
        String t = typeName.toUpperCase();
        return t.contains("CHAR") || t.contains("TEXT") || t.contains("VARCHAR") || t.contains("CLOB");
    }

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 把 JDBC 日期对象格式化为 yyyy-MM-dd；其它类型直接 toString */
    private String formatDate(Object obj) {
        if (obj == null) return null;
        if (obj instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) obj).toLocalDateTime().format(DATE_FMT);
        }
        if (obj instanceof java.sql.Date) {
            return ((java.sql.Date) obj).toLocalDate().format(DATE_FMT);
        }
        if (obj instanceof java.util.Date) {
            return ((java.util.Date) obj).toInstant()
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(DATE_FMT);
        }
        return String.valueOf(obj);
    }

    /** 从 MAX 值（如 "2025-12-31" / "2025-12"）推算最新季度（"2025-Q4"） */
    private String latestQuarterOf(String max) {
        if (max == null) return null;
        Matcher m = Pattern.compile("(\\d{4})-(\\d{1,2})").matcher(max);
        if (m.find()) {
            int year = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            int quarter = (month - 1) / 3 + 1;
            return year + "-Q" + quarter;
        }
        return max;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 列元信息内部载体 */
    private static class ColumnMeta {
        final String colName;
        final String typeName;

        ColumnMeta(String colName, String typeName) {
            this.colName = colName;
            this.typeName = typeName;
        }
    }

    /** MIN/MAX 字符串对 */
    private static class TimeBound {
        final String min;
        final String max;

        TimeBound(String min, String max) {
            this.min = min;
            this.max = max;
        }
    }

    /** T8 探查结果缓存条目已移除：原进程内 ConcurrentHashMap + CacheEntry 由 MultiLevelCache 取代（见类头注释与 probe()）。 */
}
