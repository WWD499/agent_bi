package com.bi.agent.bi.service.probe;

import com.bi.agent.bi.domain.BiDatasource;
import com.bi.agent.bi.service.sql.SqlValidator;
import com.bi.agent.bi.util.BiDataSourceFactory;
import com.bi.agent.bi.vo.DataProfile;
import com.bi.agent.cache.CacheProperties;
import com.bi.agent.cache.CacheStats;
import com.bi.agent.cache.MultiLevelCache;
import com.bi.agent.cache.RedisCacheSerializer;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 数据探查服务单测（T6）：直连 agent_bi（dsId=10）真实业务库，
 * 验证 fact_sales_order 的真实覆盖区间（2024-01 ~ 2025-12）与 status 枚举取值。
 *
 * <p>不依赖 Spring 容器：手动 new DataProbeService 并注入 dataSourceFactory + sqlValidator，
 * 避免加载 Redis / Sa-Token 等自动配置。连接参数与 application.yml 中的系统库一致
 * （agent_bi 系统库同时承载业务星型表 fact_sales_order / dim_region）。
 */
class DataProbeServiceTest {

    /** 构造指向 agent_bi 业务库的数据源（dsId=10 在本环境指向该库） */
    private BiDatasource ds() {
        BiDatasource ds = new BiDatasource();
        ds.setId(10L);
        ds.setType("postgresql");
        ds.setHost("localhost");
        ds.setPort(5432);
        ds.setDatabaseName("agent_bi");
        ds.setUsername("postgres");
        ds.setPassword("postgres123");
        return ds;
    }

    @Test
    void probe_factSalesOrder_returnsRealCoverage() {
        DataProbeService probe = new DataProbeService(new BiDataSourceFactory(), new SqlValidator(), buildProbeCache());

        Map<String, DataProfile> profiles = probe.probe(ds(), List.of("fact_sales_order"), "postgresql");

        DataProfile p = profiles.get("fact_sales_order");
        assertNotNull(p, "探查结果应包含 fact_sales_order");

        // 行数：种子数据约 600 行，宽松 >100
        assertTrue(p.getRowCount() > 100,
                "fact_sales_order 行数应接近 600（>100），实际=" + p.getRowCount());

        // 时间列 order_date 真实覆盖到 2025（证明早于当前日期，根治 CURRENT_DATE 窗口 mismatch）
        assertTrue(p.getTimeColumns().containsKey("order_date"),
                "探查应识别时间列 order_date");
        DataProfile.TimeRange tr = p.getTimeColumns().get("order_date");
        assertNotNull(tr.getMax(), "order_date MAX 不应为空");
        assertTrue(tr.getMax().contains("2025"),
                "order_date MAX 应覆盖到 2025（真实数据早于当前日期），实际=" + tr.getMax());
        assertEquals("2025-Q4", tr.getLatestQuarter(),
                "由 2025-12 推算的最新季度应为 2025-Q4，实际=" + tr.getLatestQuarter());

        // 枚举列 status 含真实取值（如「已完成」），非凭空硬写
        assertTrue(p.getEnumColumns().containsKey("status"),
                "探查应识别枚举列 status");
        assertFalse(p.getEnumColumns().get("status").isEmpty(),
                "status 应含实际取值（如 已完成）");
    }

    /**
     * 构建一个以 mock StringRedisTemplate 为 L2 的探查缓存（L2 为内存桩，不连真实 Redis），
     * 使 DataProbeService 在单测中可正常接入多级缓存。
     */
    private MultiLevelCache<String, DataProfile> buildProbeCache() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(null);
        // set(K,V,Duration) 在本版本为 void 方法，mock 默认即为 no-op，无需 stub 返回值
        when(redis.keys(anyString())).thenReturn(Collections.emptySet());
        when(redis.delete(anyString())).thenReturn(Boolean.TRUE);
        Cache<Object, Object> caffeine = Caffeine.newBuilder()
                .maximumSize(1000).expireAfterWrite(Duration.ofMinutes(30)).build();
        return new MultiLevelCache<String, DataProfile>(new CacheProperties(), caffeine, redis,
                new RedisCacheSerializer(), new CacheStats());
    }
}
