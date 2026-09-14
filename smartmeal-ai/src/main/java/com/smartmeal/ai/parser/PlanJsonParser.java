package com.smartmeal.ai.parser;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmeal.common.exception.BusinessException;
import com.smartmeal.common.result.ResultCode;
import com.smartmeal.domain.dto.plan.MealPlanResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 大模型输出的 JSON 解析。
 *
 * <p>真实环境里模型的输出有各种脏法，这里逐个处理：
 * <ul>
 *   <li>包了 {@code ```json ... ```} 代码块 —— 虽然 Prompt 里禁止了，但模型偶尔还是会加；</li>
 *   <li>前后带解释文字（「好的，这是您的食谱：」）—— 用首尾大括号定位截取；</li>
 *   <li>结尾被 max_tokens 截断 —— 这种情况没法救，抛错交给上层修复或降级；</li>
 *   <li>字段多出几个 —— 用 {@code FAIL_ON_UNKNOWN_PROPERTIES=false} 忽略。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanJsonParser {

    private final ObjectMapper objectMapper;

    public MealPlanResult parse(String raw) {
        return tryParse(raw).orElseThrow(() -> {
            log.error("模型输出无法解析为 JSON，原始内容前 500 字符：{}",
                    raw == null ? "null" : raw.substring(0, Math.min(raw.length(), 500)));
            return new BusinessException(ResultCode.AI_PARSE_FAILED);
        });
    }

    public Optional<MealPlanResult> tryParse(String raw) {
        String json = extractJson(raw);
        if (json == null) {
            return Optional.empty();
        }
        try {
            MealPlanResult result = lenientMapper().readValue(json, MealPlanResult.class);
            return Optional.ofNullable(result);
        } catch (Exception e) {
            log.warn("JSON 反序列化失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 从模型输出里抠出 JSON 主体。
     *
     * <p>策略：先剥代码块围栏，再从第一个 {@code {} 截到最后一个 {@code }}。
     * 比正则匹配「完整 JSON」更稳 —— 正则处理嵌套结构很容易失败。
     */
    public String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();

        // 剥掉 ```json ... ``` 或 ``` ... ```
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            int fenceEnd = text.lastIndexOf("```");
            if (fenceEnd > 0) {
                text = text.substring(0, fenceEnd);
            }
            text = text.trim();
        }

        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    /** 宽松模式：模型多给字段不报错。 */
    private ObjectMapper lenientMapper() {
        return objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false);
    }
}
