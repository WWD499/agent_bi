package com.bi.agent.cache;

import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 真·多级缓存核心组件（L1 本地 Caffeine + L2 复用现有 Redis）。
 *
 * <p>泛型 {@code <K,V>} 单例（被 DataProbeService / LlmService 等以不同 V 注入同一实例），
 * 对外屏蔽两级差异，业务方只调一个 read-through 入口 {@link #get}。L1 为堆内 Caffeine，
 * 直接持有对象引用，无需序列化；L2 为独立 {@link StringRedisTemplate} + fastjson2 JSON 文本，
 * key 前缀 {@code bi:cache:{namespace}:}，与 {@code bi:agent:*} / SaToken 隔离。</p>
 *
 * <p>一致性策略（Q1）：read-through + 主动失效（write-around）。写只走业务原路径；缓存仅在
 * {@code get} 回源时回填；提供 {@link #clear} / {@link #clearNamespace} 主动失效。不做
 * write-through / write-behind。回源（loader）抛异常时向上抛，不写缓存（避免脏值）。</p>
 *
 * <p>L2 操作全程 try/catch：Redis 抖动时降级为「仅用 L1 + 回源」，不让缓存故障拖垮业务。</p>
 */
@Component
public class MultiLevelCache<K, V> {

    private static final Logger log = LoggerFactory.getLogger(MultiLevelCache.class);

    /**
     * L1 本地缓存（堆内）。因本组件为泛型单例（同一 L1 被不同命名空间共享），
     * 注入的是 {@code Cache<Object,Object>}，构造时做一次 unchecked 强转（泛型擦除，运行期安全）。
     */
    private final Cache<K, V> l1;

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisCacheSerializer serializer;
    private final CacheStats stats;

    /**
     * 构造注入。
     *
     * @param properties          缓存配置（bi.cache.*）
     * @param caffeineCache       CacheConfig 提供的 Caffeine 单例（Cache&lt;Object,Object&gt;，内部强转）
     * @param stringRedisTemplate 独立 StringRedisTemplate（不复用 RedisConfig 的 JDK 序列化模板）
     * @param serializer          fastjson2 序列化器
     * @param stats               命中率统计（共享单例）
     */
    @SuppressWarnings("unchecked")
    public MultiLevelCache(CacheProperties properties,
                           Cache<Object, Object> caffeineCache,
                           StringRedisTemplate stringRedisTemplate,
                           RedisCacheSerializer serializer,
                           CacheStats stats) {
        this.l1 = (Cache<K, V>) caffeineCache;
        this.stringRedisTemplate = stringRedisTemplate;
        this.serializer = serializer;
        this.stats = stats;
    }

    /**
     * read-through 主入口。
     *
     * <p>流程：① L1 命中 → 返回；② L1 未命中查 L2（命中则反序列化并回填 L1）→ 返回；
     * ③ L1/L2 都未命中 → 调用 loader 回源，并写入 L1 + L2（带 ttl）。
     * 命中（L1 或 L2）记一次 hit；L1/L2 均命中失败回源成功记一次 miss；loader 返回 null 时不写缓存、不记 miss。</p>
     *
     * @param namespace  命名空间（如 probe / llm），用于 L2 key 隔离
     * @param key        业务 key（L1 直接用作 key；L2 key = PREFIX + namespace + ":" + key）
     * @param ttl        L2 写回 TTL
     * @param valueClass 反序列化目标类型（规避泛型擦除）
     * @param loader     回源函数（未命中时调用）
     * @return 缓存值或回源结果（loader 抛异常时向上抛，不写缓存）
     */
    public V get(String namespace, K key, Duration ttl, Class<V> valueClass,
                 Function<? super K, ? extends V> loader) {
        String l1Key = String.valueOf(key);
        String l2Key = RedisCacheSerializer.PREFIX + namespace + ":" + l1Key;
        try {
            // ① L1
            V hit1 = l1.getIfPresent(key);
            if (hit1 != null) {
                stats.recordHit();
                return hit1;
            }
            // ② L2
            try {
                String raw = stringRedisTemplate.opsForValue().get(l2Key);
                if (raw != null) {
                    V v = serializer.deserialize(raw, valueClass);
                    if (v != null) {
                        l1.put(key, v);
                        stats.recordHit();
                        return v;
                    }
                }
            } catch (Exception e) {
                // L2 读取异常：降级为仅用 L1 + 回源，不阻断业务
                log.error("MultiLevelCache L2 读取异常，降级为仅用 L1 + 回源 namespace={} key={}",
                        namespace, l2Key, e);
            }
            // ③ 回源
            V loaded = loader.apply(key);
            if (loaded != null) {
                l1.put(key, loaded);
                try {
                    stringRedisTemplate.opsForValue().set(l2Key, serializer.serialize(loaded), ttl);
                } catch (Exception e) {
                    // L2 写入异常：降级为仅用 L1，不阻断业务
                    log.error("MultiLevelCache L2 写入异常，降级为仅用 L1 namespace={} key={}",
                            namespace, l2Key, e);
                }
                stats.recordMiss();
            }
            return loaded;
        } finally {
            // 可观测（P1-1）：每次 get 末尾打印命中率，L2 抖动降级时也能看到真实命中情况
            log.info("MultiLevelCache 命中率 namespace={} hitRate={}", namespace, stats.hitRate());
        }
    }

    /**
     * 只读不回源（预留）。不触发统计。
     */
    public Optional<V> getIfPresent(K key) {
        return Optional.ofNullable(l1.getIfPresent(key));
    }

    /**
     * 主动失效单 key：同时清 L1 + L2。
     */
    public void clear(String namespace, K key) {
        String l1Key = String.valueOf(key);
        String l2Key = RedisCacheSerializer.PREFIX + namespace + ":" + l1Key;
        l1.invalidate(key);
        try {
            stringRedisTemplate.delete(l2Key);
        } catch (Exception e) {
            log.error("MultiLevelCache L2 失效失败 namespace={} key={}", namespace, l2Key, e);
        }
    }

    /**
     * 按命名空间批量失效。
     *
     * <p>L1：本实例 {@code invalidateAll()}（注：当前为共享单例，会清空整个 L1；本期不引入
     * Pub/Sub 广播，多实例 L1 靠 TTL 兜底；此处为未来「按 namespace 广播失效」扩展点）。</p>
     * <p>L2：按 {@code PREFIX + namespace + ":*"} 前缀批量 del。</p>
     */
    public void clearNamespace(String namespace) {
        // 扩展点：未来可接 Redis Pub/Sub，将失效广播到所有实例，替代下面 L1 仅本实例清的局限。
        l1.invalidateAll();
        try {
            String pattern = RedisCacheSerializer.PREFIX + namespace + ":*";
            Set<String> keys = stringRedisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.error("MultiLevelCache L2 批量失效失败 namespace={}", namespace, e);
        }
    }

    /**
     * 命中率统计快照。
     */
    public CacheStats stats() {
        return stats;
    }
}
