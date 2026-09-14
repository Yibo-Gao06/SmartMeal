package com.smartmeal.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 食材表 t_ingredient，营养数据以每 100g 为基准。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ingredient")
public class Ingredient extends BaseEntity {

    private String name;
    /** 别名，用于「西红柿 / 番茄」这类同义词归一。 */
    private String alias;
    private Long categoryId;

    /** 基准单位：g / ml / piece。 */
    private String unit;

    /*
     * 下面四个字段必须显式指定列名。
     *
     * MyBatis-Plus 的默认驼峰转下划线是「遇大写字母前插下划线」，不会在数字前插：
     *     caloriesPer100g  ->  calories_per100g
     * 而建表语句里写的是 calories_per_100g，两边差一个下划线，
     * 运行时会报 Unknown column 'calories_per100g' in 'field list'。
     *
     * 这里选择保留更好读的列名 calories_per_100g，用 @TableField 显式映射，
     * 而不是把库里的列名改成 calories_per100g。
     */
    @TableField("calories_per_100g")
    private BigDecimal caloriesPer100g;
    @TableField("protein_per_100g")
    private BigDecimal proteinPer100g;
    @TableField("fat_per_100g")
    private BigDecimal fatPer100g;
    @TableField("carb_per_100g")
    private BigDecimal carbPer100g;

    /** 该食材自身含有的过敏原编码，JSON 数组字符串，如 ["PEANUT"]。 */
    private String allergenTags;
    /** 适合的目标，JSON 数组字符串，如 ["loss_fat","balance"]。 */
    private String suitableGoals;
    private Integer status;
}
