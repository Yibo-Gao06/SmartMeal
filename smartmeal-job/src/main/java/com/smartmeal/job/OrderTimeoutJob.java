package com.smartmeal.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartmeal.domain.entity.Order;
import com.smartmeal.domain.enums.OrderStatusEnum;
import com.smartmeal.repository.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单超时取消。
 *
 * <p>生鲜订单的特点是「有配送时效」：用户下单后 15 分钟不支付，库存就必须释放，
 * 否则菜压在仓库里卖不出去。
 *
 * <p>实现要点：<b>只关状态，不做物理删除</b>。订单是财务凭证，
 * 任何情况下都不能删；取消只改 status 并记录 cancel_time，方便对账和客服追溯。
 *
 * <p>为什么不用延迟队列（RabbitMQ 死信 / Redis ZSet）：
 * 单机 MVP 阶段定时任务足够，且能扛住服务重启（状态在数据库里）。
 * 订单量上来后，改成延迟消息可以把取消延迟从「分钟级」降到「秒级」，
 * 但引入 MQ 也带来消息丢失与重复消费的新问题，现阶段不划算。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "smartmeal.job.enabled", havingValue = "true", matchIfMissing = true)
public class OrderTimeoutJob {

    /** 支付超时时间（分钟）。 */
    private static final int PAY_TIMEOUT_MINUTES = 15;

    /** 单次处理上限，避免一次性捞出太多订单把内存撑爆。 */
    private static final int BATCH_SIZE = 200;

    private final OrderMapper orderMapper;

    /** 每分钟扫一次。 */
    @Scheduled(cron = "0 * * * * ?")
    public void cancelTimeoutOrders() {
        // 定时任务必须自己兜住异常。@Scheduled 的默认行为是把异常交给
        // TaskUtils$LoggingErrorHandler，打出一整屏堆栈——数据库抖动一次就刷一屏，
        // 真正的业务日志会被埋掉。这里降级成一行 WARN，让任务下一分钟自然重试。
        try {
            doCancel();
        } catch (Exception e) {
            log.warn("订单超时任务执行失败，将在下个周期重试：{}", e.getMessage());
            log.debug("订单超时任务异常详情", e);
        }
    }

    private void doCancel() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(PAY_TIMEOUT_MINUTES);

        List<Order> timeoutOrders = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getStatus, OrderStatusEnum.PENDING_PAYMENT.getCode())
                .lt(Order::getCreateTime, deadline)
                .last("limit " + BATCH_SIZE));

        if (timeoutOrders.isEmpty()) {
            return;
        }

        int cancelled = 0;
        for (Order order : timeoutOrders) {
            Order update = new Order();
            update.setId(order.getId());
            update.setStatus(OrderStatusEnum.CANCELLED.getCode());
            update.setCancelTime(LocalDateTime.now());
            update.setRemark("超时未支付，系统自动取消");
            // 条件更新：只有仍是「待支付」才取消，避免和用户正在进行的支付回调打架
            int rows = orderMapper.update(update, Wrappers.<Order>lambdaUpdate()
                    .eq(Order::getId, order.getId())
                    .eq(Order::getStatus, OrderStatusEnum.PENDING_PAYMENT.getCode()));
            cancelled += rows;
        }
        log.info("订单超时任务：扫描 {} 单，实际取消 {} 单", timeoutOrders.size(), cancelled);
    }
}
