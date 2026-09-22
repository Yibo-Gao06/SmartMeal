package com.smartmeal.domain.dto.cart;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** 一键加购结果：加了哪些、跳过了哪些、为什么跳。 */
@Data
public class CartBatchAddResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long shoppingListId;

    /** 成功写入（含与已有条目合并）的 SKU 行数。 */
    private Integer addedCount = 0;

    /** 冰箱已够用、无需购买的食材名。 */
    private List<String> coveredByFridge = new ArrayList<>();

    /** 未加购的条目及原因，前端据此提示用户。 */
    private List<SkippedVO> skipped = new ArrayList<>();

    @Data
    public static class SkippedVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String ingredientName;
        private String skuName;
        private String reason;

        public SkippedVO() {
        }

        public SkippedVO(String ingredientName, String skuName, String reason) {
            this.ingredientName = ingredientName;
            this.skuName = skuName;
            this.reason = reason;
        }
    }
}
