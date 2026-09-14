package com.smartmeal.domain.dto.shopping;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 购物清单展示对象。
 *
 * <p>为什么不直接把 {@code ShoppingListItem} 实体列表吐给前端：
 * <ol>
 *   <li>实体的 {@code listId} / {@code substituteSkuId} / {@code createTime} 前端用不上；</li>
 *   <li>最关键的 {@code subtotal}（小计）在库里没有存 —— 它是 {@code quantity × price}。
 *       与其让前端再算一遍，不如服务端算好，避免两边四舍五入规则不一致
 *       导致「明细加起来和总价对不上」这种很难查的问题。</li>
 * </ol>
 */
@Data
public class ShoppingListVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long listId;
    private Long planId;
    private String title;
    /** 清单总价，由服务端在生成时算好并落库。 */
    private BigDecimal totalPrice;
    private String status;

    /** 全清单小计之和，用于和 totalPrice 对账；不一致说明有明细漏算。 */
    private BigDecimal itemsTotal;

    private List<ItemVO> items = new ArrayList<>();

    /** 一条明细。 */
    @Data
    public static class ItemVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long ingredientId;
        private String ingredientName;
        /** 食谱一共需要多少。 */
        private BigDecimal requiredAmount;
        private String unit;
        /** 冰箱里已有多少，已从需求里扣掉。 */
        private BigDecimal fridgeAmount;
        /** 实际需要买多少。 */
        private BigDecimal needBuyAmount;
        private Long skuId;
        private String skuName;
        /** 买几份（整包向上取整后的结果）。 */
        private Integer quantity;
        /** 单价。 */
        private BigDecimal price;
        /** 小计 = quantity × price。 */
        private BigDecimal subtotal;
        private String status;
    }
}
