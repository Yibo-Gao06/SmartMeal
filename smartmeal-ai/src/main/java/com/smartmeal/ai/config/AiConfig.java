package com.smartmeal.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 模块装配：线程池与配置绑定。
 *
 * <p>为什么 AI 生成必须走独立线程池，不能占用 Tomcat 的请求线程：
 * 一次生成要 10~60 秒，Tomcat 默认 200 个请求线程。如果直接在上面跑，
 * 十几个用户同时生成就能把线程池占满，导致<b>整个应用所有接口一起卡死</b>。
 * 这是典型的「慢依赖拖垮整个服务」故障。
 *
 * <p>所以这里用独立线程池隔离，并设置明确的上限：
 * 队列满了直接拒绝并返回友好提示，而不是无限排队把内存撑爆。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    /** AI 生成线程池：IO 密集（等模型返回），线程数可以适当放大。 */
    @Bean("aiTaskExecutor")
    public ThreadPoolTaskExecutor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("ai-plan-");
        // 拒绝策略用 Abort：宁可明确告诉用户「排队满了」，也不要无限堆积
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 优雅停机：等待正在生成的任务跑完，避免用户拿到半截结果
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /** 心跳调度器：每个 SSE 连接一个轻量任务，定期发心跳穿透代理的空闲超时。 */
    @Bean(name = "aiHeartbeatScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService aiHeartbeatScheduler() {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "ai-heartbeat-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }
}
