package com.smartmeal.service.cart;

import com.smartmeal.domain.dto.CartBatchAddRequest;
import com.smartmeal.domain.dto.cart.CartBatchAddResultVO;
import com.smartmeal.domain.dto.cart.CartVO;

/** 购物车服务。 */
public interface CartService {

    /** 购物清单一键加购：过滤可购条目、按 SKU 合并写入 t_cart。 */
    CartBatchAddResultVO batchAdd(Long userId, CartBatchAddRequest request);

    /** 当前用户购物车（含 SKU 快照与勾选合计）。 */
    CartVO getCart(Long userId);

    /** 修改某条目数量（校验归属与库存）。 */
    void updateQuantity(Long userId, Long cartId, int quantity);

    /** 勾选 / 取消勾选某条目。 */
    void updateSelected(Long userId, Long cartId, boolean selected);

    /** 删除某条目。 */
    void remove(Long userId, Long cartId);
}
