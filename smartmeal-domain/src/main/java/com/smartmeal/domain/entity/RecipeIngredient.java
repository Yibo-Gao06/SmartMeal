package com.smartmeal.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 菜谱-食材关联 t_recipe_ingredient。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_recipe_ingredient")
public class RecipeIngredient extends BaseEntity {

    private Long recipeId;
    private Long ingredientId;
    private BigDecimal amount;
    private String unit;
    /** 1 表示可选配料（如香菜、葱花），生成购物清单时可忽略。 */
    private Integer optional;
    private Long substituteIngredientId;
}
