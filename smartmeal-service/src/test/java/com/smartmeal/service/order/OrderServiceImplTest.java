package com.smartmeal.service.order;

import com.smartmeal.common.exception.BusinessException;
import com.smartmeal.common.result.ResultCode;
import com.smartmeal.domain.entity.Cart;
import com.smartmeal.domain.entity.Order;
import com.smartmeal.domain.entity.OrderItem;
import com.smartmeal.domain.entity.ProductSku;
import com.smartmeal.domain.enums.OrderStatusEnum;
import com.smartmeal.repository.mapper.CartMapper;
import com.smartmeal.repository.mapper.OrderItemMapper;
import com.smartmeal.repository.mapper.OrderMapper;
import com.smartmeal.repository.mapper.ProductMapper;
import com.smartmeal.repository.mapper.ProductSkuMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OrderServiceImpl}：下单扣库存与状态机。
 *
 * <p>钉住三条正确性底线：
 * <ol>
 *   <li><b>不超卖</b>：扣减走条件 UPDATE，返回 0 行必须整单失败、不落订单；</li>
 *   <li><b>不打架</b>：支付 / 取消 / 超时三方都靠 {@code WHERE status = 前态} 抢更新，
 *       抢输的一方必须报错或幂等返回，而不是把库存补两次；</li>
 *   <li><b>取消必回补</b>：状态更新成功的那一方才有资格回补库存。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private CartMapper cartMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private ProductSkuMapper productSkuMapper;
    @Mock
    private ProductMapper productMapper;

    private OrderServiceImpl service;

    private static final long USER_ID = 1L;
    private static final String ORDER_NO = "SO202609221200001234";

    @BeforeEach
    void setUp() {
        service = new OrderServiceImpl(cartMapper, orderMapper, orderItemMapper,
                productSkuMapper, productMapper);
        // insert 回填自增主键，否则 orderItem.orderId 拿到 null。
        // lenient：本类多数用例不走下单，严格模式下会误报 UnnecessaryStubbing
        org.mockito.Mockito.lenient().doAnswer(inv -> {
            Order order = inv.getArgument(0);
            if (order.getId() == null) {
                order.setId(42L);
            }
            return 1;
        }).when(orderMapper).insert(any(Order.class));
    }

    // ==================== fixtures ====================

    private Cart cartRow(Long id, Long skuId, int qty, Long planId) {
        Cart cart = new Cart();
        cart.setId(id);
        cart.setUserId(USER_ID);
        cart.setSkuId(skuId);
        cart.setQuantity(qty);
        cart.setSelected(1);
        cart.setPlanId(planId);
        return cart;
    }

    private ProductSku sku(Long id, String name, String price) {
        ProductSku sku = new ProductSku();
        sku.setId(id);
        sku.setSkuName(name);
        sku.setPrice(new BigDecimal(price));
        sku.setStatus(1);
        // productId 留空，跳过 product 快照查询分支
        return sku;
    }

    private Order order(String status) {
        Order order = new Order();
        order.setId(42L);
        order.setOrderNo(ORDER_NO);
        order.setUserId(USER_ID);
        order.setStatus(status);
        order.setCreateTime(LocalDateTime.now().minusMinutes(1));
        return order;
    }

    private OrderItem orderItem(Long skuId, int qty) {
        OrderItem item = new OrderItem();
        item.setOrderId(42L);
        item.setSkuId(skuId);
        item.setQuantity(qty);
        return item;
    }

    // ==================== createOrder ====================

    @Nested
    @DisplayName("下单")
    class Create {

        @Test
        @DisplayName("没有勾选条目直接报购物车为空，不碰库存")
        void emptyCart() {
            when(cartMapper.selectList(any())).thenReturn(List.of());

            BusinessException e = assertThrows(BusinessException.class,
                    () -> service.createOrder(USER_ID, null));
            assertEquals(ResultCode.CART_EMPTY.getCode(), e.getCode());
            verify(productSkuMapper, never()).deductStock(anyLong(), anyInt());
            verify(orderMapper, never()).insert(any(Order.class));
        }

        @Test
        @DisplayName("同一 SKU 跨计划出现多行：归并成一次扣减")
        void mergesSameSkuAcrossRows() {
            when(cartMapper.selectList(any())).thenReturn(List.of(
                    cartRow(1L, 11L, 2, 9L), cartRow(2L, 11L, 3, 10L)));
            when(productSkuMapper.selectBatchIds(any())).thenReturn(List.of(sku(11L, "番茄", "6.50")));
            when(productSkuMapper.deductStock(11L, 5)).thenReturn(1);

            service.createOrder(USER_ID, null);

            verify(productSkuMapper, times(1)).deductStock(11L, 5);
            ArgumentCaptor<OrderItem> captor = ArgumentCaptor.forClass(OrderItem.class);
            verify(orderItemMapper).insert(captor.capture());
            assertEquals(5, captor.getValue().getQuantity());
            assertEquals(new BigDecimal("32.50"), captor.getValue().getAmount());
        }

        @Test
        @DisplayName("库存被抢先扣光：条件更新 0 行，整单不落库")
        void outOfStockAbortsOrder() {
            when(cartMapper.selectList(any())).thenReturn(List.of(cartRow(1L, 11L, 5, null)));
            when(productSkuMapper.selectBatchIds(any())).thenReturn(List.of(sku(11L, "番茄", "6.50")));
            when(productSkuMapper.deductStock(11L, 5)).thenReturn(0);

            BusinessException e = assertThrows(BusinessException.class,
                    () -> service.createOrder(USER_ID, null));
            assertEquals(ResultCode.SKU_OUT_OF_STOCK.getCode(), e.getCode());
            assertTrue(e.getMessage().contains("番茄"));
            verify(orderMapper, never()).insert(any(Order.class));
            verify(cartMapper, never()).deleteByIds(any());
        }

        @Test
        @DisplayName("下架 SKU 拒绝下单")
        void offShelfSkuRejected() {
            when(cartMapper.selectList(any())).thenReturn(List.of(cartRow(1L, 11L, 1, null)));
            ProductSku off = sku(11L, "番茄", "6.50");
            off.setStatus(0);
            when(productSkuMapper.selectBatchIds(any())).thenReturn(List.of(off));

            BusinessException e = assertThrows(BusinessException.class,
                    () -> service.createOrder(USER_ID, null));
            assertEquals(ResultCode.SKU_NOT_FOUND.getCode(), e.getCode());
            verify(productSkuMapper, never()).deductStock(anyLong(), anyInt());
        }

        @Test
        @DisplayName("金额：总价 = Σ(单价×份数)，实付 = 总价 + 运费")
        void amounts() {
            when(cartMapper.selectList(any())).thenReturn(List.of(
                    cartRow(1L, 11L, 2, null), cartRow(2L, 12L, 1, null)));
            when(productSkuMapper.selectBatchIds(any())).thenReturn(List.of(
                    sku(11L, "番茄", "6.50"), sku(12L, "牛肉", "28.00")));
            when(productSkuMapper.deductStock(anyLong(), eq(2))).thenReturn(1);
            when(productSkuMapper.deductStock(anyLong(), eq(1))).thenReturn(1);

            Order order = service.createOrder(USER_ID, null);

            // 13.00 + 28.00 = 41.00 满 39 免运费
            assertEquals(new BigDecimal("41.00"), order.getTotalAmount());
            assertEquals(0, order.getFreightAmount().compareTo(BigDecimal.ZERO));
            assertEquals(new BigDecimal("41.00"), order.getPayAmount());
            assertEquals(OrderStatusEnum.PENDING_PAYMENT.getCode(), order.getStatus());
        }

        @Test
        @DisplayName("来源带 planId：AI_PLAN 用于统计 AI 转化率")
        void sourceTypeAiPlan() {
            when(cartMapper.selectList(any())).thenReturn(List.of(cartRow(1L, 11L, 1, 9L)));
            when(productSkuMapper.selectBatchIds(any())).thenReturn(List.of(sku(11L, "番茄", "6.50")));
            when(productSkuMapper.deductStock(11L, 1)).thenReturn(1);

            Order order = service.createOrder(USER_ID, null);

            assertEquals("AI_PLAN", order.getSourceType());
            assertEquals(9L, order.getPlanId());
        }

        @Test
        @DisplayName("下单成功后已结算的购物车条目被清空")
        void clearsCart() {
            when(cartMapper.selectList(any())).thenReturn(List.of(cartRow(7L, 11L, 1, 9L)));
            when(productSkuMapper.selectBatchIds(any())).thenReturn(List.of(sku(11L, "番茄", "6.50")));
            when(productSkuMapper.deductStock(11L, 1)).thenReturn(1);

            service.createOrder(USER_ID, null);

            verify(cartMapper).deleteByIds(List.of(7L));
        }

        @Test
        @DisplayName("订单号：SO 前缀 + 时间戳 + 随机数，对外不暴露自增主键")
        void orderNoShape() {
            String no = OrderServiceImpl.generateOrderNo();
            assertTrue(no.startsWith("SO"));
            assertEquals(20, no.length());
        }
    }

    // ==================== 运费规则 ====================

    @Nested
    @DisplayName("运费")
    class Freight {

        @Test
        @DisplayName("不满 39 收 5 元，刚好 39 免运费")
        void threshold() {
            assertEquals(new BigDecimal("5.00"), OrderServiceImpl.freightOf(new BigDecimal("38.99")));
            assertEquals(BigDecimal.ZERO, OrderServiceImpl.freightOf(new BigDecimal("39.00")));
            assertEquals(BigDecimal.ZERO, OrderServiceImpl.freightOf(new BigDecimal("120.00")));
        }
    }

    // ==================== pay ====================

    @Nested
    @DisplayName("支付")
    class Pay {

        @Test
        @DisplayName("待支付单支付成功，状态与支付时间落库")
        void success() {
            when(orderMapper.selectOne(any())).thenReturn(order(OrderStatusEnum.PENDING_PAYMENT.getCode()));
            when(orderMapper.update(any(Order.class), any())).thenReturn(1);

            Order paid = service.pay(USER_ID, ORDER_NO, "MOCK");

            assertEquals(OrderStatusEnum.PAID.getCode(), paid.getStatus());
            assertEquals("MOCK", paid.getPayType());
            assertTrue(paid.getPayTime() != null);
        }

        @Test
        @DisplayName("重复支付幂等：不再更新，直接返回原单")
        void idempotent() {
            when(orderMapper.selectOne(any())).thenReturn(order(OrderStatusEnum.PAID.getCode()));

            Order result = service.pay(USER_ID, ORDER_NO, "MOCK");

            assertEquals(OrderStatusEnum.PAID.getCode(), result.getStatus());
            verify(orderMapper, never()).update(any(Order.class), any());
        }

        @Test
        @DisplayName("与超时取消赛跑输了：明确报错，不产生已支付+已取消的双头状态")
        void lostRaceWithTimeoutJob() {
            when(orderMapper.selectOne(any()))
                    .thenReturn(order(OrderStatusEnum.PENDING_PAYMENT.getCode()))
                    .thenReturn(order(OrderStatusEnum.CANCELLED.getCode()));
            when(orderMapper.update(any(Order.class), any())).thenReturn(0);

            BusinessException e = assertThrows(BusinessException.class,
                    () -> service.pay(USER_ID, ORDER_NO, "MOCK"));
            assertEquals(ResultCode.ORDER_STATUS_ILLEGAL.getCode(), e.getCode());
        }

        @Test
        @DisplayName("别人的订单当作不存在")
        void otherUsersOrder() {
            when(orderMapper.selectOne(any())).thenReturn(null);

            BusinessException e = assertThrows(BusinessException.class,
                    () -> service.pay(USER_ID, ORDER_NO, "MOCK"));
            assertEquals(ResultCode.ORDER_NOT_FOUND.getCode(), e.getCode());
        }
    }

    // ==================== cancel ====================

    @Nested
    @DisplayName("取消")
    class Cancel {

        @Test
        @DisplayName("取消成功必须逐明细回补库存")
        void restoresStock() {
            when(orderMapper.selectOne(any())).thenReturn(order(OrderStatusEnum.PENDING_PAYMENT.getCode()));
            when(orderMapper.update(any(Order.class), any())).thenReturn(1);
            when(orderItemMapper.selectList(any())).thenReturn(List.of(orderItem(11L, 2), orderItem(12L, 1)));

            Order cancelled = service.cancel(USER_ID, ORDER_NO);

            assertEquals(OrderStatusEnum.CANCELLED.getCode(), cancelled.getStatus());
            verify(productSkuMapper).restoreStock(11L, 2);
            verify(productSkuMapper).restoreStock(12L, 1);
        }

        @Test
        @DisplayName("已支付订单不能直接取消")
        void paidCannotCancel() {
            when(orderMapper.selectOne(any())).thenReturn(order(OrderStatusEnum.PAID.getCode()));

            BusinessException e = assertThrows(BusinessException.class,
                    () -> service.cancel(USER_ID, ORDER_NO));
            assertEquals(ResultCode.ORDER_STATUS_ILLEGAL.getCode(), e.getCode());
            verify(productSkuMapper, never()).restoreStock(anyLong(), anyInt());
        }

        @Test
        @DisplayName("与支付回调赛跑输了：不重复回补库存")
        void lostRaceWithPay() {
            when(orderMapper.selectOne(any()))
                    .thenReturn(order(OrderStatusEnum.PENDING_PAYMENT.getCode()))
                    .thenReturn(order(OrderStatusEnum.PAID.getCode()));
            when(orderMapper.update(any(Order.class), any())).thenReturn(0);

            BusinessException e = assertThrows(BusinessException.class,
                    () -> service.cancel(USER_ID, ORDER_NO));
            assertEquals(ResultCode.ORDER_STATUS_ILLEGAL.getCode(), e.getCode());
            verify(productSkuMapper, never()).restoreStock(anyLong(), anyInt());
        }

        @Test
        @DisplayName("重复取消幂等：第二次直接返回已取消的单")
        void idempotent() {
            when(orderMapper.selectOne(any()))
                    .thenReturn(order(OrderStatusEnum.PENDING_PAYMENT.getCode()))
                    .thenReturn(order(OrderStatusEnum.CANCELLED.getCode()));
            when(orderMapper.update(any(Order.class), any())).thenReturn(0);

            Order result = service.cancel(USER_ID, ORDER_NO);

            assertEquals(OrderStatusEnum.CANCELLED.getCode(), result.getStatus());
            verify(productSkuMapper, never()).restoreStock(anyLong(), anyInt());
        }
    }

    // ==================== cancelTimeoutOrders ====================

    @Nested
    @DisplayName("超时批量取消")
    class Timeout {

        @Test
        @DisplayName("只有抢到状态更新的那部分订单才回补库存")
        void onlyWinnersRestore() {
            Order a = order(OrderStatusEnum.PENDING_PAYMENT.getCode());
            Order b = order(OrderStatusEnum.PENDING_PAYMENT.getCode());
            b.setId(43L);
            when(orderMapper.selectList(any())).thenReturn(List.of(a, b));
            // a 抢到，b 被并发支付抢走
            when(orderMapper.update(any(Order.class), any())).thenReturn(1, 0);
            when(orderItemMapper.selectList(any())).thenReturn(List.of(orderItem(11L, 3)));

            int cancelled = service.cancelTimeoutOrders(15, 200);

            assertEquals(1, cancelled);
            verify(productSkuMapper, times(1)).restoreStock(11L, 3);
        }

        @Test
        @DisplayName("没有超时订单时零写入")
        void nothingTimeout() {
            when(orderMapper.selectList(any())).thenReturn(List.of());

            assertEquals(0, service.cancelTimeoutOrders(15, 200));
            verify(orderMapper, never()).update(any(Order.class), any());
        }
    }
}
