package com.smartmeal.domain.enums;

import lombok.Getter;

/** 餐次。 */
@Getter
public enum MealTypeEnum {

    BREAKFAST("breakfast", "早餐"),
    LUNCH("lunch", "午餐"),
    DINNER("dinner", "晚餐"),
    SNACK("snack", "加餐");

    private final String code;
    private final String label;

    MealTypeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static boolean isValid(String code) {
        for (MealTypeEnum value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return true;
            }
        }
        return false;
    }
}
