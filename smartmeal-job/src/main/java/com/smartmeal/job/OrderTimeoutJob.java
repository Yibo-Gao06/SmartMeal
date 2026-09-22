package com.smartmeal.job;

import com.smartmeal.service.order.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 订单超时取消。
 *
 * <p>生鲜订单的特点是「有配送时效」：用户下单后 15 分钟不支付，库存就必须释放，
 * 否则菜压在仓库里卖不出去。
 *
 * <p>本类只是「调度壳」：扫描、条件更新、<b>回补库存</b>都在
 * {@link OrderService#cancelTimeoutOrders} 里，和用户主动取消共用同一条状态机路径。
 * 之前这里直接改状态不补库存，接入真实扣减后就是丢库存的 bug。
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

    private final OrderService orderService;

    /** 每分钟扫一次。 */
    @Scheduled(cron = "0 * * * * ?")
    public void cancelTimeoutOrders() {
        // 定时任务必须自己兜住异常。@Scheduled 的默认行为是把异常交给
        // TaskUtils$LoggingErrorHandler，打出一整屏堆栈——数据库抖动一次就刷一屏，
        // 真正的业务日志会被埋掉。这里降级成一行 WARN，让任务下一分钟自然重试。
        try {
            orderService.cancelTimeoutOrders(PAY_TIMEOUT_MINUTES, BATCH_SIZE);
        } catch (Exception e) {
            log.warn("订单超时任务执行失败，将在下个周期重试：{}", e.getMessage());
            log.debug("订单超时任务异常详情", e);
        }
    }
}
