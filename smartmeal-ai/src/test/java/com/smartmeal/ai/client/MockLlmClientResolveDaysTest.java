package com.smartmeal.ai.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 钉住 Mock 客户端的天数解析。
 *
 * <p>背景：Mock 曾经硬编码 {@code buildJson(7)}，导致「请求 3 天」返回 7 天计划，
 * 落库后主表 {@code t_meal_plan.days=3} 而明细表有 7 行，数据自相矛盾。
 *
 * <p>现在 {@code PlanValidator} 把天数不符判为硬错误，所以解析一旦出错就是整个请求失败，
 * 这个纯函数必须有测试兜住。
 */
class MockLlmClientResolveDaysTest {

    @Nested
    @DisplayName("正常解析")
    class NormalCases {

        @Test
        @DisplayName("标准 JSON 片段")
        void parsesStandardJson() {
            assertEquals(3, MockLlmClient.resolveDays("{\"goal\":\"loss_fat\",\"days\":3}"));
        }

        @Test
        @DisplayName("冒号两侧有空格")
        void parsesWithSpaces() {
            assertEquals(5, MockLlmClient.resolveDays("{ \"days\" : 5 }"));
        }

        @Test
        @DisplayName("真实 Prompt 里字段很多，仍能准确取到 days")
        void parsesFromRealisticPrompt() {
            String prompt = """
                    用户信息如下：

                    {"goal":"loss_fat","heightCm":175,"weightKg":85,"age":26,"days":7,"mealsPerDay":3}

                    请严格按照下面的 JSON Schema 生成 7 天膳食计划。
                    """;
            assertEquals(7, MockLlmClient.resolveDays(prompt));
        }

        @Test
        @DisplayName("取第一个匹配，不受后面同名键干扰")
        void takesFirstMatch() {
            assertEquals(3, MockLlmClient.resolveDays("{\"days\":3,\"other\":{\"days\":9}}"));
        }
    }

    @Nested
    @DisplayName("边界与兜底")
    class EdgeCases {

        @Test
        @DisplayName("null 回退默认 7 天")
        void nullFallsBackToDefault() {
            assertEquals(MockLlmClient.DEFAULT_DAYS, MockLlmClient.resolveDays(null));
        }

        @Test
        @DisplayName("空串回退默认 7 天")
        void emptyFallsBackToDefault() {
            assertEquals(MockLlmClient.DEFAULT_DAYS, MockLlmClient.resolveDays(""));
        }

        @Test
        @DisplayName("不含 days 字段回退默认 7 天")
        void missingFieldFallsBackToDefault() {
            assertEquals(MockLlmClient.DEFAULT_DAYS, MockLlmClient.resolveDays("{\"goal\":\"loss_fat\"}"));
        }

        @Test
        @DisplayName("超大值收敛到上限，避免 Mock 生成天量数据")
        void hugeValueClampedToMax() {
            assertEquals(14, MockLlmClient.resolveDays("{\"days\":999}"));
        }

        @Test
        @DisplayName("0 收敛到下限 1，避免生成空计划")
        void zeroClampedToOne() {
            assertEquals(1, MockLlmClient.resolveDays("{\"days\":0}"));
        }

        @Test
        @DisplayName("负数不是合法 JSON 天数，回退默认值")
        void negativeFallsBackToDefault() {
            assertEquals(MockLlmClient.DEFAULT_DAYS, MockLlmClient.resolveDays("{\"days\":-3}"));
        }
    }
}
