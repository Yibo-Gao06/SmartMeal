package com.smartmeal.app.controller;

import com.smartmeal.app.support.CurrentUserResolver;
import com.smartmeal.common.result.PageResult;
import com.smartmeal.common.result.Result;
import com.smartmeal.domain.dto.order.OrderCreateRequest;
import com.smartmeal.domain.dto.order.OrderDetailVO;
import com.smartmeal.domain.dto.order.OrderPayRequest;
import com.smartmeal.domain.entity.Order;
import com.smartmeal.domain.enums.OrderStatusEnum;
import com.smartmeal.service.order.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单：结算下单、模拟支付、取消、查询。
 *
 * <p>对外的单据标识一律用 {@code orderNo}（业务单号），主键 id 不出接口，防止被遍历。
 *
 * <p>支付是<b>模拟</b>的：真实项目这一步是「下单后拉起支付渠道 → 渠道异步回调 →
 * 回调里条件更新状态」。这里把两步合并成一个同步接口，
 * 但状态机与防打架手段（条件更新）和真实链路一致，接真渠道时只需替换本方法的实现。
 */
@RestController
@RequestMapping("/api/app/orders")
@RequiredArgsConstructor
@Tag(name = "订单", description = "下单 / 支付 / 取消 / 查询")
public class OrderController {

    private final OrderService orderService;
    private final CurrentUserResolver currentUserResolver;

    @PostMapping
    @Operation(summary = "购物车结算下单",
            description = "对勾选条目按 SKU 归并后条件扣库存，任一 SKU 库存不足整单回滚")
    public Result<Order> create(@RequestBody(required = false) @Valid OrderCreateRequest request) {
        Long userId = currentUserResolver.currentUserId();
        return Result.success(orderService.createOrder(userId, request));
    }

    @GetMapping
    @Operation(summary = "我的订单分页", description = "status 可选：PENDING_PAYMENT/PAID/... 缺省查全部")
    public Result<PageResult<Order>> page(@RequestParam(defaultValue = "1") long pageNum,
                                          @RequestParam(defaultValue = "10") long pageSize,
                                          @RequestParam(required = false) String status) {
        Long userId = currentUserResolver.currentUserId();
        return Result.success(orderService.page(userId, OrderStatusEnum.of(status), pageNum, pageSize));
    }

    @GetMapping("/{orderNo}")
    @Operation(summary = "订单详情", description = "含下单时的商品与价格快照")
    public Result<OrderDetailVO> detail(@PathVariable String orderNo) {
        Long userId = currentUserResolver.currentUserId();
        return Result.success(orderService.detail(userId, orderNo));
    }

    @PostMapping("/{orderNo}/pay")
    @Operation(summary = "支付订单（模拟）",
            description = "仅待支付状态可支付；重复调用幂等返回；已被超时取消则报错")
    public Result<Order> pay(@PathVariable String orderNo,
                             @RequestBody(required = false) @Valid OrderPayRequest request) {
        Long userId = currentUserResolver.currentUserId();
        String payType = request == null ? "MOCK" : request.getPayType();
        return Result.success(orderService.pay(userId, orderNo, payType));
    }

    @PostMapping("/{orderNo}/cancel")
    @Operation(summary = "取消订单", description = "仅待支付可取消，取消成功后回补库存")
    public Result<Order> cancel(@PathVariable String orderNo) {
        Long userId = currentUserResolver.currentUserId();
        return Result.success(orderService.cancel(userId, orderNo));
    }
}
