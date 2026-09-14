package com.smartmeal.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 计划中的「餐」t_meal_plan_meal。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_meal_plan_meal")
public class MealPlanMeal extends BaseEntity {

    private Long planDayId;
    private String mealType;
    private Long recipeId;
    private String recipeName;
    private Integer servings;

    private BigDecimal calories;
    private BigDecimal protein;
    private BigDecimal fat;
    private BigDecimal carb;

    /** 模型给出的推荐理由，前端展示「为什么今天吃这个」。 */
    private String reason;
    /** RAG 引用来源 ID，如 RECIPE_5001,INGREDIENT_1001，用于溯源与降低幻觉。 */
    private String sourceIds;
}
