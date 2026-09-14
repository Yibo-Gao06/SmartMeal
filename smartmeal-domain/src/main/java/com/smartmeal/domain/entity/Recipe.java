package com.smartmeal.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 菜谱表 t_recipe。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_recipe")
public class Recipe extends BaseEntity {

    private String name;
    private String coverImage;
    private String description;
    private String cuisineType;
    /** breakfast / lunch / dinner / snack。 */
    private String mealType;
    /** 烹饪时间（分钟），用于过滤「工作日只能做快手菜」。 */
    private Integer cookTime;
    private Integer difficulty;

    private BigDecimal calories;
    private BigDecimal protein;
    private BigDecimal fat;
    private BigDecimal carb;

    private String tags;
    /** 该菜谱含有的过敏原编码，JSON 数组字符串。 */
    private String allergenTags;
    private Integer status;
}
