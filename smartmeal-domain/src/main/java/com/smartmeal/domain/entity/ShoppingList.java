package com.smartmeal.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 购物清单 t_shopping_list。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_shopping_list")
public class ShoppingList extends BaseEntity {

    private Long userId;
    private Long planId;
    private String title;
    private BigDecimal totalPrice;
    /** DRAFT / CONFIRMED / ADDED_TO_CART。 */
    private String status;
}
