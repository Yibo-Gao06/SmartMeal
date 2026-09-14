package com.smartmeal.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartmeal.domain.entity.MealPlanMeal;
import org.apache.ibatis.annotations.Mapper;

/** t_meal_plan_meal 计划每餐 */
@Mapper
public interface MealPlanMealMapper extends BaseMapper<MealPlanMeal> {
}
