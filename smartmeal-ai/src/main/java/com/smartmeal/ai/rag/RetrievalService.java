package com.smartmeal.ai.rag;

import com.smartmeal.service.user.bo.UserProfile;

import java.util.List;

/**
 * 知识检索（RAG 的 R）。
 *
 * <p>这一层是 RAG 的核心，也是「换向量库不动业务代码」的隔离点。
 * 当前有 {@link InMemoryRetrievalService} 一个实现，后续接 pgvector 时
 * 只需要新增实现类并改配置，PromptBuilder 与 AiPlannerService 完全不用动。
 */
public interface RetrievalService {

    /**
     * 按用户画像召回相关知识。
     *
     * <p>实现必须做到两件事：
     * <ol>
     *   <li>把用户过敏原对应的食材<b>从候选集中剔除</b>，而不是指望模型自己避开；</li>
     *   <li>只返回平台真实存在的菜谱与食材，杜绝模型凭空编造。</li>
     * </ol>
     */
    List<KnowledgeChunk> retrieve(UserProfile profile, int topK);

    /** 当前后端类型，用于日志与健康检查。 */
    String backend();
}
