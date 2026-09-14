package com.smartmeal.common.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LocalCacheService} 的行为约束。
 *
 * <p>重点验证两件容易被写错的事：
 * <ol>
 *   <li>每条记录独立的 TTL（Caffeine 默认只支持全局 TTL，这里用了自定义 {@code Expiry}）；</li>
 *   <li>{@code increment} 必须是<b>固定窗口</b>而不是滑动窗口，
 *       否则限流会永远不触发（每次自增都把过期时间往后推）。</li>
 * </ol>
 */
class LocalCacheServiceTest {

    private LocalCacheService cache;

    @BeforeEach
    void setUp() {
        cache = new LocalCacheService(new ObjectMapper(), 1000);
    }

    @Test
    @DisplayName("set/get：存进去能取出来，类型保持一致")
    void setAndGet() {
        cache.set("user:1", "张三", Duration.ofMinutes(10));

        assertEquals("张三", cache.get("user:1", String.class));
        assertTrue(cache.exists("user:1"));
        assertEquals("caffeine-local", cache.backend());
    }

    @Test
    @DisplayName("get：key 不存在返回 null，不抛异常")
    void getMissingKeyReturnsNull() {
        assertNull(cache.get("not-exist", String.class));
        assertFalse(cache.exists("not-exist"));
    }

    @Test
    @DisplayName("TTL 到期后条目失效")
    void entryExpiresAfterTtl() throws InterruptedException {
        cache.set("short", "v", Duration.ofMillis(120));

        assertEquals("v", cache.get("short", String.class));
        Thread.sleep(250);
        assertNull(cache.get("short", String.class), "超过 TTL 后应取不到");
    }

    @Test
    @DisplayName("不同 key 可以有不同 TTL，互不影响")
    void perEntryTtlIsIndependent() throws InterruptedException {
        cache.set("fast", "A", Duration.ofMillis(100));
        cache.set("slow", "B", Duration.ofSeconds(30));

        Thread.sleep(250);

        assertNull(cache.get("fast", String.class), "短 TTL 的应已过期");
        assertEquals("B", cache.get("slow", String.class), "长 TTL 的不应受影响");
    }

    @Test
    @DisplayName("set 会重置 TTL（不是续期而是重新计时）")
    void setResetsTtl() throws InterruptedException {
        cache.set("k", "v1", Duration.ofMillis(300));
        Thread.sleep(150);
        cache.set("k", "v2", Duration.ofMillis(300));
        Thread.sleep(200);

        // 若不重置，此时总耗时 350ms > 300ms，应该已过期
        assertEquals("v2", cache.get("k", String.class), "重新 set 后 TTL 应从此刻重新计算");
    }

    @Test
    @DisplayName("increment：首次为 1，之后逐次递增")
    void incrementCountsUp() {
        assertEquals(1L, cache.increment("rl", Duration.ofMinutes(1)));
        assertEquals(2L, cache.increment("rl", Duration.ofMinutes(1)));
        assertEquals(3L, cache.increment("rl", Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("increment 是固定窗口：持续自增不会把过期时间往后推")
    void incrementUsesFixedWindow() throws InterruptedException {
        Duration window = Duration.ofMillis(400);

        assertEquals(1L, cache.increment("win", window));
        // 在窗口内反复自增，如果实现是「每次自增都续期」，窗口会被无限延长
        for (int i = 0; i < 6; i++) {
            Thread.sleep(50);
            cache.increment("win", window);
        }
        // 此时距首次自增已 300ms，仍在窗口内
        assertTrue(cache.exists("win"), "窗口内应仍然存在");

        Thread.sleep(300);
        assertNull(cache.get("win", Long.class), "超过窗口后计数应清零重来");
        assertEquals(1L, cache.increment("win", window), "过期后重新从 1 开始");
    }

    @Test
    @DisplayName("getOrLoad：命中缓存时不调用 loader")
    void getOrLoadHitsCache() {
        AtomicInteger loads = new AtomicInteger();

        String first = cache.getOrLoad("k", String.class, Duration.ofMinutes(5), () -> {
            loads.incrementAndGet();
            return "loaded";
        });
        String second = cache.getOrLoad("k", String.class, Duration.ofMinutes(5), () -> {
            loads.incrementAndGet();
            return "loaded-again";
        });

        assertEquals("loaded", first);
        assertEquals("loaded", second, "第二次应直接命中缓存");
        assertEquals(1, loads.get(), "loader 只应被调用一次");
    }

    @Test
    @DisplayName("delete / deleteByPrefix：按前缀批量清理")
    void deleteByPrefix() {
        cache.set("profile:1", "a", Duration.ofMinutes(5));
        cache.set("profile:2", "b", Duration.ofMinutes(5));
        cache.set("other:1", "c", Duration.ofMinutes(5));

        assertEquals(2L, cache.deleteByPrefix("profile:"));

        assertFalse(cache.exists("profile:1"));
        assertFalse(cache.exists("profile:2"));
        assertTrue(cache.exists("other:1"), "不匹配前缀的不应被删");

        assertTrue(cache.delete("other:1"));
        assertFalse(cache.delete("other:1"), "重复删除返回 false");
    }

    @Test
    @DisplayName("数字类型能按目标类型转换（Integer / Long / Double）")
    void convertsNumberTypes() {
        cache.set("n", 42, Duration.ofMinutes(5));

        assertEquals(42, cache.get("n", Integer.class));
        assertEquals(42L, cache.get("n", Long.class));
        assertEquals(42.0, cache.get("n", Double.class));
    }

    @Test
    @DisplayName("存对象再取出，字段完整")
    void storesComplexObject() {
        Sample sample = new Sample();
        sample.setId(7L);
        sample.setName("番茄");
        cache.set("obj", sample, Duration.ofMinutes(5));

        Sample loaded = cache.get("obj", Sample.class);
        assertNotNull(loaded);
        assertEquals(7L, loaded.getId());
        assertEquals("番茄", loaded.getName());
    }

    /** 测试用简单对象。 */
    @lombok.Data
    public static class Sample {
        private Long id;
        private String name;
    }
}
