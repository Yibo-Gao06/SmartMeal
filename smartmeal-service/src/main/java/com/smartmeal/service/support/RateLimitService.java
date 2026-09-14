package com.smartmeal.service.support;

import com.smartmeal.common.cache.CacheService;
import com.smartmeal.common.constant.CommonConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 固定窗口限流。
 *
 * <p>基于 {@link CacheService#increment} 实现，因此天然兼容 Redis 与本地缓存两种后端。
 * 为什么 AI 接口必须限流：单次生成要烧几万 token，一个脚本就能把预算刷爆。
 *
 * <p>固定窗口的固有缺陷是临界点双倍流量（窗口末尾 + 下个窗口开头）。
 * 对本场景可以接受；如果要求更平滑，换成滑动窗口或令牌桶。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final CacheService cacheService;

    /**
     * 尝试放行一次。
     *
     * @param userId   用户维度限流，避免影响其他用户
     * @param action   动作名，用于区分不同接口
     * @param limit    窗口内最大次数
     * @param window   窗口长度
     * @return true 放行 / false 拒绝
     */
    public boolean tryAcquire(Long userId, String action, int limit, Duration window) {
        String key = CommonConstants.CACHE_RATE_AI + action + ":" + userId;
        long count = cacheService.increment(key, window);
        if (count > limit) {
            log.warn("触发限流 userId={} action={} count={} limit={}", userId, action, count, limit);
            return false;
        }
        return true;
    }

    /** AI 生成接口的默认配额：每分钟 5 次。 */
    public boolean tryAcquireAiPlan(Long userId) {
        return tryAcquire(userId, "meal-plan", 5, Duration.ofMinutes(1));
    }
}
