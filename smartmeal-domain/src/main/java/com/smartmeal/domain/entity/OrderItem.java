package com.smartmeal.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 订单明细 t_order_item。
 *
 * <p>商品名称、价格、图片全部冗余存储，这是刻意的反范式：
 * 商品改价或改名后，历史订单必须保持下单时的快照。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_order_item")
public class OrderItem extends BaseEntity {

    private Long orderId;
    private String orderNo;
    private Long skuId;
    private String productName;
    private String skuName;
    private String image;
    private BigDecimal price;
    private Integer quantity;
    private BigDecimal amount;
}
