package com.smartmeal.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 购物清单明细 t_shopping_list_item。
 *
 * <p>三个数量字段是这条链路的精华：
 * {@code requiredAmount}（菜谱需要）→ 减去 {@code fridgeAmount}（冰箱已有）
 * → 得到 {@code needBuyAmount}（真正要买的）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_shopping_list_item")
public class ShoppingListItem extends BaseEntity {

    private Long listId;
    private Long ingredientId;
    private String ingredientName;

    private BigDecimal requiredAmount;
    private String unit;
    private BigDecimal fridgeAmount;
    private BigDecimal needBuyAmount;

    private Long skuId;
    private String skuName;
    private Integer quantity;
    private BigDecimal price;
    private Long substituteSkuId;

    /** AVAILABLE / OUT_OF_STOCK / SUBSTITUTED。 */
    private String status;
}
