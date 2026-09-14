package com.smartmeal.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartmeal.domain.entity.Ingredient;
import org.apache.ibatis.annotations.Mapper;

/** t_ingredient 食材 */
@Mapper
public interface IngredientMapper extends BaseMapper<Ingredient> {
}
