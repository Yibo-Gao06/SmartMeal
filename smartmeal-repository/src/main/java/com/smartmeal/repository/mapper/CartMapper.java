package com.smartmeal.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartmeal.domain.entity.Cart;
import org.apache.ibatis.annotations.Mapper;

/** t_cart 购物车 */
@Mapper
public interface CartMapper extends BaseMapper<Cart> {
}
