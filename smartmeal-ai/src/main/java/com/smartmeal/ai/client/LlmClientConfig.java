package com.smartmeal.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmeal.ai.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 客户端装配。
 *
 * <p>降级链：真实 Key → Mock。
 * 判断放在装配期而不是运行期，好处是启动日志里就能一眼看出当前跑在哪种模式，
 * 不用等第一次调用失败才发现 Key 没配。
 *
 * <p><b>为什么不用 Spring AI 自动配置的 ChatModel，而是自己构建：</b>
 * 自动配置读的是 {@code spring.ai.openai.api-key}，而本项目真正管理 Key 的地方是
 * {@code smartmeal.ai.api-key} —— 两个真相源必然会漂移。更麻烦的是
 * Spring AI 在 api-key 为空时会<b>直接抛异常导致应用起不来</b>，
 * 于是「没配 Key」这件本该优雅降级的事，变成了启动失败。
 * 所以这里让 {@code smartmeal.ai.*} 成为唯一权威，自己 new 出 ChatModel。
 */
@Slf4j
@Configuration
public class LlmClientConfig {

    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    public LlmClient llmClient(AiProperties properties, ObjectMapper objectMapper) {
        if (!properties.isEnabled()) {
            log.warn("smartmeal.ai.enabled=false，AI 模块走 Mock 客户端");
            return new MockLlmClient(objectMapper);
        }

        if (!properties.hasRealApiKey()) {
            log.warn("未检测到有效的 DEEPSEEK_API_KEY，AI 模块走 Mock 客户端。"
                    + "配置真实 Key 后重启即可切换到真实模型。");
            return new MockLlmClient(objectMapper);
        }

        ChatModel chatModel = buildDeepSeekChatModel(properties);
        log.info("AI 客户端就绪：provider=DeepSeek(OpenAI 兼容) baseUrl={} model={} temperature={}",
                properties.getBaseUrl(), properties.getModel(), properties.getTemperature());
        return new DeepSeekLlmClient(ChatClient.builder(chatModel).build(), properties);
    }

    /**
     * 用 {@code smartmeal.ai.*} 里的配置构建 DeepSeek 的 ChatModel。
     *
     * <p>DeepSeek 完整兼容 OpenAI 协议，所以直接复用 Spring AI 的 OpenAI 实现，
     * 只把 baseUrl 指过去，不需要为它单独写一套客户端。
     */
    private ChatModel buildDeepSeekChatModel(AiProperties properties) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(properties.getModel())
                .temperature(properties.getTemperature())
                .maxTokens(properties.getMaxTokens())
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }
}
