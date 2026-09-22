package com.smartmeal.domain.dto.cart;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/** 购物车条目展示对象：t_cart 与 SKU/SPU 关联后的完整视图。 */
@Data
public class CartItemVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long cartId;
    private Long skuId;
    private String productName;
    private String skuName;
    private String image;
    private String spec;
    private String unit;

    private BigDecimal price;
    private Integer quantity;
    private Integer stock;
    private Boolean selected;
    private Long planId;

    /** 小计 = price × quantity。 */
    private BigDecimal subtotal;

    /** 下单时还会做最终校验，这里只是提前暴露风险。 */
    private Boolean available;
    private String unavailableReason;
}
