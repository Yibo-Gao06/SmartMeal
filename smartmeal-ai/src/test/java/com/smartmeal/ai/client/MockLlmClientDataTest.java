package com.smartmeal.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmeal.domain.dto.plan.MealPlanResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 Mock 演示数据的「像样程度」。
 *
 * <p>Mock 数据的价值在于「让整条链路能真跑通」，但它的三处失真曾经把演示效果搞得很难看，
 * 而且都是跑起来查库才发现的：
 * <ol>
 *   <li><b>菜谱 ID 是编的</b>：用 {@code 5000 + index} 算，而库里从 5001 开始，
 *       于是每份计划都夹带一个不存在的 5000，前端点详情会 404；</li>
 *   <li><b>天数写死 7</b>：请求 3 天却返回 7 天，主表与明细对不上；</li>
 *   <li><b>热量离目标太远</b>：3 餐合计约 1000 千卡，对 2155 的目标偏离 50%，
 *       前端刷一屏「偏离目标」警告，看着像系统算错了。</li>
 * </ol>
 */
class MockLlmClientDataTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 数据库里真实存在的菜谱 ID 区间（见 mysql-init.sql 的 t_recipe 种子数据）。 */
    private static final long MIN_RECIPE_ID = 5001L;
    private static final long MAX_RECIPE_ID = 5010L;

    /**
     * 一份贴近 PromptBuilder 真实输出的 userPrompt。
     *
     * <p>刻意保留了 JSON Schema 里 {@code "dailyCalorieTarget": 0} 这个占位值 ——
     * 它在真实 Prompt 里排在用户 JSON 之后，但如果哪天顺序变了，
     * 解析器必须能识别出「0 不是有效目标」而不是拿它去算份数。
     */
    private static final String REALISTIC_PROMPT = """
            用户信息如下：

            {"goal":"loss_fat","goalLabel":"减脂","heightCm":175,"weightKg":85,"age":26,"bmi":27.8,"activityLevel":"middle","allergens":[],"forbiddenIngredients":[],"fridgeIngredients":[],"days":3,"mealsPerDay":["breakfast","lunch","dinner"],"tastePreference":"清淡","dailyCalorieTarget":2155,"bmr":1738,"tdee":2694}

            检索到的知识如下：

            {}

            请严格按照下面的 JSON Schema 生成 3 天膳食计划，并给出购物清单。
            只输出 JSON 本身，不要包裹代码块标记。

            {
              "summary": "string，整份计划的总体说明",
              "dailyCalorieTarget": 0,
              "warnings": ["string"],
              "days": [ { "dayNo": 1 } ]
            }
            """;

    private MealPlanResult generate(String prompt) throws Exception {
        MockLlmClient client = new MockLlmClient(MAPPER);
        String json = client.chat("system prompt", prompt).content();
        assertNotNull(json, "Mock 必须返回内容");
        return MAPPER.readValue(json, MealPlanResult.class);
    }

    // ==================== 反查目标热量 ====================

    @Nested
    @DisplayName("反查 dailyCalorieTarget")
    class CalorieTargetResolution {

        @Test
        @DisplayName("从用户 JSON 里取到真实目标")
        void readsRealTarget() {
            assertEquals(2155, MockLlmClient.resolveCalorieTarget(REALISTIC_PROMPT));
        }

        @Test
        @DisplayName("JSON Schema 里的 0 占位值不能当目标")
        void ignoresSchemaPlaceholder() {
            // 只有 schema、没有用户 JSON 的场景
            String schemaOnly = "{\"dailyCalorieTarget\": 0}";
            assertEquals(MockLlmClient.DEFAULT_CALORIE_TARGET,
                    MockLlmClient.resolveCalorieTarget(schemaOnly),
                    "0 是 schema 的占位值，拿它算份数会把所有餐都压成 1 份");
        }

        @Test
        @DisplayName("null / 空串 / 无该字段都回退默认值")
        void fallsBackToDefault() {
            assertEquals(MockLlmClient.DEFAULT_CALORIE_TARGET, MockLlmClient.resolveCalorieTarget(null));
            assertEquals(MockLlmClient.DEFAULT_CALORIE_TARGET, MockLlmClient.resolveCalorieTarget(""));
            assertEquals(MockLlmClient.DEFAULT_CALORIE_TARGET,
                    MockLlmClient.resolveCalorieTarget("{\"goal\":\"loss_fat\"}"));
        }

        @Test
        @DisplayName("异常值收敛到合理区间")
        void clampsExtremeValues() {
            assertEquals(800, MockLlmClient.resolveCalorieTarget("{\"dailyCalorieTarget\":50}"));
            assertEquals(6000, MockLlmClient.resolveCalorieTarget("{\"dailyCalorieTarget\":99999}"));
        }
    }

    // ==================== 反查目标文案 ====================

    @Nested
    @DisplayName("反查 goalLabel")
    class GoalLabelResolution {

        @Test
        @DisplayName("取到中文目标文案")
        void readsLabel() {
            assertEquals("减脂", MockLlmClient.resolveGoalLabel(REALISTIC_PROMPT));
        }

        @Test
        @DisplayName("缺失时回退默认文案")
        void fallsBackToDefault() {
            assertEquals(MockLlmClient.DEFAULT_GOAL_LABEL, MockLlmClient.resolveGoalLabel(null));
            assertEquals(MockLlmClient.DEFAULT_GOAL_LABEL,
                    MockLlmClient.resolveGoalLabel("{\"goal\":\"loss_fat\"}"));
        }
    }

    // ==================== 按餐次选菜 ====================

    @Nested
    @DisplayName("按餐次选菜")
    class RecipePicking {

        @Test
        @DisplayName("选出的菜与请求的餐次一致，不会早餐推荐炒菠菜")
        void picksMatchingMealType() {
            for (String mealType : new String[]{"breakfast", "lunch", "dinner"}) {
                for (int dayNo = 1; dayNo <= 7; dayNo++) {
                    MockLlmClient.MockRecipe recipe = MockLlmClient.pickRecipe(mealType, dayNo);
                    assertEquals(mealType, recipe.mealType(),
                            "第 " + dayNo + " 天的 " + mealType + " 选到了 " + recipe.mealType() + " 的菜");
                }
            }
        }

        @Test
        @DisplayName("第 1 天从池子里第一个开始，跨天轮换")
        void rotatesByDay() {
            MockLlmClient.MockRecipe day1 = MockLlmClient.pickRecipe("dinner", 1);
            MockLlmClient.MockRecipe day2 = MockLlmClient.pickRecipe("dinner", 2);
            assertFalse(day1.id() == day2.id(), "相邻两天的晚餐应该是不同的菜");
        }

        @Test
        @DisplayName("未知餐次兜底返回第一道菜，不抛异常")
        void unknownMealTypeFallsBack() {
            assertNotNull(MockLlmClient.pickRecipe("brunch", 1));
        }
    }

    // ==================== 份数换算 ====================

    @Nested
    @DisplayName("份数换算")
    class ServingsCalculation {

        @Test
        @DisplayName("按单餐目标四舍五入，至少 1 份")
        void roundsToNearestServing() {
            // 单餐目标 718 千卡（2155 / 3）
            assertEquals(3, MockLlmClient.servingsFor(220, 718));
            assertEquals(2, MockLlmClient.servingsFor(420, 718));
            assertEquals(2, MockLlmClient.servingsFor(300, 718));
            assertEquals(1, MockLlmClient.servingsFor(520, 718));
            assertEquals(6, MockLlmClient.servingsFor(120, 718));
        }

        @Test
        @DisplayName("目标远小于单份热量时，至少给 1 份而不是 0 份")
        void neverZero() {
            // 500 千卡的单份菜去凑 100 千卡的单餐目标，四舍五入是 0 份 ——
            // 0 份的菜没有意义，必须兜到 1
            assertEquals(1, MockLlmClient.servingsFor(500, 100));
        }

        @Test
        @DisplayName("非法入参返回 1 份而不是抛异常")
        void handlesIllegalInput() {
            assertEquals(1, MockLlmClient.servingsFor(0, 718));
            assertEquals(1, MockLlmClient.servingsFor(220, 0));
            assertEquals(1, MockLlmClient.servingsFor(-1, -1));
        }
    }

    // ==================== 端到端：生成的计划本身要「像样」 ====================

    @Nested
    @DisplayName("生成的计划")
    class GeneratedPlan {

        @Test
        @DisplayName("★ 所有菜谱 ID 都真实存在于库里")
        void allRecipeIdsExist() throws Exception {
            MealPlanResult result = generate(REALISTIC_PROMPT);

            Set<Long> ids = new HashSet<>();
            for (MealPlanResult.Day day : result.getDays()) {
                for (MealPlanResult.Meal meal : day.getMeals()) {
                    assertNotNull(meal.getRecipeId(), "菜谱 ID 不能为空");
                    ids.add(meal.getRecipeId());
                }
            }
            assertFalse(ids.isEmpty());
            for (Long id : ids) {
                assertTrue(id >= MIN_RECIPE_ID && id <= MAX_RECIPE_ID,
                        "菜谱 ID " + id + " 不在种子数据区间 "
                                + MIN_RECIPE_ID + "~" + MAX_RECIPE_ID + " 内，"
                                + "PlanValidator 会判定为模型编造");
            }
        }

        @Test
        @DisplayName("★ 生成天数等于请求天数")
        void dayCountMatchesRequest() throws Exception {
            assertEquals(3, generate(REALISTIC_PROMPT).getDays().size());

            String sevenDays = REALISTIC_PROMPT.replace("\"days\":3", "\"days\":7");
            assertEquals(7, generate(sevenDays).getDays().size());
        }

        @Test
        @DisplayName("★ 每天总热量落在目标 ±20% 容差内")
        void dailyCaloriesWithinTolerance() throws Exception {
            MealPlanResult result = generate(REALISTIC_PROMPT);
            BigDecimal target = BigDecimal.valueOf(2155);
            BigDecimal lower = target.multiply(new BigDecimal("0.80"));
            BigDecimal upper = target.multiply(new BigDecimal("1.20"));

            for (MealPlanResult.Day day : result.getDays()) {
                BigDecimal total = BigDecimal.ZERO;
                for (MealPlanResult.Meal meal : day.getMeals()) {
                    total = total.add(meal.getNutrition().getCalories());
                }
                assertTrue(total.compareTo(lower) >= 0 && total.compareTo(upper) <= 0,
                        "第 " + day.getDayNo() + " 天合计 " + total
                                + " 千卡超出 " + lower + "~" + upper + " 容差，"
                                + "前端会刷一屏「偏离目标」警告");
            }
        }

        @Test
        @DisplayName("份数 > 1 时，食材用量与营养值同步放大")
        void servingsScaleIngredientsAndNutrition() throws Exception {
            MealPlanResult result = generate(REALISTIC_PROMPT);

            // 池子里单份最大 520 千卡、单品最大 250 单位（牛奶 250ml），
            // 所以「实际值 <= 基准上限 × 份数」是判断份数有没有参与计算的有效上界
            BigDecimal maxCaloriesPerServing = BigDecimal.valueOf(520);
            BigDecimal maxAmountPerServing = BigDecimal.valueOf(250);

            for (MealPlanResult.Day day : result.getDays()) {
                for (MealPlanResult.Meal meal : day.getMeals()) {
                    assertTrue(meal.getServings() >= 1, "份数至少为 1");
                    BigDecimal servings = BigDecimal.valueOf(meal.getServings());

                    assertTrue(meal.getNutrition().getCalories()
                                    .compareTo(maxCaloriesPerServing.multiply(servings)) <= 0,
                            "营养值应与份数成正比，超出说明份数没参与计算");

                    for (MealPlanResult.IngredientItem item : meal.getIngredients()) {
                        assertTrue(item.getAmount()
                                        .compareTo(maxAmountPerServing.multiply(servings)) <= 0,
                                "食材用量应与份数成正比，超出说明份数没参与计算");
                    }
                }
            }
        }

        @Test
        @DisplayName("★ 每道菜挂的食材就是这道菜自己的食材，不会串味")
        void ingredientsBelongToTheRecipe() throws Exception {
            MealPlanResult result = generate(REALISTIC_PROMPT);

            // 种子数据里「番茄炒蛋」是番茄 + 鸡蛋；「豆腐菌菇煲」是豆腐 + 菠菜。
            // 曾经用 offset = recipe.id() % 池子大小 随手取食材，
            // 结果番茄炒蛋配出了鸡胸肉、豆腐菌菇煲配出了牛奶 —— 界面上一眼假。
            Map<String, Set<String>> expected = Map.of(
                    "番茄炒蛋", Set.of("番茄", "鸡蛋"),
                    "豆腐菌菇煲", Set.of("豆腐", "菠菜"),
                    "蒜蓉炒菠菜", Set.of("菠菜"),
                    "清蒸鸡胸肉配西兰花", Set.of("鸡胸肉", "西兰花"));

            int checked = 0;
            for (MealPlanResult.Day day : result.getDays()) {
                for (MealPlanResult.Meal meal : day.getMeals()) {
                    Set<String> want = expected.get(meal.getRecipeName());
                    if (want == null) {
                        continue;
                    }
                    Set<String> actual = new HashSet<>();
                    for (MealPlanResult.IngredientItem item : meal.getIngredients()) {
                        actual.add(item.getIngredientName());
                    }
                    assertEquals(want, actual,
                            "「" + meal.getRecipeName() + "」的食材应为 " + want + "，实际是 " + actual);
                    checked++;
                }
            }
            assertTrue(checked > 0, "3 天的计划里应该至少出现一次被校验的菜");
        }

        @Test
        @DisplayName("★ 每个菜谱都配了食材，且食材 ID 都存在")
        void everyRecipeHasIngredients() {
            for (MockLlmClient.MockRecipe recipe : List.of(
                    MockLlmClient.pickRecipe("breakfast", 1),
                    MockLlmClient.pickRecipe("lunch", 1),
                    MockLlmClient.pickRecipe("dinner", 1))) {
                List<MockLlmClient.MockRecipeIngredient> items =
                        MockLlmClient.recipeIngredients(recipe);
                assertNotNull(items, "菜谱 " + recipe.name() + " 没有配置食材");
                assertFalse(items.isEmpty(), "菜谱 " + recipe.name() + " 的食材列表为空");
                for (MockLlmClient.MockRecipeIngredient item : items) {
                    assertNotNull(MockLlmClient.ingredientOf(item.ingredientId()),
                            "菜谱 " + recipe.name() + " 引用了不存在的食材 ID " + item.ingredientId());
                }
            }
        }

        @Test
        @DisplayName("★ 食材用量随份数放大，且各菜用量独立")
        void ingredientAmountFollowsServings() throws Exception {
            MealPlanResult result = generate(REALISTIC_PROMPT);

            // 种子数据里鸡胸肉在 5003 是每份 150g、在 5006 是每份 120g。
            // 这两个值必须各自成立 —— 如果用量被塞进「食材主数据」，
            // 就只能有一个值，另一道菜必然是错的。
            Map<String, String> perServing = Map.of(
                    "清蒸鸡胸肉配西兰花", "150",
                    "凉拌鸡丝黄瓜", "120");

            for (MealPlanResult.Day day : result.getDays()) {
                for (MealPlanResult.Meal meal : day.getMeals()) {
                    String base = perServing.get(meal.getRecipeName());
                    if (base == null) {
                        continue;
                    }
                    BigDecimal expected = new BigDecimal(base)
                            .multiply(BigDecimal.valueOf(meal.getServings()));
                    for (MealPlanResult.IngredientItem item : meal.getIngredients()) {
                        if ("鸡胸肉".equals(item.getIngredientName())) {
                            assertEquals(0, expected.compareTo(item.getAmount()),
                                    "「" + meal.getRecipeName() + "」" + meal.getServings()
                                            + " 份的鸡胸肉应为 " + expected + "g，实际 " + item.getAmount());
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("summary 与 warnings 都带上了演示数据标记")
        void marksDemoData() throws Exception {
            MealPlanResult result = generate(REALISTIC_PROMPT);
            assertTrue(result.getSummary().contains("演示数据"));
            assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("演示数据")));
        }
    }
}
