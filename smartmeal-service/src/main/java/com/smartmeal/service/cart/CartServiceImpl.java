package com.smartmeal.service.cart;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartmeal.common.exception.BusinessException;
import com.smartmeal.common.result.ResultCode;
import com.smartmeal.domain.dto.CartBatchAddRequest;
import com.smartmeal.domain.dto.cart.CartBatchAddResultVO;
import com.smartmeal.domain.dto.cart.CartItemVO;
import com.smartmeal.domain.dto.cart.CartVO;
import com.smartmeal.domain.entity.Cart;
import com.smartmeal.domain.entity.Product;
import com.smartmeal.domain.entity.ProductSku;
import com.smartmeal.domain.entity.ShoppingList;
import com.smartmeal.domain.entity.ShoppingListItem;
import com.smartmeal.repository.mapper.CartMapper;
import com.smartmeal.repository.mapper.ProductMapper;
import com.smartmeal.repository.mapper.ProductSkuMapper;
import com.smartmeal.repository.mapper.ShoppingListItemMapper;
import com.smartmeal.repository.mapper.ShoppingListMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 购物车实现。
 *
 * <p>一个刻意的取舍：<b>加购只软校验库存，不预占</b>。
 * 购物车条目可能停留几天甚至几周，生鲜库存本来就不多，
 * 加购即预占等于把热销菜的库存替犹豫的用户锁死在货架上。
 * 真正的库存约束发生在下单那一刻（{@code OrderService} 的条件扣减），
 * 这里读到多少库存只用于给用户提前暴露风险。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    /** 单条购物车数量上限，防误操作或脚本刷出天量条目。 */
    static final int MAX_QUANTITY_PER_ITEM = 99;

    private final CartMapper cartMapper;
    private final ShoppingListMapper shoppingListMapper;
    private final ShoppingListItemMapper shoppingListItemMapper;
    private final ProductSkuMapper productSkuMapper;
    private final ProductMapper productMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CartBatchAddResultVO batchAdd(Long userId, CartBatchAddRequest request) {
        ShoppingList list = shoppingListMapper.selectById(request.getShoppingListId());
        // 别人的清单和不存在对用户来说是同一件事，不区分，避免清单 ID 被枚举
        BusinessException.throwIf(list == null || !userId.equals(list.getUserId()),
                ResultCode.NOT_FOUND, "购物清单不存在");

        List<ShoppingListItem> items = shoppingListItemMapper.selectList(
                Wrappers.<ShoppingListItem>lambdaQuery()
                        .eq(ShoppingListItem::getListId, list.getId()));
        if (request.getSelectedItemIds() != null && !request.getSelectedItemIds().isEmpty()) {
            items = items.stream().filter(i -> request.getSelectedItemIds().contains(i.getId())).toList();
        }

        CartBatchAddResultVO result = new CartBatchAddResultVO();
        result.setShoppingListId(list.getId());

        // 替代品走一次批量查，避免循环里逐条打库
        Map<Long, ProductSku> substituteById = loadSubstitutes(items, request);

        for (ShoppingListItem item : items) {
            int qty = item.getQuantity() == null ? 0 : item.getQuantity();
            if (qty <= 0) {
                // 冰箱已够用，本来就不用买，不算失败
                result.getCoveredByFridge().add(item.getIngredientName());
                continue;
            }

            Long skuId = item.getSkuId();
            ProductSku sku = null;
            if (isPurchasable(item)) {
                sku = productSkuMapper.selectById(skuId);
            } else if (Boolean.TRUE.equals(request.getAllowSubstitute())
                    && item.getSubstituteSkuId() != null) {
                sku = substituteById.get(item.getSubstituteSkuId());
            }
            // 清单生成后商品可能已下架，主路径和替代路径都要按当前状态复检
            if (sku != null && !isSellable(sku)) {
                sku = null;
            }

            if (sku == null) {
                result.getSkipped().add(new CartBatchAddResultVO.SkippedVO(
                        item.getIngredientName(), item.getSkuName(),
                        "无有库存的可售商品" + (Boolean.TRUE.equals(request.getAllowSubstitute())
                                ? "（含替代品）" : "，可开启替代品补齐")));
                continue;
            }

            if (qty > (sku.getStock() == null ? 0 : sku.getStock())) {
                result.getSkipped().add(new CartBatchAddResultVO.SkippedVO(
                        item.getIngredientName(), sku.getSkuName(),
                        "库存不足：需 " + qty + " 份，仅剩 " + (sku.getStock() == null ? 0 : sku.getStock()) + " 份"));
                continue;
            }

            cartMapper.upsertQuantity(userId, sku.getId(), qty, list.getPlanId());
            result.setAddedCount(result.getAddedCount() + 1);
        }

        if (result.getAddedCount() > 0) {
            ShoppingList update = new ShoppingList();
            update.setId(list.getId());
            update.setStatus("ADDED_TO_CART");
            shoppingListMapper.updateById(update);
        }
        log.info("一键加购完成 userId={} listId={} 加购={} 跳过={} 冰箱已覆盖={}",
                userId, list.getId(), result.getAddedCount(),
                result.getSkipped().size(), result.getCoveredByFridge().size());
        return result;
    }

    @Override
    public CartVO getCart(Long userId) {
        List<Cart> rows = cartMapper.selectList(Wrappers.<Cart>lambdaQuery()
                .eq(Cart::getUserId, userId)
                .orderByDesc(Cart::getId));

        CartVO vo = new CartVO();
        if (rows.isEmpty()) {
            return vo;
        }
        Map<Long, ProductSku> skuById = mapById(
                productSkuMapper.selectBatchIds(rows.stream().map(Cart::getSkuId).distinct().toList()),
                ProductSku::getId);
        List<Long> productIds = skuById.values().stream()
                .map(ProductSku::getProductId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Product> productById = productIds.isEmpty() ? Map.of()
                : mapById(productMapper.selectBatchIds(productIds), Product::getId);

        BigDecimal total = BigDecimal.ZERO;
        int selectedCount = 0;
        for (Cart row : rows) {
            CartItemVO item = toItemVO(row, skuById.get(row.getSkuId()), productById);
            vo.getItems().add(item);
            if (row.getSelected() != null && row.getSelected() == 1) {
                selectedCount++;
                if (Boolean.TRUE.equals(item.getAvailable())) {
                    total = total.add(item.getSubtotal());
                }
            }
        }
        vo.setItemCount(rows.size());
        vo.setSelectedCount(selectedCount);
        vo.setTotalAmount(total);
        return vo;
    }

    @Override
    public void updateQuantity(Long userId, Long cartId, int quantity) {
        Cart row = requireOwnedRow(userId, cartId);
        BusinessException.throwIf(quantity < 1 || quantity > MAX_QUANTITY_PER_ITEM,
                ResultCode.BAD_REQUEST, "数量必须在 1~" + MAX_QUANTITY_PER_ITEM + " 之间");

        ProductSku sku = productSkuMapper.selectById(row.getSkuId());
        BusinessException.throwIf(!isSellable(sku), ResultCode.SKU_NOT_FOUND);
        int stock = sku.getStock() == null ? 0 : sku.getStock();
        BusinessException.throwIf(quantity > stock, ResultCode.SKU_OUT_OF_STOCK,
                "库存仅剩 " + stock + " 份");

        Cart update = new Cart();
        update.setId(cartId);
        update.setQuantity(quantity);
        cartMapper.updateById(update);
    }

    @Override
    public void updateSelected(Long userId, Long cartId, boolean selected) {
        requireOwnedRow(userId, cartId);
        Cart update = new Cart();
        update.setId(cartId);
        update.setSelected(selected ? 1 : 0);
        cartMapper.updateById(update);
    }

    @Override
    public void remove(Long userId, Long cartId) {
        requireOwnedRow(userId, cartId);
        // delete 条件里再次带上 user_id：查改删之间隔着一次请求往返，归属要防到最后一刻
        int rows = cartMapper.delete(Wrappers.<Cart>lambdaQuery()
                .eq(Cart::getId, cartId)
                .eq(Cart::getUserId, userId));
        BusinessException.throwIf(rows == 0, ResultCode.CART_ITEM_NOT_FOUND);
    }

    // ==================== 内部实现 ====================

    private Map<Long, ProductSku> loadSubstitutes(List<ShoppingListItem> items,
                                                  CartBatchAddRequest request) {
        if (!Boolean.TRUE.equals(request.getAllowSubstitute())) {
            return Map.of();
        }
        List<Long> ids = items.stream()
                .filter(i -> !isPurchasable(i) && i.getSubstituteSkuId() != null)
                .map(ShoppingListItem::getSubstituteSkuId)
                .distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return mapById(productSkuMapper.selectBatchIds(ids), ProductSku::getId);
    }

    /** 清单条目本身是否可直接购买（匹配到 SKU 且生成时有货）。 */
    private boolean isPurchasable(ShoppingListItem item) {
        return "AVAILABLE".equals(item.getStatus()) && item.getSkuId() != null;
    }

    /** SKU 当前是否可售（存在、未下架）。 */
    private boolean isSellable(ProductSku sku) {
        return sku != null && sku.getStatus() != null && sku.getStatus() == 1;
    }

    private Cart requireOwnedRow(Long userId, Long cartId) {
        Cart row = cartMapper.selectById(cartId);
        BusinessException.throwIf(row == null || !userId.equals(row.getUserId()),
                ResultCode.CART_ITEM_NOT_FOUND);
        return row;
    }

    private CartItemVO toItemVO(Cart row, ProductSku sku, Map<Long, Product> productById) {
        CartItemVO item = new CartItemVO();
        item.setCartId(row.getId());
        item.setSkuId(row.getSkuId());
        item.setQuantity(row.getQuantity());
        item.setSelected(row.getSelected() != null && row.getSelected() == 1);
        item.setPlanId(row.getPlanId());

        if (sku == null) {
            item.setAvailable(false);
            item.setUnavailableReason("商品已被删除");
            item.setSubtotal(BigDecimal.ZERO);
            item.setPrice(BigDecimal.ZERO);
            return item;
        }
        item.setSkuName(sku.getSkuName());
        item.setSpec(sku.getSpec());
        item.setUnit(sku.getUnit());
        item.setPrice(sku.getPrice());
        item.setStock(sku.getStock());
        Product product = productById.get(sku.getProductId());
        if (product != null) {
            item.setProductName(product.getName());
            item.setImage(product.getImage());
        }
        BigDecimal price = sku.getPrice() == null ? BigDecimal.ZERO : sku.getPrice();
        item.setSubtotal(price.multiply(BigDecimal.valueOf(row.getQuantity() == null ? 0 : row.getQuantity())));

        if (!isSellable(sku)) {
            item.setAvailable(false);
            item.setUnavailableReason("商品已下架");
        } else if (row.getQuantity() != null && row.getQuantity() > (sku.getStock() == null ? 0 : sku.getStock())) {
            item.setAvailable(false);
            item.setUnavailableReason("库存不足，当前仅剩 " + (sku.getStock() == null ? 0 : sku.getStock()) + " 份");
        } else {
            item.setAvailable(true);
        }
        return item;
    }

    private <T> Map<Long, T> mapById(List<T> list, Function<T, Long> idGetter) {
        return list.stream().collect(Collectors.toMap(idGetter, Function.identity(),
                (a, b) -> a, LinkedHashMap::new));
    }
}
