package com.smartmeal.service.user;

import com.smartmeal.domain.enums.ActivityLevelEnum;
import com.smartmeal.domain.enums.GoalEnum;
import com.smartmeal.service.user.bo.UserProfile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 营养计算。
 *
 * <p>用 Mifflin-St Jeor 公式估算基础代谢率（BMR），再乘活动系数得到 TDEE，
 * 最后按目标做热量调整。把这段计算放在服务端而不是交给大模型，
 * 是因为它是确定性的数学问题 —— 交给模型只会引入不确定性。
 *
 * <pre>
 * 男：BMR = 10 × 体重(kg) + 6.25 × 身高(cm) - 5 × 年龄 + 5
 * 女：BMR = 10 × 体重(kg) + 6.25 × 身高(cm) - 5 × 年龄 - 161
 * TDEE = BMR × 活动系数
 * 目标热量 = TDEE × (1 + 目标调整比例)
 * </pre>
 */
@Component
public class NutritionCalculator {

    /** 安全下限：无论目标多激进，每日热量不建议低于此值。 */
    private static final int MIN_CALORIE = 1200;
    /** 安全上限。 */
    private static final int MAX_CALORIE = 4000;

    public void fill(UserProfile profile) {
        if (profile.getHeightCm() == null || profile.getWeightKg() == null || profile.getAge() == null) {
            // 数据不全时给一个中庸值，避免后续除零和空指针
            profile.setBmi(0);
            profile.setBmr(0);
            profile.setTdee(0);
            profile.setDailyCalorieTarget(1800);
            return;
        }

        double height = profile.getHeightCm().doubleValue();
        double weight = profile.getWeightKg().doubleValue();
        int age = profile.getAge();

        // BMI：体重(kg) / 身高(m)²
        double heightM = height / 100.0;
        profile.setBmi(BigDecimal.valueOf(weight / (heightM * heightM))
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue());

        // 性别缺省按男性公式（0 未知 / 1 男 / 2 女）
        double bmr = (profile.getGender() != null && profile.getGender() == 2)
                ? 10 * weight + 6.25 * height - 5 * age - 161
                : 10 * weight + 6.25 * height - 5 * age + 5;

        int bmrInt = (int) Math.round(bmr);
        double factor = ActivityLevelEnum.of(profile.getActivityLevel()).getFactor();
        int tdee = (int) Math.round(bmrInt * factor);

        GoalEnum goal = GoalEnum.of(profile.getGoal());
        int target = (int) Math.round(tdee * (1 + goal.getCalorieAdjustRatio()));
        target = Math.max(MIN_CALORIE, Math.min(MAX_CALORIE, target));

        profile.setBmr(bmrInt);
        profile.setTdee(tdee);
        profile.setDailyCalorieTarget(target);
    }
}
