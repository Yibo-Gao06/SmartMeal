package com.smartmeal.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartmeal.domain.entity.Order;
import org.apache.ibatis.annotations.Mapper;

/** t_order 订单 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}
