package com.smartmeal.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartmeal.domain.entity.AllergenIngredient;
import org.apache.ibatis.annotations.Mapper;

/** t_allergen_ingredient 过敏原→食材映射 */
@Mapper
public interface AllergenIngredientMapper extends BaseMapper<AllergenIngredient> {
}
