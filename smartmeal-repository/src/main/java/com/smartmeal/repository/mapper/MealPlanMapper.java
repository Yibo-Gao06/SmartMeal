package com.smartmeal.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartmeal.domain.entity.MealPlan;
import org.apache.ibatis.annotations.Mapper;

/** t_meal_plan AI 膳食计划 */
@Mapper
public interface MealPlanMapper extends BaseMapper<MealPlan> {
}
