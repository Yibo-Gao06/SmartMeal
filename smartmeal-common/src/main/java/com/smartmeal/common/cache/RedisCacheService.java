package com.smartmeal.common.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Redis 缓存实现。
 *
 * <p>生产环境使用。相比本地缓存多了分布式共享和原子计数能力。
 *
 * <p>两个实现细节值得注意：
 * <ul>
 *   <li>批量删除用 {@code SCAN} 游标而不是 {@code KEYS}，避免大 key 空间下阻塞 Redis 主线程；</li>
 *   <li>限流计数用 {@code INCR} + 首次 {@code EXPIRE}，保证窗口期不因持续请求而被无限延长。</li>
 * </ul>
 */
@Slf4j
public class RedisCacheService implements CacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 单次 SCAN 的游标数量，不宜过大。 */
    private static final long SCAN_COUNT = 500L;

    public RedisCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public <T> void set(String key, T value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, toJson(value), ttl);
        } catch (Exception e) {
            // 缓存不可用不应导致业务失败，降级为「当作没缓存」
            log.warn("Redis 写入失败 key={}，已忽略", key, e);
        }
    }

    @Override
    public <T> T get(String key, Class<T> type) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            return json == null ? null : fromJson(json, type);
        } catch (Exception e) {
            log.warn("Redis 读取失败 key={}，已忽略", key, e);
            return null;
        }
    }

    @Override
    public boolean delete(String key) {
        try {
            return Boolean.TRUE.equals(redisTemplate.delete(key));
        } catch (Exception e) {
            log.warn("Redis 删除失败 key={}，已忽略", key, e);
            return false;
        }
    }

    @Override
    public long deleteByPrefix(String prefix) {
        List<String> keys = scanKeys(prefix + "*");
        if (keys.isEmpty()) {
            return 0L;
        }
        try {
            Long removed = redisTemplate.delete(keys);
            return removed == null ? 0L : removed;
        } catch (Exception e) {
            log.warn("Redis 批量删除失败 prefix={}，已忽略", prefix, e);
            return 0L;
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.warn("Redis exists 失败 key={}，已忽略", key, e);
            return false;
        }
    }

    @Override
    public <T> T getOrLoad(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        T cached = get(key, type);
        if (cached != null) {
            return cached;
        }
        T loaded = loader.get();
        if (loaded != null) {
            set(key, loaded, ttl);
        }
        return loaded;
    }

    @Override
    public long increment(String key, Duration window) {
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                // 只在创建计数器时设置过期，后续自增不续期 → 固定窗口
                redisTemplate.expire(key, window);
            }
            return count == null ? 0L : count;
        } catch (Exception e) {
            log.warn("Redis 计数失败 key={}，已降级为放行", key, e);
            return 0L;
        }
    }

    @Override
    public String backend() {
        return "redis";
    }

    /** 用 SCAN 游标遍历，避免 KEYS 阻塞。 */
    private List<String> scanKeys(String pattern) {
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(SCAN_COUNT).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        } catch (Exception e) {
            log.warn("Redis SCAN 失败 pattern={}，已忽略", pattern, e);
        }
        return keys;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("缓存值序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("缓存值反序列化失败 target={}", type.getSimpleName());
            return null;
        }
    }
}
