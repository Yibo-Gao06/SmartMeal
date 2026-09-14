package com.smartmeal.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 知识库同步。
 *
 * <p>职责：把菜谱、食材的增量变更同步到向量库，保证 RAG 检索用的是最新数据。
 *
 * <p><b>当前是空实现（占位）。</b>原因：本机没有部署 pgvector，Embedding 模型也未接入
 * （DeepSeek 不提供 Embedding 接口）。检索目前走 {@code InMemoryRetrievalService}
 * 直接查 MySQL，天然不存在「数据不同步」的问题。
 *
 * <p>接入 pgvector 后，这里要实现的逻辑是：
 * <ol>
 *   <li>按 {@code t_knowledge_document.version} 找出变更的文档；</li>
 *   <li>切片 → 调 Embedding 模型 → 写入 {@code t_knowledge_embedding}；</li>
 *   <li>失败的切片记入重试队列，由 {@code EmbeddingRetryJob} 补偿。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "smartmeal.job.knowledge-sync-enabled", havingValue = "true")
public class KnowledgeSyncJob {

    @Scheduled(cron = "0 0 3 * * ?")
    public void syncKnowledgeBase() {
        log.info("知识库同步任务触发（当前为占位实现，接入 pgvector 后启用）");
    }
}
