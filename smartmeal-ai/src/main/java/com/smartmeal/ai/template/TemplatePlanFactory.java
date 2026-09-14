package com.smartmeal.ai.template;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartmeal.domain.dto.plan.MealPlanResult;
import com.smartmeal.domain.entity.Recipe;
import com.smartmeal.repository.mapper.RecipeMapper;
import com.smartmeal.service.user.bo.UserProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 降级模板。
 *
 * <p>「模型挂了怎么办」是这类系统的必答题。答案不能是「返回错误页」——
 * 那等于把 AI 服务的不稳定性直接暴露给用户。
 *
 * <p>这里的做法是：从平台真实菜谱库里按目标挑菜，拼出一份结构完全合法的计划。
 * 它没有个性化推理，但<b>能保证用户始终拿到一份可执行的方案并完成下单</b>。
 * 商业上这比「优雅的报错」有价值得多。
 *
 * <p>降级会在 {@code t_meal_plan.status} 标记为 {@code FALLBACK}，
 * 并在 warnings 里明确告知用户，不假装是 AI 生成的结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TemplatePlanFactory {

    private final RecipeMapper recipeMapper;

    public MealPlanResult build(UserProfile profile) {
        List<Recipe> all = recipeMapper.selectList(
                Wrappers.<Recipe>lambdaQuery().eq(Recipe::getStatus, 1));
        if (all.isEmpty()) {
            log.error("菜谱库为空，无法生成降级模板");
            return empty(profile);
        }

        MealPlanResult result = new MealPlanResult();
        result.setSummary("（降级模板）AI 服务暂时不可用，已为你准备一份基于平台菜谱库的标准安排");
        result.setDailyCalorieTarget(profile.getDailyCalorieTarget());
        result.setWarnings(new ArrayList<>(List.of(
                "AI 服务暂时不可用，当前展示的是预置模板食谱，个性化程度有限。",
                "本系统提供膳食建议仅供参考，不构成医疗诊断或治疗方案。")));

        List<MealPlanResult.Day> days = new ArrayList<>();
        for (int dayNo = 1; dayNo <= profile.getDays(); dayNo++) {
            MealPlanResult.Day day = new MealPlanResult.Day();
            day.setDayNo(dayNo);
            day.setSummary("第 " + dayNo + " 天（模板安排）");

            List<MealPlanResult.Meal> meals = new ArrayList<>();
            for (String mealType : profile.getMealsPerDay()) {
                Recipe recipe = pick(all, mealType, dayNo);
                if (recipe != null) {
                    meals.add(toMeal(recipe, mealType));
                }
            }
            day.setMeals(meals);
            days.add(day);
        }
        result.setDays(days);
        result.setShoppingList(new ArrayList<>());

        log.warn("已生成降级模板计划 天数={}", days.size());
        return result;
    }

    /** 按餐次轮转挑菜，保证同一餐次不会连着吃同一道。 */
    private Recipe pick(List<Recipe> all, String mealType, int dayNo) {
        List<Recipe> matched = all.stream()
                .filter(r -> mealType.equals(r.getMealType()))
                .toList();
        List<Recipe> pool = matched.isEmpty() ? all : matched;
        return pool.get((dayNo - 1) % pool.size());
    }

    private MealPlanResult.Meal toMeal(Recipe recipe, String mealType) {
        MealPlanResult.Meal meal = new MealPlanResult.Meal();
        meal.setMealType(mealType);
        meal.setRecipeId(recipe.getId());
        meal.setRecipeName(recipe.getName());
        meal.setReason("平台精选菜谱");
        meal.setServings(1);

        MealPlanResult.Nutrition nutrition = new MealPlanResult.Nutrition();
        nutrition.setCalories(nz(recipe.getCalories()));
        nutrition.setProtein(nz(recipe.getProtein()));
        nutrition.setFat(nz(recipe.getFat()));
        nutrition.setCarb(nz(recipe.getCarb()));
        meal.setNutrition(nutrition);

        meal.setIngredients(new ArrayList<>());
        meal.setSourceIds(new ArrayList<>(List.of("RECIPE_" + recipe.getId())));
        return meal;
    }

    private MealPlanResult empty(UserProfile profile) {
        MealPlanResult result = new MealPlanResult();
        result.setSummary("菜谱库为空，暂时无法生成任何计划");
        result.setDailyCalorieTarget(profile.getDailyCalorieTarget());
        result.setWarnings(new ArrayList<>(List.of("平台菜谱库尚未初始化，请联系管理员导入菜谱数据。")));
        result.setDays(new ArrayList<>());
        result.setShoppingList(new ArrayList<>());
        return result;
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
