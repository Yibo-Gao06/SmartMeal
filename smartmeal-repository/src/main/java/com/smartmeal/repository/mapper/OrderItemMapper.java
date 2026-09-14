package com.smartmeal.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartmeal.domain.entity.OrderItem;
import org.apache.ibatis.annotations.Mapper;

/** t_order_item 订单明细 */
@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {
}
