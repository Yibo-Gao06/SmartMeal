package com.smartmeal.domain.dto.cart;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 购物车整体视图：条目 + 汇总。 */
@Data
public class CartVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private List<CartItemVO> items = new ArrayList<>();

    private Integer itemCount = 0;

    /** 勾选件数（按 SKU 行数计，非份数）。 */
    private Integer selectedCount = 0;

    /** 勾选且可购买条目的金额合计，下单前的预估。 */
    private BigDecimal totalAmount = BigDecimal.ZERO;
}
