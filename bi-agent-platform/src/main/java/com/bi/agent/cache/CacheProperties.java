package com.bi.agent.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 多级缓存配置属性（绑定 {@code bi.cache.*}）。
 *
 * <p>项目未引入 Lombok，手写 getter/setter，风格与全项目一致。
 * 字段含兜底默认值，保证无配置也能编译运行。</p>
 */
@ConfigurationProperties("bi.cache")
public class CacheProperties {

    /** 缓存总开关（默认开启；本期 get 流程以该值作为可读开关位，预留关闭能力） */
    private boolean enabled = true;

    /** 默认 TTL（分钟），组件内按调用入参 Duration 覆盖，此处为兜底默认值 */
    private long defaultTtlMinutes = 30;

    /** L1 Caffeine 最大条目数 */
    private long caffeineMaxSize = 10000;

    /** L1 Caffeine 写入后过期时间（分钟） */
    private long caffeineExpireMinutes = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getDefaultTtlMinutes() {
        return defaultTtlMinutes;
    }

    public void setDefaultTtlMinutes(long defaultTtlMinutes) {
        this.defaultTtlMinutes = defaultTtlMinutes;
    }

    public long getCaffeineMaxSize() {
        return caffeineMaxSize;
    }

    public void setCaffeineMaxSize(long caffeineMaxSize) {
        this.caffeineMaxSize = caffeineMaxSize;
    }

    public long getCaffeineExpireMinutes() {
        return caffeineExpireMinutes;
    }

    public void setCaffeineExpireMinutes(long caffeineExpireMinutes) {
        this.caffeineExpireMinutes = caffeineExpireMinutes;
    }
}
