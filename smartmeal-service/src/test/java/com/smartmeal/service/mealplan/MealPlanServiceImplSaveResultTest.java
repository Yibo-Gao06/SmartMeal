package com.smartmeal.service.mealplan;

import com.smartmeal.domain.dto.plan.MealPlanResult;
import com.smartmeal.domain.entity.MealPlan;
import com.smartmeal.repository.mapper.MealPlanDayMapper;
import com.smartmeal.repository.mapper.MealPlanMapper;
import com.smartmeal.repository.mapper.MealPlanMealItemMapper;
import com.smartmeal.repository.mapper.MealPlanMealMapper;
import com.smartmeal.service.user.bo.UserProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

/**
 * 钉住一个真实发生过的数据污染 bug。
 *
 * <p>背景：{@code MealPlanResult.dailyCalorieTarget} 是模型对 Prompt 输入的复述，
 * 而 {@code UserProfile.dailyCalorieTarget} 是 {@code NutritionCalculator} 按
 * Mifflin-St Jeor 公式实算出来的。两者本应一致，但模型（以及 Mock 客户端）完全可能
 * 回传一个不同的数字。
 *
 * <p>修复前 {@code saveResult} 的写法是「result 优先、否则回落 profile」，
 * 结果出现两套标准：{@code PlanValidator} 按 profile 的 2155 判每日热量偏离度，
 * 而 {@code t_meal_plan.daily_calorie_target} 落的是 Mock 硬编码的 1600。
 * 前端拿到 1600、警告里却写着 2155，用户无从判断哪个才对。
 *
 * <p>这类用例不需要数据库：四个 mapper 全部 mock，且只传空天数列表，
 * 因此 {@code persistDetails} 的循环体不会执行，子表 mapper 一次都不会被调用。
 */
@ExtendWith(MockitoExtension.class)
class MealPlanServiceImplSaveResultTest {

    @Mock
    private MealPlanMapper mealPlanMapper;
    @Mock
    private MealPlanDayMapper dayMapper;
    @Mock
    private MealPlanMealMapper mealMapper;
    @Mock
    private MealPlanMealItemMapper mealItemMapper;

    @InjectMocks
    private MealPlanServiceImpl service;

    @Test
    @DisplayName("模型回传了错误的热量目标时，落库仍以后端实算值为准")
    void modelCalorieTargetMustNotOverrideProfile() {
        UserProfile profile = new UserProfile();
        profile.setDailyCalorieTarget(2155);

        MealPlanResult result = new MealPlanResult();
        result.setDailyCalorieTarget(1600);
        result.setDays(List.of());

        service.saveResult(1L, result, profile);

        assertEquals(Integer.valueOf(2155), capturedCalorieTarget(),
                "模型回传的 1600 不得覆盖后端实算的 2155");
    }

    @Test
    @DisplayName("模型没回传热量目标时，落库取后端实算值")
    void missingModelCalorieTargetFallsBackToProfile() {
        UserProfile profile = new UserProfile();
        profile.setDailyCalorieTarget(1800);

        MealPlanResult result = new MealPlanResult();
        result.setDailyCalorieTarget(null);
        result.setDays(List.of());

        service.saveResult(2L, result, profile);

        assertEquals(Integer.valueOf(1800), capturedCalorieTarget());
    }

    @Test
    @DisplayName("落库状态为 SUCCESS，planId 正确透传")
    void saveResultMarksPlanSuccess() {
        UserProfile profile = new UserProfile();
        profile.setDailyCalorieTarget(2000);

        MealPlanResult result = new MealPlanResult();
        result.setDays(List.of());

        service.saveResult(99L, result, profile);

        ArgumentCaptor<MealPlan> captor = ArgumentCaptor.forClass(MealPlan.class);
        verify(mealPlanMapper).updateById(captor.capture());
        assertEquals(99L, captor.getValue().getId());
        assertEquals("SUCCESS", captor.getValue().getStatus());
    }

    private Integer capturedCalorieTarget() {
        ArgumentCaptor<MealPlan> captor = ArgumentCaptor.forClass(MealPlan.class);
        verify(mealPlanMapper).updateById(captor.capture());
        return captor.getValue().getDailyCalorieTarget();
    }
}
