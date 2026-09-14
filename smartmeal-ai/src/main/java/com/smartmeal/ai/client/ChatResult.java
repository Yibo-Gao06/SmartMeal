package com.smartmeal.ai.client;

import org.springframework.ai.chat.metadata.Usage;

/** 一次模型调用的结果。 */
public record ChatResult(String content, Integer promptTokens, Integer completionTokens,
                         Integer totalTokens, String model) {

    public static ChatResult of(String content, Usage usage, String model) {
        if (usage == null) {
            return new ChatResult(content, null, null, null, model);
        }
        return new ChatResult(content, usage.getPromptTokens(), usage.getCompletionTokens(),
                usage.getTotalTokens(), model);
    }

    public static ChatResult of(String content, String model) {
        return new ChatResult(content, null, null, null, model);
    }
}
