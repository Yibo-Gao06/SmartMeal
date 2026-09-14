package com.smartmeal.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartmeal.domain.entity.MealPlanMealItem;
import org.apache.ibatis.annotations.Mapper;

/** t_meal_plan_meal_item 计划每餐食材 */
@Mapper
public interface MealPlanMealItemMapper extends BaseMapper<MealPlanMealItem> {
}
