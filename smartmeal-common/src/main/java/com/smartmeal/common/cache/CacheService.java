package com.smartmeal.common.cache;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 缓存抽象。
 *
 * <p>为什么要抽这一层：
 * <ol>
 *   <li>本地开发机器不一定装了 Redis，没有它整个项目就跑不起来，成本太高；</li>
 *   <li>单元测试不该依赖外部中间件；</li>
 *   <li>生产环境要 Redis 的分布式能力（多实例共享、原子计数）。</li>
 * </ol>
 *
 * <p>实现由 {@code smartmeal.cache.type} 决定：{@code redis} / {@code local}（默认）。
 */
public interface CacheService {

    /** 写入缓存。已存在的 key 会被覆盖并重置 TTL。 */
    <T> void set(String key, T value, Duration ttl);

    /** 读取缓存，类型不匹配或不存在返回 {@code null}。 */
    <T> T get(String key, Class<T> type);

    /** 删除单个 key，返回是否真的删掉了。 */
    boolean delete(String key);

    /**
     * 按前缀批量删除。
     *
     * <p>注意：Redis 下用 {@code SCAN} 实现，禁止用 {@code KEYS}（会阻塞主线程）。
     *
     * @return 删除的 key 数量
     */
    long deleteByPrefix(String prefix);

    boolean exists(String key);

    /**
     * 缓存未命中时通过 {@code loader} 加载并回填，用于挡住缓存击穿。
     *
     * <p>注意：本地实现基于 Caffeine 的 {@code get(key, mappingFunction)}，
     * 同一 key 的并发加载是串行的；Redis 实现无法做到这一点，需要分布式锁才能完全防击穿。
     */
    <T> T getOrLoad(String key, Class<T> type, Duration ttl, Supplier<T> loader);

    /**
     * 固定窗口计数，用于接口限流与登录失败计数。
     *
     * <p>第一次调用创建计数器并设置 TTL，后续自增不重置过期时间。
     *
     * <p><b>请用本方法的返回值做判定，不要 {@code get} 回来再判。</b>
     * 计数器的存储方式两种实现并不一致：本地实现存 {@code AtomicLong} 对象，
     * Redis 实现存的是 {@code INCR} 产生的裸整数。后者能被 {@code get(key, Long.class)}
     * 读回来，靠的是「裸数字恰好是合法 JSON」这个巧合 ——
     * 换个存储格式（比如改成 JSON 对象）就会静默读不到值。
     * 返回值是接口契约，不依赖任何存储细节。
     *
     * @return 自增后的计数值
     */
    long increment(String key, Duration window);

    /** 当前生效的实现名，用于日志与健康检查。 */
    String backend();
}
