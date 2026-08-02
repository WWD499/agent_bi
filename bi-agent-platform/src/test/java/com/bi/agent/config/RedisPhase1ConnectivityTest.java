package com.bi.agent.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import redis.embedded.RedisServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 连通性验证（不加载 Spring 上下文、不连 PG）：
 * 用内嵌 Redis 服务端（embedded-redis，自带二进制）提供 127.0.0.1:6379，
 * 手动用 Lettuce 直连，证明「本地 Redis + Lettuce + 序列化」链路通。
 * 内嵌服务端避免对外部 Redis 的依赖，使测试在沙箱中可自包含运行。
 */
class RedisPhase1ConnectivityTest {

    /** 用与默认 6379 不同的端口，避免与本机已运行的真实 Redis 冲突；测试仍自包含、无需外部 Redis */
    private static final int TEST_REDIS_PORT = 16379;

    private static RedisServer redisServer;

    @BeforeAll
    static void startRedis() throws Exception {
        redisServer = RedisServer.newRedisServer().port(TEST_REDIS_PORT).build();
        redisServer.start();
    }

    @AfterAll
    static void stopRedis() {
        if (redisServer != null) {
            try {
                redisServer.stop();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void lettuceConnectivityAndSerializationShouldWork() {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration("127.0.0.1", TEST_REDIS_PORT);
        LettuceConnectionFactory factory = new LettuceConnectionFactory(cfg);
        factory.afterPropertiesSet();

        RedisTemplate<String, Object> tpl = new RedisTemplate<>();
        tpl.setConnectionFactory(factory);
        tpl.setKeySerializer(new StringRedisSerializer());
        tpl.setValueSerializer(new JdkSerializationRedisSerializer());
        tpl.afterPropertiesSet();

        tpl.opsForValue().set("satoken:phase1:ping", "ok");
        assertThat(tpl.opsForValue().get("satoken:phase1:ping")).isEqualTo("ok");

        // 清理测试写入的 key，避免污染 Redis
        tpl.delete("satoken:phase1:ping");

        factory.destroy();
    }
}
