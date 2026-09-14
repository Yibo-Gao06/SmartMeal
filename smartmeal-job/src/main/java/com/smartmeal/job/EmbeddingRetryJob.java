package com.smartmeal.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Embedding 失败重试。
 *
 * <p>向量化是外部调用，必然会有失败（限流、超时、额度不足）。
 * 失败不能直接丢掉，否则知识库会缺数据，检索质量悄悄下降 —— 这种问题最难排查。
 *
 * <p>所以设计上要求：失败的切片落一张重试表，由本任务按指数退避重试，
 * 超过最大次数后告警而不是静默丢弃。
 *
 * <p>当前为占位实现，原因同 {@link KnowledgeSyncJob}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "smartmeal.job.knowledge-sync-enabled", havingValue = "true")
public class EmbeddingRetryJob {

    @Scheduled(fixedDelay = 300_000L)
    public void retryFailedEmbeddings() {
        log.debug("Embedding 重试任务触发（当前为占位实现）");
    }
}
