package com.smartmeal.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 每餐的食材明细 t_meal_plan_meal_item。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_meal_plan_meal_item")
public class MealPlanMealItem extends BaseEntity {

    private Long mealId;
    private Long ingredientId;
    private String ingredientName;
    private BigDecimal amount;
    private String unit;
    private Long substituteIngredientId;
    private Long skuId;
}
