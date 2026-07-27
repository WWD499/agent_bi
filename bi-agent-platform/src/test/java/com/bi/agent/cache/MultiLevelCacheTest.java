package com.bi.agent.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MultiLevelCache 单测（T03）：用 Mockito mock StringRedisTemplate（L2 降级为内存桩），
 * Caffeine / RedisCacheSerializer / CacheStats 均用真实例，覆盖四路径：
 * ① L1 命中不调 loader ② L1 未命中但 L2 命中回填 L1 ③ 双未命中回源并写两级 ④ clear 后重新回源。
 */
class MultiLevelCacheTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private MultiLevelCache<String, String> cache;
    private CacheStats stats;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // L2 默认：未命中（返回 null）、写入成功、keys 空、delete 成功
        when(valueOps.get(anyString())).thenReturn(null);
        // set(K,V,Duration) 在本版本为 void 方法，mock 默认即为 no-op，无需 stub 返回值；
        // 测试仅通过 verify(...) 确认写入发生（见各用例）。
        when(redisTemplate.keys(anyString())).thenReturn(Collections.emptySet());
        when(redisTemplate.delete(anyString())).thenReturn(Boolean.TRUE);

        Cache<Object, Object> caffeine = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofMinutes(30))
                .build();
        stats = new CacheStats();
        cache = new MultiLevelCache<String, String>(new CacheProperties(), caffeine, redisTemplate,
                new RedisCacheSerializer(), stats);
    }

    @Test
    void l1HitDoesNotCallLoader() {
        AtomicInteger calls = new AtomicInteger();
        Function<String, String> loader = k -> {
            calls.incrementAndGet();
            return "v-" + k;
        };

        cache.get("probe", "k1", Duration.ofMinutes(10), String.class, loader);
        // 第二次：L1 命中，loader 不应再被调用
        String second = cache.get("probe", "k1", Duration.ofMinutes(10), String.class, loader);

        assertEquals("v-k1", second);
        assertEquals(1, calls.get(), "L1 命中后 loader 不应被再次调用");
    }

    @Test
    void l2HitBackfillsL1WithoutLoader() {
        // L2 命中：返回 JSON 字符串
        when(valueOps.get(eq("bi:cache:probe:k2"))).thenReturn("\"l2-value\"");

        AtomicInteger calls = new AtomicInteger();
        String result = cache.get("probe", "k2", Duration.ofMinutes(10), String.class,
                k -> {
                    calls.incrementAndGet();
                    return "should-not-run";
                });

        assertEquals("l2-value", result);
        assertEquals(0, calls.get(), "L2 命中不应调用 loader");
        // L1 已回填：再次 get 直接 L1 命中，仍不调 loader
        String again = cache.get("probe", "k2", Duration.ofMinutes(10), String.class,
                k -> {
                    calls.incrementAndGet();
                    return "x";
                });
        assertEquals("l2-value", again);
        assertEquals(0, calls.get());
    }

    @Test
    void missLoadsAndFillsBothLevels() {
        AtomicInteger calls = new AtomicInteger();
        String result = cache.get("probe", "k3", Duration.ofMinutes(10), String.class,
                k -> {
                    calls.incrementAndGet();
                    return "loaded-" + k;
                });

        assertEquals("loaded-k3", result);
        assertEquals(1, calls.get());
        // L2 应写入一次（存 JSON 文本，非二进制）
        verify(valueOps, times(1)).set(eq("bi:cache:probe:k3"), eq("\"loaded-k3\""), any(Duration.class));
        // stats：1 miss / 0 hit
        assertEquals(1, stats.getMisses());
        assertEquals(0, stats.getHits());
    }

    @Test
    void clearForcesReload() {
        AtomicInteger calls = new AtomicInteger();
        cache.get("probe", "k4", Duration.ofMinutes(10), String.class,
                k -> {
                    calls.incrementAndGet();
                    return "val-" + k;
                });
        assertEquals(1, calls.get());

        cache.clear("probe", "k4");

        // clear 后 L1/L2 均失效，再次 get 重新回源
        cache.get("probe", "k4", Duration.ofMinutes(10), String.class,
                k -> {
                    calls.incrementAndGet();
                    return "val2-" + k;
                });
        assertEquals(2, calls.get(), "clear 后再次 get 应重新回源");
        verify(redisTemplate, times(1)).delete(eq("bi:cache:probe:k4"));
    }

    @Test
    void hitRateIsReasonableAfterMixedAccess() {
        // 3 次未命中（回源）+ 3 次 L1 命中 => 3 miss, 3 hit
        for (int i = 0; i < 3; i++) {
            cache.get("probe", "hr" + i, Duration.ofMinutes(10), String.class, k -> "value-" + k);
        }
        for (int i = 0; i < 3; i++) {
            cache.get("probe", "hr" + i, Duration.ofMinutes(10), String.class, k -> "value-" + k);
        }
        assertEquals(3, stats.getHits());
        assertEquals(3, stats.getMisses());
        assertEquals(0.5, stats.hitRate(), 0.0001);
    }

    @Test
    void l2StoresJsonTextNotBinary() {
        // 验证 L2 写入的是 JSON 文本（供 LlmService V=String 场景）
        cache.get("llm", "sha-abc", Duration.ofMinutes(30), String.class, k -> "final-answer");
        verify(valueOps, times(1)).set(eq("bi:cache:llm:sha-abc"), eq("\"final-answer\""), any(Duration.class));
    }

    @Test
    void serializerRoundTripsString() {
        // 直接验证 LlmService（V=String）的 L2 JSON 往返正确
        RedisCacheSerializer s = new RedisCacheSerializer();
        String json = s.serialize("hello");
        assertEquals("\"hello\"", json);
        assertEquals("hello", s.deserialize(json, String.class));
    }

    @Test
    void loaderNotCalledWhenL2Present() {
        // 反向确认：即便 loader 会抛异常，L2 命中时也绝不会触达 loader
        when(valueOps.get(eq("bi:cache:probe:k9"))).thenReturn("\"from-redis\"");
        String r = cache.get("probe", "k9", Duration.ofMinutes(10), String.class,
                k -> {
                    throw new IllegalStateException("loader must not run on L2 hit");
                });
        assertEquals("from-redis", r);
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }
}
