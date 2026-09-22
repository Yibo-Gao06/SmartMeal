package com.smartmeal.service.cart;

import com.smartmeal.common.exception.BusinessException;
import com.smartmeal.common.result.ResultCode;
import com.smartmeal.domain.dto.CartBatchAddRequest;
import com.smartmeal.domain.dto.cart.CartBatchAddResultVO;
import com.smartmeal.domain.dto.cart.CartVO;
import com.smartmeal.domain.entity.Cart;
import com.smartmeal.domain.entity.ProductSku;
import com.smartmeal.domain.entity.ShoppingList;
import com.smartmeal.domain.entity.ShoppingListItem;
import com.smartmeal.repository.mapper.CartMapper;
import com.smartmeal.repository.mapper.ProductMapper;
import com.smartmeal.repository.mapper.ProductSkuMapper;
import com.smartmeal.repository.mapper.ShoppingListItemMapper;
import com.smartmeal.repository.mapper.ShoppingListMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CartServiceImpl}：一键加购与购物车维护。
 *
 * <p>重点钉住四件容易出错的事：
 * <ol>
 *   <li>清单归属校验——别人的清单必须当成「不存在」，不能泄露 ID 是否存在；</li>
 *   <li>冰箱已够用的条目（quantity=0）算「已覆盖」而不是「跳过」，语义完全不同；</li>
 *   <li>库存不足只跳过单条，不影响其他条目加购；</li>
 *   <li>购物车改数量以 SKU <b>当前</b>库存为准，而不是清单生成时的快照。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class CartServiceImplTest {

    @Mock
    private CartMapper cartMapper;
    @Mock
    private ShoppingListMapper shoppingListMapper;
    @Mock
    private ShoppingListItemMapper shoppingListItemMapper;
    @Mock
    private ProductSkuMapper productSkuMapper;
    @Mock
    private ProductMapper productMapper;

    private CartServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CartServiceImpl(cartMapper, shoppingListMapper, shoppingListItemMapper,
                productSkuMapper, productMapper);
    }

    // ==================== fixtures ====================

    private static final long USER_ID = 1L;
    private static final long LIST_ID = 100L;
    private static final long PLAN_ID = 9L;

    private ShoppingList list() {
        ShoppingList list = new ShoppingList();
        list.setId(LIST_ID);
        list.setUserId(USER_ID);
        list.setPlanId(PLAN_ID);
        list.setStatus("DRAFT");
        return list;
    }

    private ShoppingListItem item(Long id, String ingredient, Long skuId, int qty, String status) {
        ShoppingListItem item = new ShoppingListItem();
        item.setId(id);
        item.setListId(LIST_ID);
        item.setIngredientName(ingredient);
        item.setSkuId(skuId);
        item.setQuantity(qty);
        item.setStatus(status);
        return item;
    }

    private ProductSku sku(Long id, String name, String price, int stock) {
        ProductSku sku = new ProductSku();
        sku.setId(id);
        sku.setProductId(500L);
        sku.setSkuName(name);
        sku.setPrice(new BigDecimal(price));
        sku.setStock(stock);
        sku.setStatus(1);
        return sku;
    }

    private CartBatchAddRequest request(Long... selectedItemIds) {
        CartBatchAddRequest request = new CartBatchAddRequest();
        request.setShoppingListId(LIST_ID);
        if (selectedItemIds.length > 0) {
            request.setSelectedItemIds(List.of(selectedItemIds));
        }
        return request;
    }

    private void stubList(ShoppingList list, List<ShoppingListItem> items) {
        // lenient：归属校验失败时用不到 selectList stub，严格模式下会误报 UnnecessaryStubbing
        lenient().when(shoppingListMapper.selectById(LIST_ID)).thenReturn(list);
        lenient().when(shoppingListItemMapper.selectList(any())).thenReturn(new ArrayList<>(items));
    }

    // ==================== batchAdd ====================

    @Nested
    @DisplayName("一键加购")
    class BatchAdd {

        @Test
        @DisplayName("他人的清单当作不存在，不泄露清单 ID 是否有效")
        void rejectsOtherUsersList() {
            ShoppingList other = list();
            other.setUserId(999L);
            stubList(other, List.of());

            BusinessException e = assertThrows(BusinessException.class,
                    () -> service.batchAdd(USER_ID, request()));
            assertEquals(ResultCode.NOT_FOUND.getCode(), e.getCode());
            verify(cartMapper, never()).upsertQuantity(anyLong(), anyLong(), anyInt(), anyLong());
        }

        @Test
        @DisplayName("清单不存在的 ID 与他人的清单返回同一个错误码")
        void missingListSameCode() {
            lenient().when(shoppingListMapper.selectById(LIST_ID)).thenReturn(null);
            BusinessException e = assertThrows(BusinessException.class,
                    () -> service.batchAdd(USER_ID, request()));
            assertEquals(ResultCode.NOT_FOUND.getCode(), e.getCode());
        }

        @Test
        @DisplayName("正常条目写入购物车，planId 落到条目上用于溯源")
        void addsAvailableItem() {
            stubList(list(), List.of(item(1L, "番茄", 11L, 2, "AVAILABLE")));
            when(productSkuMapper.selectById(11L)).thenReturn(sku(11L, "番茄500g/盒", "6.50", 30));

            CartBatchAddResultVO result = service.batchAdd(USER_ID, request());

            assertEquals(1, result.getAddedCount());
            verify(cartMapper).upsertQuantity(USER_ID, 11L, 2, PLAN_ID);
            assertTrue(result.getSkipped().isEmpty());
        }

        @Test
        @DisplayName("份数为 0（冰箱已够用）算已覆盖，不算跳过")
        void fridgeCoveredIsNotSkipped() {
            stubList(list(), List.of(item(1L, "鸡蛋", null, 0, "AVAILABLE")));

            CartBatchAddResultVO result = service.batchAdd(USER_ID, request());

            assertEquals(List.of("鸡蛋"), result.getCoveredByFridge());
            assertTrue(result.getSkipped().isEmpty());
            assertEquals(0, result.getAddedCount());
            verify(cartMapper, never()).upsertQuantity(anyLong(), anyLong(), anyInt(), anyLong());
        }

        @Test
        @DisplayName("库存不足只跳过该条，其余照常加购")
        void insufficientStockSkipsOnlyThatItem() {
            stubList(list(), List.of(
                    item(1L, "番茄", 11L, 5, "AVAILABLE"),
                    item(2L, "牛肉", 12L, 3, "AVAILABLE")));
            when(productSkuMapper.selectById(11L)).thenReturn(sku(11L, "番茄500g/盒", "6.50", 30));
            when(productSkuMapper.selectById(12L)).thenReturn(sku(12L, "牛肉200g/份", "28.00", 1));

            CartBatchAddResultVO result = service.batchAdd(USER_ID, request());

            assertEquals(1, result.getAddedCount());
            assertEquals(1, result.getSkipped().size());
            assertEquals("牛肉", result.getSkipped().get(0).getIngredientName());
            assertTrue(result.getSkipped().get(0).getReason().contains("库存不足"));
            verify(cartMapper).upsertQuantity(USER_ID, 11L, 5, PLAN_ID);
            verify(cartMapper, never()).upsertQuantity(eq(USER_ID), eq(12L), anyInt(), anyLong());
        }

        @Test
        @DisplayName("下架 SKU 跳过并提示，不会写进购物车")
        void skipsOffShelfSku() {
            stubList(list(), List.of(item(1L, "番茄", 11L, 2, "AVAILABLE")));
            ProductSku off = sku(11L, "番茄500g/盒", "6.50", 30);
            off.setStatus(0);
            when(productSkuMapper.selectById(11L)).thenReturn(off);

            CartBatchAddResultVO result = service.batchAdd(USER_ID, request());

            assertEquals(0, result.getAddedCount());
            assertFalse(result.getSkipped().isEmpty());
        }

        @Test
        @DisplayName("allowSubstitute=false 时缺货条目直接跳过")
        void doesNotUseSubstituteByDefault() {
            ShoppingListItem out = item(1L, "鲈鱼", 21L, 1, "OUT_OF_STOCK");
            out.setSubstituteSkuId(22L);
            stubList(list(), List.of(out));

            CartBatchAddResultVO result = service.batchAdd(USER_ID, request());

            assertEquals(0, result.getAddedCount());
            verify(cartMapper, never()).upsertQuantity(anyLong(), anyLong(), anyInt(), anyLong());
        }

        @Test
        @DisplayName("allowSubstitute=true 时用替代 SKU 补齐")
        void usesSubstituteWhenAllowed() {
            ShoppingListItem out = item(1L, "鲈鱼", 21L, 2, "OUT_OF_STOCK");
            out.setSubstituteSkuId(22L);
            stubList(list(), List.of(out));
            when(productSkuMapper.selectBatchIds(any())).thenReturn(List.of(sku(22L, "冰鲜鲈鱼/条", "32.00", 8)));

            CartBatchAddRequest request = request();
            request.setAllowSubstitute(Boolean.TRUE);
            CartBatchAddResultVO result = service.batchAdd(USER_ID, request);

            assertEquals(1, result.getAddedCount());
            verify(cartMapper).upsertQuantity(USER_ID, 22L, 2, PLAN_ID);
        }

        @Test
        @DisplayName("传 selectedItemIds 时只加购勾选的明细")
        void respectsSelectedItemIds() {
            stubList(list(), List.of(
                    item(1L, "番茄", 11L, 2, "AVAILABLE"),
                    item(2L, "黄瓜", 12L, 1, "AVAILABLE")));
            when(productSkuMapper.selectById(11L)).thenReturn(sku(11L, "番茄", "6.50", 30));

            CartBatchAddResultVO result = service.batchAdd(USER_ID, request(1L));

            assertEquals(1, result.getAddedCount());
            verify(cartMapper).upsertQuantity(USER_ID, 11L, 2, PLAN_ID);
            verify(productSkuMapper, never()).selectById(12L);
        }

        @Test
        @DisplayName("全部跳过时不改清单状态")
        void listStatusUntouchedWhenNothingAdded() {
            stubList(list(), List.of(item(1L, "番茄", 11L, 5, "AVAILABLE")));
            when(productSkuMapper.selectById(11L)).thenReturn(sku(11L, "番茄", "6.50", 0));

            service.batchAdd(USER_ID, request());

            verify(shoppingListMapper, never()).updateById(any(ShoppingList.class));
        }

        @Test
        @DisplayName("有成功条目时清单状态推进为 ADDED_TO_CART")
        void advancesListStatus() {
            stubList(list(), List.of(item(1L, "番茄", 11L, 2, "AVAILABLE")));
            when(productSkuMapper.selectById(11L)).thenReturn(sku(11L, "番茄", "6.50", 30));

            service.batchAdd(USER_ID, request());

            ArgumentCaptor<ShoppingList> captor = ArgumentCaptor.forClass(ShoppingList.class);
            verify(shoppingListMapper).updateById(captor.capture());
            assertEquals("ADDED_TO_CART", captor.getValue().getStatus());
        }
    }

    // ==================== getCart ====================

    @Nested
    @DisplayName("购物车视图")
    class GetCart {

        private Cart row(Long id, Long skuId, int qty, int selected) {
            Cart cart = new Cart();
            cart.setId(id);
            cart.setUserId(USER_ID);
            cart.setSkuId(skuId);
            cart.setQuantity(qty);
            cart.setSelected(selected);
            cart.setPlanId(PLAN_ID);
            return cart;
        }

        @Test
        @DisplayName("空车不查 SKU")
        void emptyCart() {
            when(cartMapper.selectList(any())).thenReturn(List.of());
            CartVO vo = service.getCart(USER_ID);
            assertEquals(0, vo.getItemCount());
            assertEquals(BigDecimal.ZERO, vo.getTotalAmount());
            verify(productSkuMapper, never()).selectBatchIds(any());
        }

        @Test
        @DisplayName("合计只累加勾选且可购条目，取消勾选的不算钱")
        void totalCountsSelectedAvailableOnly() {
            when(cartMapper.selectList(any())).thenReturn(List.of(
                    row(1L, 11L, 2, 1), row(2L, 12L, 3, 0)));
            when(productSkuMapper.selectBatchIds(any()))
                    .thenReturn(List.of(sku(11L, "番茄", "6.50", 30), sku(12L, "牛肉", "28.00", 30)));
            when(productMapper.selectBatchIds(any())).thenReturn(List.of());

            CartVO vo = service.getCart(USER_ID);

            assertEquals(2, vo.getItemCount());
            assertEquals(1, vo.getSelectedCount());
            assertEquals(new BigDecimal("13.00"), vo.getTotalAmount());
        }

        @Test
        @DisplayName("勾选但库存不足：不计入合计，条目上给出原因")
        void selectedButOutOfStockNotInTotal() {
            when(cartMapper.selectList(any())).thenReturn(List.of(row(1L, 11L, 5, 1)));
            when(productSkuMapper.selectBatchIds(any())).thenReturn(List.of(sku(11L, "番茄", "6.50", 2)));
            when(productMapper.selectBatchIds(any())).thenReturn(List.of());

            CartVO vo = service.getCart(USER_ID);

            assertEquals(BigDecimal.ZERO, vo.getTotalAmount());
            assertFalse(vo.getItems().get(0).getAvailable());
            assertTrue(vo.getItems().get(0).getUnavailableReason().contains("库存不足"));
        }

        @Test
        @DisplayName("SKU 已被删除：条目标记不可购而不是整单报错")
        void deletedSkuMarkedUnavailable() {
            when(cartMapper.selectList(any())).thenReturn(List.of(row(1L, 11L, 1, 1)));
            when(productSkuMapper.selectBatchIds(any())).thenReturn(List.of());

            CartVO vo = service.getCart(USER_ID);

            assertFalse(vo.getItems().get(0).getAvailable());
            assertEquals("商品已被删除", vo.getItems().get(0).getUnavailableReason());
        }
    }

    // ==================== 单条操作 ====================

    @Nested
    @DisplayName("数量 / 勾选 / 删除")
    class Mutations {

        private Cart row() {
            Cart cart = new Cart();
            cart.setId(7L);
            cart.setUserId(USER_ID);
            cart.setSkuId(11L);
            cart.setQuantity(2);
            cart.setSelected(1);
            return cart;
        }

        @Test
        @DisplayName("改别人的条目：报「条目不存在」")
        void notOwner() {
            Cart other = row();
            other.setUserId(999L);
            when(cartMapper.selectById(7L)).thenReturn(other);

            BusinessException e = assertThrows(BusinessException.class,
                    () -> service.updateQuantity(USER_ID, 7L, 3));
            assertEquals(ResultCode.CART_ITEM_NOT_FOUND.getCode(), e.getCode());
        }

        @Test
        @DisplayName("数量按当前库存校验，而不是清单快照")
        void validatesAgainstCurrentStock() {
            when(cartMapper.selectById(7L)).thenReturn(row());
            when(productSkuMapper.selectById(11L)).thenReturn(sku(11L, "番茄", "6.50", 3));

            BusinessException e = assertThrows(BusinessException.class,
                    () -> service.updateQuantity(USER_ID, 7L, 4));
            assertEquals(ResultCode.SKU_OUT_OF_STOCK.getCode(), e.getCode());
            verify(cartMapper, never()).updateById(any(Cart.class));
        }

        @Test
        @DisplayName("数量上限 99，超出报参数错误")
        void quantityUpperBound() {
            when(cartMapper.selectById(7L)).thenReturn(row());
            assertThrows(BusinessException.class, () -> service.updateQuantity(USER_ID, 7L, 100));
            assertThrows(BusinessException.class, () -> service.updateQuantity(USER_ID, 7L, 0));
        }

        @Test
        @DisplayName("合法数量落库")
        void validQuantityPersists() {
            when(cartMapper.selectById(7L)).thenReturn(row());
            when(productSkuMapper.selectById(11L)).thenReturn(sku(11L, "番茄", "6.50", 30));

            service.updateQuantity(USER_ID, 7L, 3);

            ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
            verify(cartMapper).updateById(captor.capture());
            assertEquals(3, captor.getValue().getQuantity());
        }

        @Test
        @DisplayName("删除条件里带上 user_id，归属防到最后一刻")
        void deleteScopedByUser() {
            when(cartMapper.selectById(7L)).thenReturn(row());
            when(cartMapper.delete(any())).thenReturn(1);

            service.remove(USER_ID, 7L);

            verify(cartMapper).delete(any());
        }

        @Test
        @DisplayName("删除影响 0 行时报条目不存在")
        void deleteMissingRowThrows() {
            when(cartMapper.selectById(7L)).thenReturn(row());
            when(cartMapper.delete(any())).thenReturn(0);

            BusinessException e = assertThrows(BusinessException.class,
                    () -> service.remove(USER_ID, 7L));
            assertEquals(ResultCode.CART_ITEM_NOT_FOUND.getCode(), e.getCode());
        }

        @Test
        @DisplayName("勾选状态更新不校验库存（未勾选的缺货条目允许存在）")
        void toggleSelectedSkipsStockCheck() {
            when(cartMapper.selectById(7L)).thenReturn(row());

            service.updateSelected(USER_ID, 7L, false);

            verify(cartMapper).updateById(any(Cart.class));
            verify(productSkuMapper, never()).selectById(anyLong());
        }
    }
}
