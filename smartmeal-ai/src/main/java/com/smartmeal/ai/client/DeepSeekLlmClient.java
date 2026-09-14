package com.smartmeal.ai.client;

import com.smartmeal.ai.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * DeepSeek 客户端。
 *
 * <p>DeepSeek 提供 OpenAI 兼容协议，所以直接复用 Spring AI 的 OpenAI 实现，
 * 只把 {@code base-url} 指向 {@code https://api.deepseek.com} 即可，无需自己写 HTTP 层。
 *
 * <p>两个实现细节：
 * <ul>
 *   <li>流式模式下 token 用量在最后一个 chunk 的 metadata 里，所以用 {@code chatResponse()}
 *       而不是 {@code content()} —— 后者拿不到 usage，成本统计就断了；</li>
 *   <li>{@code toIterable()} 是阻塞式消费，跑在 MVC 的工作线程上没问题；
 *       但绝不能放在 Netty 事件循环里，会直接阻塞 IO 线程。</li>
 * </ul>
 */
@Slf4j
public class DeepSeekLlmClient implements LlmClient {

    private final ChatClient chatClient;
    private final AiProperties properties;

    public DeepSeekLlmClient(ChatClient chatClient, AiProperties properties) {
        this.chatClient = chatClient;
        this.properties = properties;
    }

    @Override
    public ChatResult chat(String systemPrompt, String userPrompt) {
        ChatResponse response = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .options(options())
                .call()
                .chatResponse();

        String content = extractText(response);
        Usage usage = response == null || response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
        return ChatResult.of(content, usage, properties.getModel());
    }

    @Override
    public ChatResult streamChat(String systemPrompt, String userPrompt, Consumer<String> onToken) {
        StringBuilder buffer = new StringBuilder();
        AtomicReference<Usage> usageRef = new AtomicReference<>();

        chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .options(options())
                .stream()
                .chatResponse()
                .toIterable()
                .forEach(response -> {
                    if (response == null) {
                        return;
                    }
                    String text = extractText(response);
                    if (text != null && !text.isEmpty()) {
                        buffer.append(text);
                        onToken.accept(text);
                    }
                    // usage 只在最后一个 chunk 出现，每片都尝试覆盖，最后留下的就是总量
                    if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                        usageRef.set(response.getMetadata().getUsage());
                    }
                });

        return ChatResult.of(buffer.toString(), usageRef.get(), properties.getModel());
    }

    @Override
    public String modelName() {
        return properties.getModel();
    }

    /**
     * 每次请求都带上 options。
     *
     * <p>temperature 压低是为了 JSON 稳定性；maxTokens 设上限是为了防止
     * 模型跑飞把费用烧穿。注意 DeepSeek 不支持 OpenAI 的 {@code json_object}
     * 之外的严格 schema 约束，所以格式兜底还得靠后端的解析与修复。
     */
    private OpenAiChatOptions options() {
        return OpenAiChatOptions.builder()
                .model(properties.getModel())
                .temperature(properties.getTemperature())
                .maxTokens(properties.getMaxTokens())
                .build();
    }

    private String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }
}
