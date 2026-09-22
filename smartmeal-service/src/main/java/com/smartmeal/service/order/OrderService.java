package com.smartmeal.service.order;

import com.smartmeal.common.result.PageResult;
import com.smartmeal.domain.dto.order.OrderCreateRequest;
import com.smartmeal.domain.dto.order.OrderDetailVO;
import com.smartmeal.domain.entity.Order;
import com.smartmeal.domain.enums.OrderStatusEnum;

/**
 * 订单：下单扣库存、支付、取消、查询。
 *
 * <p>状态机（本服务只认这几条边）：
 * <pre>
 *   PENDING_PAYMENT --pay----> PAID --ship--> DELIVERING --finish--> COMPLETED
 *          |
 *          +--cancel/timeout--> CANCELLED   （仅此状态回补库存）
 * </pre>
 */
public interface OrderService {

    /** 购物车结算下单：扣库存 + 生成订单 + 清理已结算条目。 */
    Order createOrder(Long userId, OrderCreateRequest request);

    /** 模拟支付。返回支付后的订单。 */
    Order pay(Long userId, String orderNo, String payType);

    /** 用户主动取消（仅待支付可取消，取消回补库存）。 */
    Order cancel(Long userId, String orderNo);

    /** 订单详情（含明细），只能查自己的。 */
    OrderDetailVO detail(Long userId, String orderNo);

    /** 我的订单分页，status 为空表示全部。 */
    PageResult<Order> page(Long userId, OrderStatusEnum status, long pageNum, long pageSize);

    /**
     * 取消超时未支付的订单并回补库存，供定时任务调用。
     *
     * @return 实际取消的单数
     */
    int cancelTimeoutOrders(int timeoutMinutes, int batchSize);
}
