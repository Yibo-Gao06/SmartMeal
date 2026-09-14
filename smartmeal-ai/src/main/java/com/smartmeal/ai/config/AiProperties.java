package com.smartmeal.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** AI 模块配置，前缀 {@code smartmeal.ai}。 */
@Data
@ConfigurationProperties(prefix = "smartmeal.ai")
public class AiProperties {

    /** 总开关。关掉后走 MockLlmClient，用于本地无 Key 开发。 */
    private boolean enabled = true;

    /** 模型名。DeepSeek 提供 deepseek-chat（通用）与 deepseek-reasoner（推理）。 */
    private String model = "deepseek-chat";

    /**
     * 采样温度。
     *
     * <p>结构化输出场景必须压低。0.1~0.3 之间能在「不呆板」和「不跑格式」之间取平衡；
     * 调到 0.8 以上时 JSON 字段缺失率会明显上升。
     */
    private Double temperature = 0.2;

    private Integer maxTokens = 8000;

    /** API Key，用于判断是否配置了真实凭据。 */
    private String apiKey = "";

    /**
     * 服务地址。
     *
     * <p>DeepSeek 走 OpenAI 兼容协议，路径是 {@code /v1/chat/completions}，
     * 所以这里只写到域名，路径由 Spring AI 的 OpenAiApi 补。
     */
    private String baseUrl = "https://api.deepseek.com";

    /** RAG 检索开关。关掉后跳过知识库检索，直接让模型凭自身知识生成（仅用于对比实验）。 */
    private boolean ragEnabled = true;

    /** 向量检索召回条数。TopK 越大上下文越长，成本和延迟越高，10 是本项目的经验值。 */
    private int topK = 10;

    /** 知识不足时的最低相似度阈值，低于该值认为没检索到有效知识。 */
    private double scoreThreshold = 0.35;

    /** JSON 解析失败时，允许让模型自我修复的最大轮次。 */
    private int maxRepairAttempts = 2;

    /** 单次生成的整体超时（毫秒），超过则走降级模板。 */
    private long timeoutMillis = 120_000L;

    /**
     * 是否配置了看起来真实的 API Key。
     *
     * <p>占位符以 {@code sk-} 开头但全是 x 或含 REPLACE，一律当作没配。
     */
    public boolean hasRealApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        String trimmed = apiKey.trim();
        if (trimmed.contains("REPLACE") || trimmed.contains("xxxx")) {
            return false;
        }
        return trimmed.length() >= 20;
    }
}
