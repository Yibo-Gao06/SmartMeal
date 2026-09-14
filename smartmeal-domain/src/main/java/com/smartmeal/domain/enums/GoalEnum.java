package com.smartmeal.domain.enums;

import lombok.Getter;

/**
 * 健康目标。
 *
 * <p>{@code calorieAdjustRatio} 是相对 TDEE 的调整系数，用于计算每日目标热量：
 * 减脂 -20%、增肌 +15%、均衡 0%、控糖 -10%。
 */
@Getter
public enum GoalEnum {

    LOSS_FAT("loss_fat", "减脂", -0.20),
    GAIN_MUSCLE("gain_muscle", "增肌", 0.15),
    BALANCE("balance", "均衡", 0.0),
    LOW_SUGAR("low_sugar", "控糖", -0.10);

    private final String code;
    private final String label;
    private final double calorieAdjustRatio;

    GoalEnum(String code, String label, double calorieAdjustRatio) {
        this.code = code;
        this.label = label;
        this.calorieAdjustRatio = calorieAdjustRatio;
    }

    public static GoalEnum of(String code) {
        for (GoalEnum value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        return BALANCE;
    }
}
