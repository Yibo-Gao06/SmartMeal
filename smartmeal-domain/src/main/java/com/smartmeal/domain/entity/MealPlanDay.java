package com.smartmeal.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 计划中的「天」t_meal_plan_day。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_meal_plan_day")
public class MealPlanDay extends BaseEntity {

    private Long planId;
    private Integer dayNo;
    private LocalDate planDate;
    private String summary;
    private BigDecimal totalCalories;
    private BigDecimal totalProtein;
    private BigDecimal totalFat;
    private BigDecimal totalCarb;
}
