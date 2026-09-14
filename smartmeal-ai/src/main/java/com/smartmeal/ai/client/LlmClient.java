package com.smartmeal.ai.client;

import java.util.function.Consumer;

/**
 * 大模型客户端抽象。
 *
 * <p>抽这一层的理由：模型供应商会换、协议会变、本地开发还没有 Key。
 * 业务代码只依赖这个接口，换供应商只改一个实现类。
 *
 * <p>当前有两个实现：
 * <ul>
 *   <li>{@link DeepSeekLlmClient}：走 DeepSeek 的 OpenAI 兼容协议；</li>
 *   <li>{@link MockLlmClient}：无 Key 时返回结构合法的假数据，保证链路可测。</li>
 * </ul>
 */
public interface LlmClient {

    /** 一次性返回完整内容，用于「修复 JSON」这类短交互。 */
    ChatResult chat(String systemPrompt, String userPrompt);

    /**
     * 流式生成。
     *
     * @param onToken 每收到一个增量片段回调一次，由调用方决定是推给前端还是丢弃
     * @return 聚合后的完整内容 + token 消耗
     */
    ChatResult streamChat(String systemPrompt, String userPrompt, Consumer<String> onToken);

    /** 当前使用的模型名，落库用于排查与成本分析。 */
    String modelName();

    /** 是否处于 Mock 模式。前端可以据此提示「当前为演示数据」。 */
    default boolean isMock() {
        return false;
    }
}
