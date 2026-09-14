package com.smartmeal.common.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 本地缓存实现（Caffeine）。
 *
 * <p>定位：单机开发 / 单元测试 / 没有 Redis 的兜底方案。
 * <b>不具备分布式一致性</b>，多实例部署时每个实例各存一份，生产请切到 {@link RedisCacheService}。
 *
 * <p>TTL 通过自定义 {@link Expiry} 实现，支持每条记录独立的过期时间。
 */
@Slf4j
public class LocalCacheService implements CacheService {

    private final ObjectMapper objectMapper;
    private final Cache<String, Holder> cache;

    /** 缓存条目：值 + 该条目自己的 TTL。 */
    private record Holder(Object value, long ttlNanos) {
    }

    public LocalCacheService(ObjectMapper objectMapper, long maximumSize) {
        this.objectMapper = objectMapper;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfter(new Expiry<String, Holder>() {
                    @Override
                    public long expireAfterCreate(String key, Holder holder, long currentTime) {
                        return holder.ttlNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, Holder holder, long currentTime,
                                                  long currentDuration) {
                        // 保留原有剩余时间：让 increment 表现为固定窗口而不是滑动窗口
                        return currentDuration;
                    }

                    @Override
                    public long expireAfterRead(String key, Holder holder, long currentTime,
                                                long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }

    @Override
    public <T> void set(String key, T value, Duration ttl) {
        // 先失效再写入，保证 TTL 被重置（expireAfterUpdate 会保留旧时间）
        cache.invalidate(key);
        cache.put(key, new Holder(value, ttl.toNanos()));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Holder holder = cache.getIfPresent(key);
        if (holder == null) {
            return null;
        }
        return convert(holder.value(), type);
    }

    @Override
    public boolean delete(String key) {
        boolean existed = cache.getIfPresent(key) != null;
        cache.invalidate(key);
        return existed;
    }

    @Override
    public long deleteByPrefix(String prefix) {
        ConcurrentMap<String, Holder> map = cache.asMap();
        long count = 0;
        for (String key : map.keySet()) {
            if (key.startsWith(prefix)) {
                map.remove(key);
                count++;
            }
        }
        return count;
    }

    @Override
    public boolean exists(String key) {
        return cache.getIfPresent(key) != null;
    }

    @Override
    public <T> T getOrLoad(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        Holder holder = cache.get(key, k -> new Holder(loader.get(), ttl.toNanos()));
        return holder == null ? null : convert(holder.value(), type);
    }

    @Override
    public long increment(String key, Duration window) {
        AtomicLong result = new AtomicLong();
        cache.asMap().compute(key, (k, existing) -> {
            if (existing == null) {
                result.set(1L);
                return new Holder(new AtomicLong(1L), window.toNanos());
            }
            AtomicLong counter = (AtomicLong) existing.value();
            result.set(counter.incrementAndGet());
            return existing;
        });
        return result.get();
    }

    @Override
    public String backend() {
        return "caffeine-local";
    }

    /** 缓存里存的是原对象（本地缓存不序列化），只在类型不符时才走 JSON 转换兜底。 */
    @SuppressWarnings("unchecked")
    private <T> T convert(Object value, Class<T> type) {
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return (T) value;
        }
        if (value instanceof Number number) {
            return (T) convertNumber(number, (Class<? extends Number>) type);
        }
        try {
            return objectMapper.convertValue(value, type);
        } catch (Exception e) {
            log.warn("本地缓存类型转换失败 target={} actual={}", type.getSimpleName(),
                    value.getClass().getSimpleName());
            return null;
        }
    }

    private Number convertNumber(Number number, Class<? extends Number> type) {
        if (type == Long.class) {
            return number.longValue();
        }
        if (type == Integer.class) {
            return number.intValue();
        }
        if (type == Double.class) {
            return number.doubleValue();
        }
        return number;
    }

    /** 供监控使用：当前缓存条目数。 */
    public long estimatedSize() {
        cache.cleanUp();
        return cache.estimatedSize();
    }

    /** 供监控使用：全部缓存 key。 */
    public Map<String, Holder> snapshot() {
        return Map.copyOf(cache.asMap());
    }
}
