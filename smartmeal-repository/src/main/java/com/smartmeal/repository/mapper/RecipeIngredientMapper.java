package com.smartmeal.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartmeal.domain.entity.RecipeIngredient;
import org.apache.ibatis.annotations.Mapper;

/** t_recipe_ingredient 菜谱食材关联 */
@Mapper
public interface RecipeIngredientMapper extends BaseMapper<RecipeIngredient> {
}
