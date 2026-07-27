package com.bi.agent.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 多级缓存装配（P0）。
 *
 * <p>① 注册 {@link CacheProperties} 配置属性 Bean（绑定 bi.cache.*）；
 * ② 提供共享的 Caffeine {@link Cache}&lt;Object,Object&gt; 单例，供 {@link MultiLevelCache}
 * 泛型单例在构造时强转复用（L1 由 CacheConfig 统一管理容量与过期）。</p>
 *
 * <p>注意：L2 复用 Spring Boot 自动配置的 {@link org.springframework.data.redis.core.StringRedisTemplate}
 * （不在此新建，避免与 RedisConfig 的 JDK 序列化模板冲突），由 {@link MultiLevelCache} 直接注入。</p>
 */
@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {

    /**
     * L1 Caffeine 单例（Cache&lt;Object,Object&gt;）。
     *
     * <p>容量与过期来自 bi.cache.caffeine-max-size / caffeine-expire-minutes。
     * 作为共享 L1 被 MultiLevelCache 单例注入后按命名空间复用。</p>
     */
    @Bean
    public Cache<Object, Object> caffeineCache(CacheProperties properties) {
        return Caffeine.newBuilder()
                .maximumSize(properties.getCaffeineMaxSize())
                .expireAfterWrite(Duration.ofMinutes(properties.getCaffeineExpireMinutes()))
                .build();
    }
}
