package com.smartmeal.service.order;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartmeal.common.exception.BusinessException;
import com.smartmeal.common.result.PageResult;
import com.smartmeal.common.result.ResultCode;
import com.smartmeal.domain.dto.order.OrderCreateRequest;
import com.smartmeal.domain.dto.order.OrderDetailVO;
import com.smartmeal.domain.entity.Cart;
import com.smartmeal.domain.entity.Order;
import com.smartmeal.domain.entity.OrderItem;
import com.smartmeal.domain.entity.Product;
import com.smartmeal.domain.entity.ProductSku;
import com.smartmeal.domain.enums.OrderStatusEnum;
import com.smartmeal.repository.mapper.CartMapper;
import com.smartmeal.repository.mapper.OrderItemMapper;
import com.smartmeal.repository.mapper.OrderMapper;
import com.smartmeal.repository.mapper.ProductMapper;
import com.smartmeal.repository.mapper.ProductSkuMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 下单与订单状态机。
 *
 * <p>两个正确性要点，都有对应的条件更新：
 * <ol>
 *   <li><b>防超卖</b>：库存扣减是 {@code stock >= n} 写进 WHERE 的单条 UPDATE，
 *       受影响行数为 0 即库存被抢先扣光，抛异常回滚整单；</li>
 *   <li><b>状态打架</b>：支付与超时取消都靠 {@code WHERE status = 前态} 抢更新，
 *       数据库保证同一订单只会有一方成功，不存在「已取消的单被支付」。</li>
 * </ol>
 *
 * <p>扣减失败时逐个 SKU 顺序加锁，顺序按 skuId 升序 —— 并发下单时所有事务
 * 以相同顺序申请行锁，避免互相持有对方需要的锁造成死锁。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private static final DateTimeFormatter ORDER_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 满 39 免运费，否则按 5 元计。生鲜配送有真实成本，运费在下单时定死。 */
    static final BigDecimal FREE_FREIGHT_THRESHOLD = new BigDecimal("39.00");
    static final BigDecimal BASE_FREIGHT = new BigDecimal("5.00");

    private final CartMapper cartMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductSkuMapper productSkuMapper;
    private final ProductMapper productMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Order createOrder(Long userId, OrderCreateRequest request) {
        List<Cart> rows = cartMapper.selectList(Wrappers.<Cart>lambdaQuery()
                .eq(Cart::getUserId, userId)
                .eq(Cart::getSelected, 1));
        BusinessException.throwIf(rows.isEmpty(), ResultCode.CART_EMPTY);

        // 同一 SKU 可能出现在多条计划来源的购物车行里，结算前归并
        Map<Long, Integer> qtyBySku = new TreeMap<>();
        Map<Long, Long> planBySku = new LinkedHashMap<>();
        for (Cart row : rows) {
            int qty = row.getQuantity() == null ? 0 : row.getQuantity();
            BusinessException.throwIf(qty <= 0, ResultCode.BAD_REQUEST, "购物车存在非法数量条目");
            qtyBySku.merge(row.getSkuId(), qty, Integer::sum);
            if (row.getPlanId() != null && row.getPlanId() > 0) {
                planBySku.putIfAbsent(row.getSkuId(), row.getPlanId());
            }
        }

        Map<Long, ProductSku> skuById = productSkuMapper
                .selectBatchIds(new ArrayList<>(qtyBySku.keySet())).stream()
                .collect(Collectors.toMap(ProductSku::getId, Function.identity()));

        List<OrderItem> items = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        Long planId = planBySku.values().stream().findFirst().orElse(null);

        for (Map.Entry<Long, Integer> entry : qtyBySku.entrySet()) {
            Long skuId = entry.getKey();
            int qty = entry.getValue();
            ProductSku sku = skuById.get(skuId);
            BusinessException.throwIf(sku == null || sku.getStatus() == null || sku.getStatus() != 1,
                    ResultCode.SKU_NOT_FOUND, "商品「" + (sku == null ? skuId : sku.getSkuName()) + "」已下架");

            // 条件扣减：0 行 = 被抢光。skuId 升序保证所有事务按同一顺序拿行锁
            int affected = productSkuMapper.deductStock(skuId, qty);
            if (affected == 0) {
                throw new BusinessException(ResultCode.SKU_OUT_OF_STOCK,
                        "「" + sku.getSkuName() + "」库存不足，请调整数量后重新下单");
            }

            BigDecimal price = sku.getPrice() == null ? BigDecimal.ZERO : sku.getPrice();
            BigDecimal amount = scale(price.multiply(BigDecimal.valueOf(qty)));
            totalAmount = totalAmount.add(amount);

            OrderItem item = new OrderItem();
            item.setSkuId(skuId);
            item.setSkuName(sku.getSkuName());
            item.setPrice(price);
            item.setQuantity(qty);
            item.setAmount(amount);
            Product product = sku.getProductId() == null ? null : productMapper.selectById(sku.getProductId());
            if (product != null) {
                item.setProductName(product.getName());
                item.setImage(product.getImage());
            }
            items.add(item);
        }

        Order order = new Order();
        order.setOrderNo(generateOrderNo());
        order.setUserId(userId);
        order.setPlanId(planId);
        order.setSourceType(planId != null ? "AI_PLAN" : "NORMAL");
        order.setTotalAmount(scale(totalAmount));
        order.setFreightAmount(freightOf(order.getTotalAmount()));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setPayAmount(scale(order.getTotalAmount().add(order.getFreightAmount())));
        order.setStatus(OrderStatusEnum.PENDING_PAYMENT.getCode());
        order.setRemark(request == null ? null : request.getRemark());
        orderMapper.insert(order);

        for (OrderItem item : items) {
            item.setOrderId(order.getId());
            item.setOrderNo(order.getOrderNo());
            orderItemMapper.insert(item);
        }

        cartMapper.deleteByIds(rows.stream().map(Cart::getId).toList());
        log.info("下单成功 orderNo={} userId={} 明细={} 应付={}",
                order.getOrderNo(), userId, items.size(), order.getPayAmount());
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Order pay(Long userId, String orderNo, String payType) {
        Order order = requireOwnedOrder(userId, orderNo);
        if (OrderStatusEnum.PAID.getCode().equals(order.getStatus())) {
            // 幂等：重复支付回调不再改状态，直接回单
            return order;
        }
        assertStatus(order, OrderStatusEnum.PENDING_PAYMENT);

        Order update = new Order();
        update.setStatus(OrderStatusEnum.PAID.getCode());
        update.setPayType(payType == null || payType.isBlank() ? "MOCK" : payType);
        update.setPayTime(LocalDateTime.now());
        int rows = orderMapper.update(update, conditionByNoAndStatus(order.getId(),
                OrderStatusEnum.PENDING_PAYMENT));
        if (rows == 0) {
            Order latest = requireOwnedOrder(userId, orderNo);
            if (OrderStatusEnum.PAID.getCode().equals(latest.getStatus())) {
                return latest;
            }
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL,
                    "订单已被取消，无法支付（超时未支付库存已释放）");
        }
        order.setStatus(update.getStatus());
        order.setPayType(update.getPayType());
        order.setPayTime(update.getPayTime());
        log.info("订单支付成功 orderNo={} payType={}", orderNo, order.getPayType());
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Order cancel(Long userId, String orderNo) {
        Order order = requireOwnedOrder(userId, orderNo);
        assertStatus(order, OrderStatusEnum.PENDING_PAYMENT);
        int rows = cancelAndUpdate(order, "用户主动取消");
        if (rows == 0) {
            Order latest = requireOwnedOrder(userId, orderNo);
            if (OrderStatusEnum.CANCELLED.getCode().equals(latest.getStatus())) {
                return latest;
            }
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL,
                    "订单状态已变化（可能已支付），取消失败");
        }
        return order;
    }

    @Override
    public OrderDetailVO detail(Long userId, String orderNo) {
        Order order = requireOwnedOrder(userId, orderNo);
        OrderDetailVO vo = new OrderDetailVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setStatus(order.getStatus());
        vo.setSourceType(order.getSourceType());
        vo.setPlanId(order.getPlanId());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setFreightAmount(order.getFreightAmount());
        vo.setDiscountAmount(order.getDiscountAmount());
        vo.setPayAmount(order.getPayAmount());
        vo.setPayType(order.getPayType());
        vo.setPayTime(order.getPayTime());
        vo.setCancelTime(order.getCancelTime());
        vo.setCreateTime(order.getCreateTime());
        vo.setRemark(order.getRemark());
        vo.setItems(orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery()
                .eq(OrderItem::getOrderId, order.getId())
                .orderByAsc(OrderItem::getId)));
        return vo;
    }

    @Override
    public PageResult<Order> page(Long userId, OrderStatusEnum status, long pageNum, long pageSize) {
        IPage<Order> page = orderMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<Order>lambdaQuery()
                        .eq(Order::getUserId, userId)
                        .eq(status != null, Order::getStatus, status == null ? null : status.getCode())
                        .orderByDesc(Order::getId));
        return new PageResult<>(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cancelTimeoutOrders(int timeoutMinutes, int batchSize) {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(timeoutMinutes);
        List<Order> timeoutOrders = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getStatus, OrderStatusEnum.PENDING_PAYMENT.getCode())
                .lt(Order::getCreateTime, deadline)
                .last("limit " + batchSize));

        int cancelled = 0;
        for (Order order : timeoutOrders) {
            // 条件更新成功的那一方才回补库存：与支付回调并发时另一边拿到 0 行，不会重复补
            cancelled += cancelAndUpdate(order, "超时未支付，系统自动取消");
        }
        if (!timeoutOrders.isEmpty()) {
            log.info("超时订单处理：扫描 {} 单，实际取消 {} 单", timeoutOrders.size(), cancelled);
        }
        return cancelled;
    }

    // ==================== 内部实现 ====================

    /** 条件更新为已取消，成功则回补库存。返回受影响行数（0 表示状态被并发的另一方抢先改变）。 */
    private int cancelAndUpdate(Order order, String remark) {
        Order update = new Order();
        update.setStatus(OrderStatusEnum.CANCELLED.getCode());
        update.setCancelTime(LocalDateTime.now());
        update.setRemark(remark);
        int rows = orderMapper.update(update, conditionByNoAndStatus(order.getId(),
                OrderStatusEnum.PENDING_PAYMENT));
        if (rows > 0) {
            restoreStock(order.getId());
            order.setStatus(update.getStatus());
            order.setCancelTime(update.getCancelTime());
            order.setRemark(remark);
        }
        return rows;
    }

    private void restoreStock(Long orderId) {
        List<OrderItem> items = orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery()
                .eq(OrderItem::getOrderId, orderId));
        for (OrderItem item : items) {
            if (item.getSkuId() != null && item.getQuantity() != null && item.getQuantity() > 0) {
                productSkuMapper.restoreStock(item.getSkuId(), item.getQuantity());
            }
        }
    }

    /** 状态更新的兜底条件：WHERE id = ? AND status = 期望前态。 */
    private LambdaUpdateWrapper<Order> conditionByNoAndStatus(
            Long orderId, OrderStatusEnum expected) {
        return Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, expected.getCode());
    }

    private Order requireOwnedOrder(Long userId, String orderNo) {
        Order order = orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
                .eq(Order::getOrderNo, orderNo)
                .eq(Order::getUserId, userId));
        // 不区分「不存在」和「不是你的」，避免订单号被枚举
        BusinessException.throwIf(order == null, ResultCode.ORDER_NOT_FOUND);
        return order;
    }

    private void assertStatus(Order order, OrderStatusEnum expected) {
        BusinessException.throwIf(!expected.getCode().equals(order.getStatus()),
                ResultCode.ORDER_STATUS_ILLEGAL,
                "当前状态「" + order.getStatus() + "」不允许该操作");
    }

    /** 运费规则：满额包邮，不满收基础配送费。 */
    static BigDecimal freightOf(BigDecimal totalAmount) {
        return totalAmount.compareTo(FREE_FREIGHT_THRESHOLD) >= 0
                ? BigDecimal.ZERO : BASE_FREIGHT;
    }

    static String generateOrderNo() {
        return "SO" + LocalDateTime.now().format(ORDER_NO_FORMAT)
                + ThreadLocalRandom.current().nextInt(1000, 10000);
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }
}
