package com.smartmeal.domain.enums;

import lombok.Getter;

/** 活动量，系数用于 Mifflin-St Jeor 公式计算 TDEE。 */
@Getter
public enum ActivityLevelEnum {

    LOW("low", "久坐", 1.2),
    MIDDLE("middle", "中等", 1.55),
    HIGH("high", "高强度", 1.725);

    private final String code;
    private final String label;
    private final double factor;

    ActivityLevelEnum(String code, String label, double factor) {
        this.code = code;
        this.label = label;
        this.factor = factor;
    }

    public static ActivityLevelEnum of(String code) {
        for (ActivityLevelEnum value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        return MIDDLE;
    }
}
