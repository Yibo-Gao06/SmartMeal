package com.smartmeal.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartmeal.domain.entity.ShoppingList;
import org.apache.ibatis.annotations.Mapper;

/** t_shopping_list 购物清单 */
@Mapper
public interface ShoppingListMapper extends BaseMapper<ShoppingList> {
}
