package com.smartmeal.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartmeal.domain.entity.UserFridgeIngredient;
import org.apache.ibatis.annotations.Mapper;

/** t_user_fridge_ingredient 用户冰箱库存 */
@Mapper
public interface UserFridgeIngredientMapper extends BaseMapper<UserFridgeIngredient> {
}
