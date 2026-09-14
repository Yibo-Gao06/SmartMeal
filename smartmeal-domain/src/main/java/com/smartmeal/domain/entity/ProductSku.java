package com.smartmeal.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 商品 SKU 表 t_product_sku。
 *
 * <p>核心字段是 {@code ingredientId} 和 {@code conversionRate}：
 * 前者建立「菜谱食材 → 可购买商品」的桥梁，
 * 后者解决单位换算，例如 1 份 = 500g。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_product_sku")
public class ProductSku extends BaseEntity {

    private Long productId;
    private String skuName;
    /** 关联食材，购物清单 SKU 匹配的第一优先级依据。 */
    private Long ingredientId;

    private BigDecimal price;
    private Integer stock;
    private String unit;
    /** 规格描述，如「500g/盒」。 */
    private String spec;
    /** 1 个销售单位 = 多少基准单位（g/ml/piece）。 */
    private BigDecimal conversionRate;
    private Integer status;
}
