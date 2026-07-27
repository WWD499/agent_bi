package com.bi.agent.cache;

import com.alibaba.fastjson2.JSON;
import org.springframework.stereotype.Component;

/**
 * L2 Redis 序列化（fastjson2 JSON 文本）。
 *
 * <p>key 命名规范（P1-2 AC1/AC2/AC3）：L2 Redis key = {@code bi:cache:{namespace}:{key}}，
 * 前缀集中在 {@link #PREFIX}，与 {@code bi:agent:*} / SaToken 命名空间隔离，避免污染。</p>
 *
 * <p>value 一律序列化为 JSON 文本（绝不存 JDK 二进制 blob），反序列化按传入 {@code Class}
 * 还原（规避泛型擦除）。L1（Caffeine 堆内）持有对象引用，无需序列化。</p>
 *
 * <p>注册为 Spring 单例 Bean，供 {@link MultiLevelCache} 构造注入（无状态、可共享）。</p>
 */
@Component
public class RedisCacheSerializer {

    /** L2 Redis key 统一前缀（见共享知识 §1） */
    public static final String PREFIX = "bi:cache:";

    /**
     * 序列化为 JSON 文本。
     *
     * @param value 待序列化对象（L2 value）
     * @param <V>   值类型
     * @return JSON 字符串
     */
    public <V> String serialize(V value) {
        return JSON.toJSONString(value);
    }

    /**
     * 反序列化（按目标类型，规避泛型擦除）。
     *
     * @param json JSON 字符串
     * @param clazz 目标类型
     * @param <V>  值类型
     * @return 反序列化对象；json 为 null/空时返回 null
     */
    public <V> V deserialize(String json, Class<V> clazz) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        return JSON.parseObject(json, clazz);
    }
}
