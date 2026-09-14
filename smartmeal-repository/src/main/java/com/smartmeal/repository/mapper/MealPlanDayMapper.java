package com.smartmeal.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartmeal.domain.entity.MealPlanDay;
import org.apache.ibatis.annotations.Mapper;

/** t_meal_plan_day 计划每日 */
@Mapper
public interface MealPlanDayMapper extends BaseMapper<MealPlanDay> {
}
