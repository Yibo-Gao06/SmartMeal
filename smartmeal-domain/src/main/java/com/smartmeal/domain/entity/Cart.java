package com.smartmeal.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 购物车 t_cart。
 *
 * <p>{@code planId} 记录该条目来自哪份 AI 膳食计划 —— 这是本项目相对传统外卖
 * 多出来的溯源字段：用户能回答「我为什么买了这些东西」。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_cart")
public class Cart extends BaseEntity {

    private Long userId;
    private Long skuId;
    private Integer quantity;
    private Integer selected;
    private Long planId;
}
