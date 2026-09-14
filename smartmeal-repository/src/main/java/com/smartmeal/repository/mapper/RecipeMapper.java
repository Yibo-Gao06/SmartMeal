package com.smartmeal.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartmeal.domain.entity.Recipe;
import org.apache.ibatis.annotations.Mapper;

/** t_recipe 菜谱 */
@Mapper
public interface RecipeMapper extends BaseMapper<Recipe> {
}
