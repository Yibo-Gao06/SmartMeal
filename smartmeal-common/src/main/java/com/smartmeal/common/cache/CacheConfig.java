package com.smartmeal.common.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 缓存装配。
 *
 * <pre>
 * smartmeal.cache.type = redis  → RedisCacheService（多实例生产环境）
 * smartmeal.cache.type = local  → LocalCacheService（默认，零依赖可启动）
 * </pre>
 *
 * <p>Redis 实现的装配用 {@link ObjectProvider} 延迟取 {@link StringRedisTemplate}：
 * 即使 classpath 上有 Redis 自动配置，只要本机没装 Redis，应用依然能正常启动，
 * 只是走本地缓存。
 */
@Slf4j
@Configuration
public class CacheConfig {

    @Bean
    @ConditionalOnProperty(name = "smartmeal.cache.type", havingValue = "redis")
    public CacheService redisCacheService(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                          ObjectMapper objectMapper) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            log.warn("smartmeal.cache.type=redis 但容器里没有 StringRedisTemplate，降级为本地缓存");
            return new LocalCacheService(objectMapper, 10_000);
        }
        log.info("缓存实现: Redis");
        return new RedisCacheService(redisTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(CacheService.class)
    public CacheService localCacheService(ObjectMapper objectMapper) {
        log.info("缓存实现: Caffeine 本地缓存（未启用 Redis）");
        return new LocalCacheService(objectMapper, 10_000);
    }
}
