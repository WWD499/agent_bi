package com.bi.agent.cache;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.LongAdder;

/**
 * 缓存命中率统计（并发安全）。
 *
 * <p>计数使用 {@link LongAdder}（高并发无锁累加），避免 {@code synchronized} 竞争。
 * 命中率 = 命中数 / (命中数 + 未命中数)；分母为 0 时返回 0.0（避免除零）。</p>
 */
@Component
public class CacheStats {

    private final LongAdder hit = new LongAdder();
    private final LongAdder miss = new LongAdder();

    /** 记录一次命中（L1 或 L2 命中均计入） */
    public void recordHit() {
        hit.increment();
    }

    /** 记录一次未命中（L1/L2 均未命中、且成功回源后计入） */
    public void recordMiss() {
        miss.increment();
    }

    public long getHits() {
        return hit.sum();
    }

    public long getMisses() {
        return miss.sum();
    }

    /**
     * 命中率 = hits / (hits + misses)。
     *
     * @return 命中率（[0,1]）；无统计样本时返回 0.0
     */
    public double hitRate() {
        long h = hit.sum();
        long m = miss.sum();
        long total = h + m;
        return total == 0L ? 0.0 : (double) h / total;
    }
}
