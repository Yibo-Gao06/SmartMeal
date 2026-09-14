package com.smartmeal.domain.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 购物清单一键加购请求。 */
@Data
public class CartBatchAddRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "购物清单 ID 不能为空")
    private Long shoppingListId;

    /** 为空表示全选；非空则只加购指定明细。 */
    private List<Long> selectedItemIds;

    /** 缺货商品是否用替代品补齐。 */
    private Boolean allowSubstitute = Boolean.FALSE;
}
