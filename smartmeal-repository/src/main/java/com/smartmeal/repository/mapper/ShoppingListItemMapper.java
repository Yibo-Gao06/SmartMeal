package com.smartmeal.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartmeal.domain.entity.ShoppingListItem;
import org.apache.ibatis.annotations.Mapper;

/** t_shopping_list_item 购物清单明细 */
@Mapper
public interface ShoppingListItemMapper extends BaseMapper<ShoppingListItem> {
}
