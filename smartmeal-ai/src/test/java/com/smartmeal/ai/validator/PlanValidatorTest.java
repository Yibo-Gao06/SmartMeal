package com.smartmeal.ai.validator;

import com.smartmeal.domain.dto.plan.MealPlanResult;
import com.smartmeal.domain.entity.Ingredient;
import com.smartmeal.domain.entity.Recipe;
import com.smartmeal.repository.mapper.IngredientMapper;
import com.smartmeal.repository.mapper.RecipeMapper;
import com.smartmeal.service.user.bo.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * {@link PlanValidator} 的行为约束。
 *
 * <p>这是项目里最重要的一道防线 —— 面试时被问「你怎么保证模型不胡说」，
 * 答案就落在这个类上。所以它的测试重点不是覆盖率，而是<b>把每一种漏拦的可能性钉死</b>。
 *
 * <p>过敏原校验有两条判据，必须能各自独立生效：
 * <ol>
 *   <li><b>ID 判据</b>：用户对花生过敏 → 映射表展开成 {花生, 花生油, 花生酱} 的 ID 集合。
 *       模型只要返回了这些 ID 中的任何一个就拦下，<b>哪怕食材名写成「食用调和油」</b>；</li>
 *   <li><b>名称判据</b>：映射表漏配时，靠食材名包含过敏原关键词兜底。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class PlanValidatorTest {

    @Mock
    private IngredientMapper ingredientMapper;

    @Mock
    private RecipeMapper recipeMapper;

    private PlanValidator validator;

    @BeforeEach
    void setUp() {
        validator = new PlanValidator(ingredientMapper, recipeMapper);

        // 默认：所有被引用的 ID 都「在库里存在」，避免实体存在性警告干扰其他断言。
        // 用 lenient 是因为不是每个用例都会走到实体校验。
        lenient().when(recipeMapper.selectBatchIds(any(Collection.class))).thenAnswer(inv -> {
            Collection<? extends Serializable> ids = inv.getArgument(0);
            List<Recipe> list = new ArrayList<>();
            for (Serializable id : ids) {
                Recipe recipe = new Recipe();
                recipe.setId(Long.valueOf(id.toString()));
                list.add(recipe);
            }
            return list;
        });
        lenient().when(ingredientMapper.selectBatchIds(any(Collection.class))).thenAnswer(inv -> {
            Collection<? extends Serializable> ids = inv.getArgument(0);
            List<Ingredient> list = new ArrayList<>();
            for (Serializable id : ids) {
                Ingredient ingredient = new Ingredient();
                ingredient.setId(Long.valueOf(id.toString()));
                list.add(ingredient);
            }
            return list;
        });
    }

    // ==================== 构造测试数据 ====================

    /** 一份最小可用的计划：1 天 1 餐 1 个食材。 */
    private MealPlanResult planWith(Long ingredientId, String ingredientName) {
        MealPlanResult.IngredientItem item = new MealPlanResult.IngredientItem();
        item.setIngredientId(ingredientId);
        item.setIngredientName(ingredientName);
        item.setAmount(new BigDecimal("100"));
        item.setUnit("g");

        MealPlanResult.Meal meal = new MealPlanResult.Meal();
        meal.setMealType("lunch");
        meal.setRecipeId(5001L);
        meal.setRecipeName("番茄炒蛋");
        meal.setIngredients(new ArrayList<>(List.of(item)));

        MealPlanResult.Day day = new MealPlanResult.Day();
        day.setDayNo(1);
        day.setMeals(new ArrayList<>(List.of(meal)));

        MealPlanResult result = new MealPlanResult();
        result.setDays(new ArrayList<>(List.of(day)));
        return result;
    }

    /** 无过敏原的档案。 */
    private UserProfile noAllergyProfile() {
        UserProfile profile = new UserProfile();
        profile.setUserId(1L);
        profile.setDailyCalorieTarget(2000);
        // 必须与 planWith() 生成的 1 天对齐：validateStructure 会校验「实际天数 == 请求天数」，
        // 不设的话 UserProfile.days 默认是 7，每个用例都会先因为天数不符而挂掉，
        // 把真正的断言目标（过敏原 / 热量 / 重复度）淹没在无关错误里。
        profile.setDays(1);
        return profile;
    }

    /** 声明了过敏原、且映射表已正确展开的档案。 */
    private UserProfile profileWithExpanded(String code, Map<Long, String> expanded) {
        UserProfile profile = noAllergyProfile();
        profile.setAllergenCodes(new java.util.LinkedHashSet<>(List.of(code)));
        profile.setAllergenIngredientNames(new LinkedHashMap<>(expanded));
        return profile;
    }

    /**
     * 生成指定天数、每天 1 餐 1 食材的计划。
     *
     * <p>用于天数一致性校验：{@code planWith()} 固定只给 1 天，测不出「要 7 天给 3 天」。
     * 这里统一用同一个 recipeId，3 天以内不会触发重复度警告，干扰最小。
     */
    private MealPlanResult planWithDays(int days) {
        List<MealPlanResult.Day> list = new ArrayList<>();
        for (int d = 1; d <= days; d++) {
            MealPlanResult.Meal meal = new MealPlanResult.Meal();
            meal.setMealType("lunch");
            meal.setRecipeId(5001L);
            meal.setRecipeName("番茄炒蛋");
            meal.setIngredients(new ArrayList<>());

            MealPlanResult.Day day = new MealPlanResult.Day();
            day.setDayNo(d);
            day.setMeals(new ArrayList<>(List.of(meal)));
            list.add(day);
        }
        MealPlanResult result = new MealPlanResult();
        result.setDays(new ArrayList<>(list));
        return result;
    }

    // ==================== 过敏原：ID 判据 ====================

    @Test
    @DisplayName("ID 判据：食材名不含「花生」也要拦下——这正是展开映射表的意义")
    void blocksByIngredientIdEvenWhenNameLooksInnocent() {
        // 花生油 1013 是「花生」的衍生品，映射表已展开
        UserProfile profile = profileWithExpanded("PEANUT", Map.of(1013L, "花生油"));
        // 模型把名字写成了「食用调和油」，纯名称匹配会漏掉
        MealPlanResult result = planWith(1013L, "食用调和油");

        ValidationReport report = validator.validate(result, profile);

        assertTrue(report.hasError(), "ID 命中映射表必须判为硬错误");
        assertTrue(report.errorSummary().contains("食用调和油"), "错误信息里要指出具体是哪个食材");
    }

    @Test
    @DisplayName("ID 判据：多个衍生品命中时全部列出")
    void listsAllAllergenHits() {
        Map<Long, String> expanded = new LinkedHashMap<>();
        expanded.put(1012L, "花生");
        expanded.put(1014L, "花生酱");
        UserProfile profile = profileWithExpanded("PEANUT", expanded);

        MealPlanResult result = planWith(1014L, "花生酱");

        ValidationReport report = validator.validate(result, profile);

        assertTrue(report.hasError());
        assertTrue(report.errorSummary().contains("花生酱"));
    }

    // ==================== 过敏原：名称判据 ====================

    @Test
    @DisplayName("名称判据：映射表漏配衍生品时，靠名称兜底拦住")
    void blocksByNameWhenMappingMissesDerivedProduct() {
        // 映射表里只有「花生」1012，没有「花生酱」
        UserProfile profile = profileWithExpanded("PEANUT", Map.of(1012L, "花生"));
        // 模型返回了一个不在映射表里的花生酱 ID
        MealPlanResult result = planWith(8888L, "花生酱");

        ValidationReport report = validator.validate(result, profile);

        assertTrue(report.hasError(), "名称包含过敏原关键词也必须拦下");
        assertTrue(report.errorSummary().contains("花生酱"));
    }

    @Test
    @DisplayName("名称判据：单个字的过敏原关键词不参与匹配，避免误伤")
    void ignoresTooShortKeywords() {
        // 「蛋」只有一个字，代码里要求关键词长度 >= 2 才参与 contains 匹配
        UserProfile profile = profileWithExpanded("EGG", Map.of(1002L, "蛋"));
        MealPlanResult result = planWith(7777L, "皮蛋瘦肉粥");

        ValidationReport report = validator.validate(result, profile);

        assertFalse(report.hasError(),
                "长度 1 的关键词不匹配，否则「蛋」会误伤「皮蛋」「蛋糕」等大量正常食材");
    }

    // ==================== 过敏原：映射表缺失 ====================

    @Test
    @DisplayName("★ 映射表缺行时不能静默跳过校验")
    void doesNotSilentlySkipWhenMappingIsMissing() {
        // 用户申报了「树坚果」过敏，但 t_allergen_ingredient 里没有 TREE_NUT 的记录，
        // 于是 expandAllergens 返回空 Map —— 这是真实会发生的运营漏配场景。
        UserProfile profile = noAllergyProfile();
        profile.setAllergenCodes(new java.util.LinkedHashSet<>(List.of("TREE_NUT")));
        profile.setAllergenIngredientNames(new LinkedHashMap<>());

        MealPlanResult result = planWith(9999L, "腰果");

        ValidationReport report = validator.validate(result, profile);

        assertFalse(report.getWarnings().isEmpty(),
                "申报了过敏原却没有任何食材映射，必须给出「无法精确校验」的警告，"
                        + "绝不能因为 Map 为空就整段跳过——那等于过敏原保护完全失效");
        assertTrue(report.getWarnings().stream().anyMatch(w -> w.contains("TREE_NUT")),
                "警告里要点明是哪个过敏原没配置映射");
    }

    @Test
    @DisplayName("完全没申报过敏原时，不做任何过敏原校验也不告警")
    void noAllergyMeansNoCheck() {
        UserProfile profile = noAllergyProfile();
        MealPlanResult result = planWith(1012L, "花生");

        ValidationReport report = validator.validate(result, profile);

        assertFalse(report.hasError(), "没申报过敏原就不该拦");
        assertTrue(report.getWarnings().stream().noneMatch(w -> w.contains("过敏原")),
                "没申报过敏原不该产生过敏原相关警告");
    }

    // ==================== 结构校验 ====================

    @Test
    @DisplayName("结构：结果为 null / 没有任何一天，都判硬错误且不抛 NPE")
    void structureErrors() {
        ValidationReport nullReport = validator.validate(null, noAllergyProfile());
        assertTrue(nullReport.hasError());
        assertTrue(nullReport.errorSummary().contains("为空"),
                "result 为 null 时要短路返回一条可读错误，不能让它掉进 validateEntities 抛 NPE");

        MealPlanResult emptyDays = new MealPlanResult();
        emptyDays.setDays(new ArrayList<>());
        assertTrue(validator.validate(emptyDays, noAllergyProfile()).hasError());
    }

    @Test
    @DisplayName("结构：结果为 null 且用户有过敏原时，同样不能抛 NPE")
    void nullResultWithAllergyDoesNotThrow() {
        UserProfile profile = profileWithExpanded("PEANUT", Map.of(1012L, "花生"));

        ValidationReport report = validator.validate(null, profile);

        assertTrue(report.hasError());
    }

    @Test
    @DisplayName("结构：某一天没有任何餐次，判硬错误并指出是哪一天")
    void dayWithoutMealsIsError() {
        MealPlanResult result = new MealPlanResult();
        MealPlanResult.Day day = new MealPlanResult.Day();
        day.setDayNo(3);
        day.setMeals(new ArrayList<>());
        result.setDays(new ArrayList<>(List.of(day)));

        ValidationReport report = validator.validate(result, noAllergyProfile());

        assertTrue(report.hasError());
        assertTrue(report.errorSummary().contains("3"), "错误信息要指明是第几天");
    }

    @Test
    @DisplayName("★ 结构：生成天数少于请求天数，判硬错误")
    void fewerDaysThanRequestedIsHardError() {
        // 用户要 7 天，模型只给了 3 天。
        // 修复前这里只检查「天数不为空」，于是 Mock 硬编码 7 天时会产出
        // 主表 t_meal_plan.days=3、明细 t_meal_plan_day 却有 7 行的自相矛盾数据。
        // 判硬错误而不是警告：少给天数是需求没被满足，且重试很可能就对了。
        UserProfile profile = noAllergyProfile();
        profile.setDays(7);

        ValidationReport report = validator.validate(planWithDays(3), profile);

        assertTrue(report.hasError(), "天数不符必须判硬错误");
        assertTrue(report.errorSummary().contains("7") && report.errorSummary().contains("3"),
                "错误信息要同时给出要求的天数和实际返回的天数，方便排查");
    }

    @Test
    @DisplayName("★ 结构：生成天数多于请求天数，同样判硬错误")
    void moreDaysThanRequestedIsHardError() {
        UserProfile profile = noAllergyProfile();
        profile.setDays(3);

        ValidationReport report = validator.validate(planWithDays(7), profile);

        assertTrue(report.hasError(), "多给天数也不对：用户只安排了 3 天，多出来的没有意义");
    }

    @Test
    @DisplayName("结构：天数一致时不产生天数相关错误")
    void matchingDayCountIsSilent() {
        UserProfile profile = noAllergyProfile();
        profile.setDays(3);

        ValidationReport report = validator.validate(planWithDays(3), profile);

        assertFalse(report.hasError(), "天数一致不该报错");
        assertTrue(report.getWarnings().stream().noneMatch(w -> w.contains("天数")),
                "天数一致时也不该出现天数相关警告");
    }

    // ==================== 热量校验（软） ====================

    @Test
    @DisplayName("热量：偏离目标超过 ±20% 只给警告，不阻断")
    void calorieDeviationIsWarningOnly() {
        UserProfile profile = noAllergyProfile();
        profile.setDailyCalorieTarget(2000);

        MealPlanResult result = planWith(1001L, "番茄");
        MealPlanResult.Nutrition nutrition = new MealPlanResult.Nutrition();
        nutrition.setCalories(new BigDecimal("3000")); // 偏离 50%
        result.getDays().get(0).getMeals().get(0).setNutrition(nutrition);

        ValidationReport report = validator.validate(result, profile);

        assertFalse(report.hasError(), "热量偏差不能阻断，否则模型的小偏差会让用户一直拿不到方案");
        assertTrue(report.getWarnings().stream().anyMatch(w -> w.contains("热量")));
    }

    @Test
    @DisplayName("热量：在 ±20% 以内不告警")
    void calorieWithinToleranceIsSilent() {
        UserProfile profile = noAllergyProfile();
        profile.setDailyCalorieTarget(2000);

        MealPlanResult result = planWith(1001L, "番茄");
        MealPlanResult.Nutrition nutrition = new MealPlanResult.Nutrition();
        nutrition.setCalories(new BigDecimal("2200")); // 偏离 10%
        result.getDays().get(0).getMeals().get(0).setNutrition(nutrition);

        ValidationReport report = validator.validate(result, profile);

        assertTrue(report.getWarnings().stream().noneMatch(w -> w.contains("热量")));
    }

    @Test
    @DisplayName("热量：目标热量为 0 时跳过校验，不做除零")
    void skipsCalorieCheckWhenTargetMissing() {
        UserProfile profile = noAllergyProfile();
        profile.setDailyCalorieTarget(0);

        ValidationReport report = validator.validate(planWith(1001L, "番茄"), profile);

        assertFalse(report.hasError());
    }

    // ==================== 重复度（软） ====================

    @Test
    @DisplayName("重复度：同一道菜超过 3 次给警告，避免七天全是番茄炒蛋")
    void repeatedRecipeTriggersWarning() {
        MealPlanResult result = new MealPlanResult();
        List<MealPlanResult.Day> days = new ArrayList<>();
        for (int d = 1; d <= 5; d++) {
            MealPlanResult.Meal meal = new MealPlanResult.Meal();
            meal.setMealType("lunch");
            meal.setRecipeId(5001L);
            meal.setRecipeName("番茄炒蛋");
            meal.setIngredients(new ArrayList<>());

            MealPlanResult.Day day = new MealPlanResult.Day();
            day.setDayNo(d);
            day.setMeals(new ArrayList<>(List.of(meal)));
            days.add(day);
        }
        result.setDays(new ArrayList<>(days));

        UserProfile profile = noAllergyProfile();
        profile.setDays(5); // 与上面构造的 5 天对齐，否则会先撞上天数不符的硬错误
        ValidationReport report = validator.validate(result, profile);

        assertTrue(report.getWarnings().stream().anyMatch(w -> w.contains("番茄炒蛋") && w.contains("多样性")));
    }

    @Test
    @DisplayName("重复度：恰好 3 次不算超，不告警")
    void exactlyThreeTimesIsAcceptable() {
        MealPlanResult result = new MealPlanResult();
        List<MealPlanResult.Day> days = new ArrayList<>();
        for (int d = 1; d <= 3; d++) {
            MealPlanResult.Meal meal = new MealPlanResult.Meal();
            meal.setMealType("lunch");
            meal.setRecipeName("番茄炒蛋");
            meal.setIngredients(new ArrayList<>());

            MealPlanResult.Day day = new MealPlanResult.Day();
            day.setDayNo(d);
            day.setMeals(new ArrayList<>(List.of(meal)));
            days.add(day);
        }
        result.setDays(new ArrayList<>(days));

        UserProfile profile = noAllergyProfile();
        profile.setDays(3); // 与上面构造的 3 天对齐
        ValidationReport report = validator.validate(result, profile);

        assertTrue(report.getWarnings().stream().noneMatch(w -> w.contains("多样性")));
    }

    // ==================== 实体存在性（软） ====================

    @Test
    @DisplayName("实体：菜谱 ID 在平台不存在时给警告，不阻断")
    void nonExistingRecipeIsWarningOnly() {
        // 让菜谱查询返回空，模拟模型编造了一个不存在的菜谱 ID
        lenient().when(recipeMapper.selectBatchIds(any(Collection.class))).thenReturn(new ArrayList<>());

        ValidationReport report = validator.validate(planWith(1001L, "番茄"), noAllergyProfile());

        assertFalse(report.hasError(), "编造 ID 只是可疑，不该直接拒绝整份方案");
        assertTrue(report.getWarnings().stream().anyMatch(w -> w.contains("菜谱")));
    }
}
