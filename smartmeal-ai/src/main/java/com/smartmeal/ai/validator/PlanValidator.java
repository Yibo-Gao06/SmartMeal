package com.smartmeal.ai.validator;

import com.smartmeal.domain.dto.plan.MealPlanResult;
import com.smartmeal.domain.entity.Ingredient;
import com.smartmeal.domain.entity.Recipe;
import com.smartmeal.repository.mapper.IngredientMapper;
import com.smartmeal.repository.mapper.RecipeMapper;
import com.smartmeal.service.user.bo.UserProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 大模型输出后置校验。
 *
 * <p><b>这是整个项目里最重要的一道防线。</b>
 * 面试常被问「你怎么保证模型不胡说」，标准答案不是「我把 Prompt 写好了」，
 * 而是「我不信任模型」——所有关键约束都在后端重新验证一遍。
 *
 * <p>五类校验：
 * <ol>
 *   <li><b>过敏原</b>（硬）：ID 集合求交 + 名称兜底匹配，双重判定；</li>
 *   <li><b>结构完整性</b>（硬）：天数必须与请求一致，餐次、食材不能为空；</li>
 *   <li><b>实体存在性</b>（软）：菜谱 ID、食材 ID 必须在库里真实存在；</li>
 *   <li><b>热量区间</b>（软）：日均热量偏离目标超过 ±20% 给警告；</li>
 *   <li><b>重复度</b>（软）：同一道菜出现次数过多给警告，避免「七天全是番茄炒蛋」。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanValidator {

    /** 热量允许偏差比例，超过即警告。 */
    private static final double CALORIE_TOLERANCE = 0.20;

    /** 同一道菜在整份计划里允许出现的最大次数。 */
    private static final int MAX_RECIPE_REPEAT = 3;

    private final IngredientMapper ingredientMapper;
    private final RecipeMapper recipeMapper;

    public ValidationReport validate(MealPlanResult result, UserProfile profile) {
        ValidationReport report = new ValidationReport();

        // 结果为 null 说明模型什么都没吐出来。后面几个校验方法都直接读 result.getDays()，
        // 这里必须短路返回 —— 否则「模型返回结果为空」这个分支根本走不到，
        // 用户看到的是 500 而不是一条可读的失败原因。
        if (result == null) {
            report.addError("模型返回结果为空");
            log.warn("计划校验未通过 plan 校验错误={}", report.errorSummary());
            return report;
        }

        validateStructure(result, profile, report);
        validateAllergens(result, profile, report);
        validateEntities(result, report);
        validateNutrition(result, profile, report);
        validateDiversity(result, report);

        if (report.hasError()) {
            log.warn("计划校验未通过 plan 校验错误={}", report.errorSummary());
        } else if (!report.getWarnings().isEmpty()) {
            log.info("计划校验通过，但有 {} 条警告", report.getWarnings().size());
        }
        return report;
    }

    // ---------- 1. 结构 ----------
    private void validateStructure(MealPlanResult result, UserProfile profile, ValidationReport report) {
        if (result.getDays() == null || result.getDays().isEmpty()) {
            report.addError("计划中没有任何一天的安排");
            return;
        }

        // 天数必须与请求一致，判硬错误而不是警告。
        //
        // 理由是「少给天数」和「热量偏高」性质不同：后者是建议优化，前者是需求没被满足。
        // 曾经这里只检查「天数不为空」，于是出现主表 days=3、明细却有 7 天的自相矛盾数据
        // （Mock 硬编码 7 天所致），前端按主表渲染会多出 4 天，按明细渲染又对不上主表。
        // 判硬错误的额外好处是重试有意义 —— 模型下次很可能就按对的天数输出。
        int expected = profile.getDays();
        int actual = result.getDays().size();
        if (expected > 0 && actual != expected) {
            report.addError("计划天数不符：要求 " + expected + " 天，模型返回 " + actual + " 天");
        }

        for (MealPlanResult.Day day : result.getDays()) {
            if (day.getMeals() == null || day.getMeals().isEmpty()) {
                report.addError("第 " + day.getDayNo() + " 天没有任何餐次");
            }
        }
    }

    // ---------- 2. 过敏原（硬校验） ----------
    private void validateAllergens(MealPlanResult result, UserProfile profile, ValidationReport report) {
        if (!profile.hasAllergy()) {
            return;
        }
        Set<Long> forbiddenIds = profile.allergenIngredientIds();
        Set<String> forbiddenNames = new LinkedHashSet<>(profile.getAllergenIngredientNames().values());
        // 编码本身也作为名称兜底，防止映射表缺数据时完全失效
        forbiddenNames.addAll(profile.getAllergenCodes());

        // 申报了过敏原、却一个食材都没展开出来 —— 说明 t_allergen_ingredient 漏配了。
        // 这时 ID 判据完全用不上，只剩一个很弱的名称兜底，必须显式告诉用户，
        // 不能让用户以为「系统已经帮我挡住了」。
        if (forbiddenIds.isEmpty()) {
            report.addWarning("以下过敏原尚未配置食材映射，本次无法做精确校验，"
                    + "仅按食材名称做了粗略比对，请务必自行核对配料表：" + profile.getAllergenCodes());
            log.warn("过敏原 {} 无食材映射，精确校验不可用", profile.getAllergenCodes());
        }

        Set<String> hits = new LinkedHashSet<>();
        if (result.getDays() != null) {
            for (MealPlanResult.Day day : result.getDays()) {
                if (day.getMeals() == null) {
                    continue;
                }
                for (MealPlanResult.Meal meal : day.getMeals()) {
                    if (meal.getIngredients() == null) {
                        continue;
                    }
                    for (MealPlanResult.IngredientItem item : meal.getIngredients()) {
                        // 判据一：食材 ID 命中映射表（精确）
                        if (item.getIngredientId() != null && forbiddenIds.contains(item.getIngredientId())) {
                            hits.add(describe(meal, item));
                            continue;
                        }
                        // 判据二：名称包含过敏原关键词（兜底，防映射表漏配）
                        String name = item.getIngredientName();
                        if (name != null && forbiddenNames.stream()
                                .filter(f -> f != null && f.length() >= 2)
                                .anyMatch(name::contains)) {
                            hits.add(describe(meal, item));
                        }
                    }
                }
            }
        }

        if (!hits.isEmpty()) {
            report.addError("检测到过敏原成分，已拒绝该方案：" + String.join("、", hits));
        }
    }

    private String describe(MealPlanResult.Meal meal, MealPlanResult.IngredientItem item) {
        return meal.getRecipeName() + "中的" + item.getIngredientName();
    }

    // ---------- 3. 实体存在性 ----------
    private void validateEntities(MealPlanResult result, ValidationReport report) {
        Set<Long> recipeIds = new HashSet<>();
        Set<Long> ingredientIds = new HashSet<>();
        if (result.getDays() != null) {
            for (MealPlanResult.Day day : result.getDays()) {
                if (day.getMeals() == null) {
                    continue;
                }
                for (MealPlanResult.Meal meal : day.getMeals()) {
                    if (meal.getRecipeId() != null) {
                        recipeIds.add(meal.getRecipeId());
                    }
                    if (meal.getIngredients() != null) {
                        meal.getIngredients().stream()
                                .map(MealPlanResult.IngredientItem::getIngredientId)
                                .filter(java.util.Objects::nonNull)
                                .forEach(ingredientIds::add);
                    }
                }
            }
        }

        if (!recipeIds.isEmpty()) {
            List<Recipe> found = recipeMapper.selectBatchIds(recipeIds);
            if (found.size() < recipeIds.size()) {
                Set<Long> existing = new HashSet<>(found.stream().map(Recipe::getId).toList());
                recipeIds.removeAll(existing);
                report.addWarning("以下菜谱 ID 在平台不存在，可能是模型编造的：" + recipeIds);
            }
        }
        if (!ingredientIds.isEmpty()) {
            List<Ingredient> found = ingredientMapper.selectBatchIds(ingredientIds);
            if (found.size() < ingredientIds.size()) {
                Set<Long> existing = new HashSet<>(found.stream().map(Ingredient::getId).toList());
                ingredientIds.removeAll(existing);
                report.addWarning("以下食材 ID 在平台不存在，可能是模型编造的：" + ingredientIds);
            }
        }
    }

    // ---------- 4. 热量 ----------
    private void validateNutrition(MealPlanResult result, UserProfile profile, ValidationReport report) {
        int target = profile.getDailyCalorieTarget();
        if (target <= 0 || result.getDays() == null) {
            return;
        }
        for (MealPlanResult.Day day : result.getDays()) {
            if (day.getMeals() == null) {
                continue;
            }
            BigDecimal total = day.getMeals().stream()
                    .map(MealPlanResult.Meal::getNutrition)
                    .filter(java.util.Objects::nonNull)
                    .map(MealPlanResult.Nutrition::getCalories)
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            double deviation = Math.abs(total.doubleValue() - target) / target;
            if (deviation > CALORIE_TOLERANCE) {
                report.addWarning(String.format(
                        "第 %d 天总热量 %.0f 千卡，偏离目标 %d 千卡 %.0f%%",
                        day.getDayNo(), total.doubleValue(), target, deviation * 100));
            }
        }
    }

    // ---------- 5. 重复度 ----------
    private void validateDiversity(MealPlanResult result, ValidationReport report) {
        Map<String, Integer> counter = new HashMap<>();
        if (result.getDays() == null) {
            return;
        }
        for (MealPlanResult.Day day : result.getDays()) {
            if (day.getMeals() == null) {
                continue;
            }
            for (MealPlanResult.Meal meal : day.getMeals()) {
                if (meal.getRecipeName() != null) {
                    counter.merge(meal.getRecipeName(), 1, Integer::sum);
                }
            }
        }
        counter.forEach((name, count) -> {
            if (count > MAX_RECIPE_REPEAT) {
                report.addWarning("「" + name + "」在计划中出现 " + count + " 次，建议增加菜式多样性");
            }
        });
    }
}
