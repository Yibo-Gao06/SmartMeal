package com.smartmeal.ai.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmeal.common.exception.BusinessException;
import com.smartmeal.domain.dto.plan.MealPlanResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlanJsonParser} 的容错能力。
 *
 * <p>Prompt 里明确要求「只输出 JSON」，但真实模型不会每次都听话。
 * 这个类存在的唯一理由就是兜住这些脏输出 —— 每一条 {@code extractJson} 的规则
 * 都对应一种线上真实出现过的脏法，所以测试必须逐个覆盖。
 */
class PlanJsonParserTest {

    private final PlanJsonParser parser = new PlanJsonParser(new ObjectMapper());

    private static final String VALID_JSON = """
            {
              "summary": "一周减脂餐",
              "dailyCalorieTarget": 1800,
              "days": [
                {
                  "dayNo": 1,
                  "meals": [
                    {
                      "mealType": "breakfast",
                      "recipeId": 5001,
                      "recipeName": "番茄炒蛋",
                      "ingredients": [
                        {"ingredientId": 1001, "ingredientName": "番茄", "amount": 200, "unit": "g"}
                      ],
                      "nutrition": {"calories": 220, "protein": 14, "fat": 14, "carb": 8}
                    }
                  ]
                }
              ]
            }
            """;

    @Test
    @DisplayName("干净 JSON：直接解析成功")
    void parsesCleanJson() {
        MealPlanResult result = parser.parse(VALID_JSON);

        assertEquals("一周减脂餐", result.getSummary());
        assertEquals(1800, result.getDailyCalorieTarget());
        assertEquals(1, result.getDays().size());
        assertEquals("番茄炒蛋", result.getDays().get(0).getMeals().get(0).getRecipeName());
        assertEquals(1001L, result.getDays().get(0).getMeals().get(0)
                .getIngredients().get(0).getIngredientId());
    }

    @Test
    @DisplayName("包了 ```json 代码块：剥掉围栏再解析")
    void stripsJsonFence() {
        String raw = "```json\n" + VALID_JSON + "\n```";

        assertEquals("一周减脂餐", parser.parse(raw).getSummary());
    }

    @Test
    @DisplayName("包了无语言标记的 ``` 代码块：同样能剥")
    void stripsPlainFence() {
        String raw = "```\n" + VALID_JSON + "\n```";

        assertEquals("一周减脂餐", parser.parse(raw).getSummary());
    }

    @Test
    @DisplayName("前后带解释文字：用首尾大括号截取")
    void extractsJsonFromSurroundingProse() {
        String raw = "好的，这是为您定制的一周食谱：\n" + VALID_JSON + "\n希望对您有帮助！";

        assertEquals("一周减脂餐", parser.parse(raw).getSummary());
    }

    @Test
    @DisplayName("代码块 + 前后都有话：两道处理都要生效")
    void handlesFenceWithProse() {
        String raw = "以下是结果：\n```json\n" + VALID_JSON + "\n```\n需要我调整吗？";

        assertEquals("一周减脂餐", parser.parse(raw).getSummary());
    }

    @Test
    @DisplayName("模型多吐了字段：忽略掉，不影响解析")
    void ignoresUnknownFields() {
        String raw = """
                {"summary":"x","days":[],"extraFieldFromModel":"whatever","another":{"deep":1}}
                """;

        MealPlanResult result = parser.parse(raw);

        assertEquals("x", result.getSummary());
    }

    @Test
    @DisplayName("嵌套 JSON 里有花括号字符串：仍能正确定位主体")
    void handlesNestedBraces() {
        String raw = "前言 {\"summary\":\"含 } 的说明\",\"days\":[]} 后记";

        assertEquals("含 } 的说明", parser.parse(raw).getSummary());
    }

    @Test
    @DisplayName("被 max_tokens 截断的 JSON：解析失败并抛业务异常，交给上层降级")
    void truncatedJsonFails() {
        String truncated = VALID_JSON.substring(0, VALID_JSON.length() / 2);

        assertThrows(BusinessException.class, () -> parser.parse(truncated),
                "截断的 JSON 救不回来，必须抛错让上层走修复或降级，不能返回半截对象");
    }

    @Test
    @DisplayName("null / 空串 / 纯文本：都抛业务异常")
    void invalidInputThrows() {
        assertThrows(BusinessException.class, () -> parser.parse(null));
        assertThrows(BusinessException.class, () -> parser.parse("   "));
        assertThrows(BusinessException.class, () -> parser.parse("抱歉，我无法完成这个请求。"));
    }

    @Test
    @DisplayName("tryParse：失败时返回 empty 而不是抛异常")
    void tryParseReturnsEmptyOnFailure() {
        Optional<MealPlanResult> ok = parser.tryParse(VALID_JSON);
        assertTrue(ok.isPresent());

        Optional<MealPlanResult> bad = parser.tryParse("这不是 JSON");
        assertFalse(bad.isPresent(), "tryParse 用于「试探性修复」，不该抛异常");

        assertFalse(parser.tryParse(null).isPresent());
        assertFalse(parser.tryParse("").isPresent());
    }

    @Test
    @DisplayName("extractJson：没有大括号时返回 null")
    void extractJsonReturnsNullWithoutBraces() {
        assertNull(parser.extractJson("完全没有 JSON"));
        assertNull(parser.extractJson(null));
        // 只有左括号没有右括号，属于截断，不能硬截
        assertNull(parser.extractJson("{\"a\":1"));
    }

    @Test
    @DisplayName("days 缺省时返回空列表而不是 null，避免调用方空指针")
    void missingDaysBecomesEmptyList() {
        MealPlanResult result = parser.parse("{\"summary\":\"只有摘要\"}");

        assertEquals("只有摘要", result.getSummary());
        // 字段初始化器保证了这一点，校验器据此判断「没有安排」
        assertTrue(result.getDays() != null && result.getDays().isEmpty());
    }
}
