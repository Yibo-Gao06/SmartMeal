package com.smartmeal.service.user;

import com.smartmeal.service.user.bo.UserProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link NutritionCalculator} 的数值正确性。
 *
 * <p>这个类把「算热量」这件事从大模型手里拿回来，交给确定性的数学公式。
 * 所以它的输出必须完全可复现 —— 这里的每个期望值都是手算 Mifflin-St Jeor 公式得出的，
 * 一旦公式或系数被改动，测试会立刻失败。
 *
 * <pre>
 * 男：BMR = 10 × 体重 + 6.25 × 身高 - 5 × 年龄 + 5
 * 女：BMR = 10 × 体重 + 6.25 × 身高 - 5 × 年龄 - 161
 * TDEE = BMR × 活动系数（low 1.2 / middle 1.55 / high 1.725）
 * 目标 = TDEE × (1 + 目标系数)（减脂 -0.20 / 增肌 +0.15 / 均衡 0 / 控糖 -0.10）
 * </pre>
 */
class NutritionCalculatorTest {

    private final NutritionCalculator calculator = new NutritionCalculator();

    /** 构造一份基础档案：170cm / 80kg / 30 岁 / 男性 / 中等活动量。 */
    private UserProfile baseProfile() {
        UserProfile profile = new UserProfile();
        profile.setHeightCm(new BigDecimal("170"));
        profile.setWeightKg(new BigDecimal("80"));
        profile.setAge(30);
        profile.setGender(1);
        profile.setActivityLevel("middle");
        profile.setGoal("loss_fat");
        return profile;
    }

    @Test
    @DisplayName("男性减脂：BMR / TDEE / 目标热量逐级正确")
    void maleLossFat() {
        UserProfile profile = baseProfile();
        calculator.fill(profile);

        // BMR = 800 + 1062.5 - 150 + 5 = 1717.5 → 1718
        assertEquals(1718, profile.getBmr());
        // TDEE = 1718 × 1.55 = 2662.9 → 2663
        assertEquals(2663, profile.getTdee());
        // 目标 = 2663 × 0.8 = 2130.4 → 2130
        assertEquals(2130, profile.getDailyCalorieTarget());
        // BMI = 80 / 1.7² = 27.68 → 27.7
        assertEquals(27.7, profile.getBmi());
    }

    @Test
    @DisplayName("女性用的是 -161 而不是 +5 的公式")
    void femaleUsesDifferentFormula() {
        UserProfile profile = baseProfile();
        profile.setGender(2);
        calculator.fill(profile);

        // BMR = 800 + 1062.5 - 150 - 161 = 1551.5 → 1552
        assertEquals(1552, profile.getBmr());
        // TDEE = 1552 × 1.55 = 2405.6 → 2406
        assertEquals(2406, profile.getTdee());
        // 目标 = 2406 × 0.8 = 1924.8 → 1925
        assertEquals(1925, profile.getDailyCalorieTarget());
    }

    @Test
    @DisplayName("性别未知时按男性公式兜底")
    void unknownGenderFallsBackToMale() {
        UserProfile profile = baseProfile();
        profile.setGender(null);
        calculator.fill(profile);

        assertEquals(1718, profile.getBmr());
    }

    @Test
    @DisplayName("增肌：在 TDEE 基础上 +15%")
    void gainMuscleAddsCalories() {
        UserProfile profile = baseProfile();
        profile.setGoal("gain_muscle");
        calculator.fill(profile);

        // 2663 × 1.15 = 3062.45 → 3062
        assertEquals(3062, profile.getDailyCalorieTarget());
    }

    @Test
    @DisplayName("均衡：目标热量等于 TDEE，不做调整")
    void balanceEqualsTdee() {
        UserProfile profile = baseProfile();
        profile.setGoal("balance");
        calculator.fill(profile);

        assertEquals(profile.getTdee(), profile.getDailyCalorieTarget());
    }

    @Test
    @DisplayName("控糖：在 TDEE 基础上 -10%")
    void lowSugarReducesCalories() {
        UserProfile profile = baseProfile();
        profile.setGoal("low_sugar");
        calculator.fill(profile);

        // 2663 × 0.9 = 2396.7 → 2397
        assertEquals(2397, profile.getDailyCalorieTarget());
    }

    @Test
    @DisplayName("活动系数按 low/middle/high 生效")
    void activityFactorApplies() {
        UserProfile low = baseProfile();
        low.setActivityLevel("low");
        calculator.fill(low);
        // 1718 × 1.2 = 2061.6 → 2062
        assertEquals(2062, low.getTdee());

        UserProfile high = baseProfile();
        high.setActivityLevel("high");
        calculator.fill(high);
        // 1718 × 1.725 = 2963.55 → 2964
        assertEquals(2964, high.getTdee());
    }

    @Test
    @DisplayName("活动量缺失或非法时按 middle 兜底")
    void activityFallsBackToMiddle() {
        UserProfile missing = baseProfile();
        missing.setActivityLevel(null);
        calculator.fill(missing);
        assertEquals(2663, missing.getTdee());

        UserProfile illegal = baseProfile();
        illegal.setActivityLevel("unknown-level");
        calculator.fill(illegal);
        assertEquals(2663, illegal.getTdee());
    }

    @Test
    @DisplayName("目标缺失或非法时按均衡兜底")
    void goalFallsBackToBalance() {
        UserProfile missing = baseProfile();
        missing.setGoal(null);
        calculator.fill(missing);
        assertEquals(2663, missing.getDailyCalorieTarget());

        UserProfile illegal = baseProfile();
        illegal.setGoal("i-want-to-be-thin");
        calculator.fill(illegal);
        assertEquals(2663, illegal.getDailyCalorieTarget());
    }

    @Test
    @DisplayName("安全下限：算出来低于 1200 千卡时钳制到 1200")
    void clampsToMinimumCalories() {
        UserProfile profile = new UserProfile();
        profile.setHeightCm(new BigDecimal("150"));
        profile.setWeightKg(new BigDecimal("40"));
        profile.setAge(30);
        profile.setGender(1);
        profile.setActivityLevel("low");
        profile.setGoal("loss_fat");
        calculator.fill(profile);

        // BMR = 400 + 937.5 - 150 + 5 = 1192.5 → 1193
        assertEquals(1193, profile.getBmr());
        // TDEE = 1193 × 1.2 = 1431.6 → 1432；目标 = 1432 × 0.8 = 1145.6 → 1146
        // 1146 < 1200，必须被抬回 1200，不能给用户一个极端节食的方案
        assertEquals(1200, profile.getDailyCalorieTarget());
    }

    @Test
    @DisplayName("安全上限：算出来高于 4000 千卡时钳制到 4000")
    void clampsToMaximumCalories() {
        UserProfile profile = new UserProfile();
        profile.setHeightCm(new BigDecimal("200"));
        profile.setWeightKg(new BigDecimal("150"));
        profile.setAge(20);
        profile.setGender(1);
        profile.setActivityLevel("high");
        profile.setGoal("gain_muscle");
        calculator.fill(profile);

        // BMR = 1500 + 1250 - 100 + 5 = 2655
        assertEquals(2655, profile.getBmr());
        // TDEE = 2655 × 1.725 = 4579.875 → 4580
        assertEquals(4580, profile.getTdee());
        // 目标 = 4580 × 1.15 = 5267 → 超过上限，钳制到 4000
        assertEquals(4000, profile.getDailyCalorieTarget());
    }

    @Test
    @DisplayName("关键字段缺失时给中庸值，不抛异常也不算错")
    void missingFieldsUseSafeDefaults() {
        UserProfile profile = new UserProfile();
        profile.setHeightCm(null);
        profile.setWeightKg(new BigDecimal("80"));
        profile.setAge(30);

        calculator.fill(profile);

        assertEquals(0, profile.getBmi());
        assertEquals(0, profile.getBmr());
        assertEquals(0, profile.getTdee());
        assertEquals(1800, profile.getDailyCalorieTarget(), "缺数据时给 1800 而不是 0，避免下游除以 0");
    }

    @Test
    @DisplayName("体重缺失同样走兜底分支")
    void missingWeightUsesSafeDefaults() {
        UserProfile profile = new UserProfile();
        profile.setHeightCm(new BigDecimal("170"));
        profile.setWeightKg(null);
        profile.setAge(30);

        calculator.fill(profile);

        assertEquals(1800, profile.getDailyCalorieTarget());
    }
}
