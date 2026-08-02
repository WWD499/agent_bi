package com.bi.agent.config;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.TestPropertySource;
import redis.embedded.RedisServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Sa-Token 已切到 Redis 持久化 DAO（Token 落 Redis）。
 *
 * 采用最小上下文（只启用 Redis 自动配置 + 导入 RedisConfig），
 * 不加载完整应用，从而不依赖 PostgreSQL / MyBatis（避免需要 PG 库）。
 *
 * 说明：
 *  - Sa-Token 1.44.0 已将旧版 RedisTokenDao 重构为 SaTokenDaoForRedisTemplate，
 *    且由 sa-token-redis-template 的自动配置在探测到 RedisTemplate 后自动注册。
 *  - 活动 DAO 通过 SaManager.getSaTokenDao() 获取（1.44.0 取代了 StpUtil.getTokenDao()）。
 *  - 不通过 StpUtil.login 触发，是因为纯测试线程未初始化 Sa-Token Web 上下文
 *    （SaHolder 需要 servlet 模型对象），而本测试仅验证“TokenDao 是 Redis 实现 +
 *    DAO 写入确实落 Redis”这一 Phase 1 核心目标；StpUtil.login 正是底层调用该 DAO 完成持久化。
 *
 * 内嵌 Redis 服务端（embedded-redis）在 127.0.0.1:6379 提供 Redis，
 * 与 application.yml 的 spring.data.redis 一致，使测试自包含、无需外部 Redis。
 */
@TestPropertySource(properties = "spring.data.redis.port=16379")
@SpringBootTest(classes = SaTokenRedisDaoTest.Phase1TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SaTokenRedisDaoTest {

    /** 用与默认 6379 不同的端口，避免与本机已运行的真实 Redis 冲突；测试仍自包含、无需外部 Redis */
    private static final int TEST_REDIS_PORT = 16379;

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration.class
    })
    @Import(RedisConfig.class)
    static class Phase1TestConfig {
    }

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

    @Autowired(required = false)
    RedisTemplate<String, Object> redisTemplate;

    @Test
    void tokenDaoShouldBeRedis() {
        // 1) 活动 TokenDao 应是 Redis 持久化实现（SaTokenDaoForRedisTemplate）
        SaTokenDao dao = SaManager.getSaTokenDao();
        assertThat(dao).isInstanceOf(SaTokenDaoForRedisTemplate.class);

        // 2) 通过 Redis DAO 写入并读回，验证确实持久化到 Redis（等价于“Token 落 Redis”）
        dao.set("satoken:phase1:verify", "1", 60);
        assertThat(dao.get("satoken:phase1:verify")).isEqualTo("1");

        // 3) 直接查 Redis，确认有 satoken 前缀的 key 落库
        assertThat(redisTemplate.keys("satoken:*")).isNotEmpty();
    }
}
