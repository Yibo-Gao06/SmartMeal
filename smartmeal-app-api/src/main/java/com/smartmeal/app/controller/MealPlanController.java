package com.smartmeal.app.controller;

import com.smartmeal.app.support.CurrentUserResolver;
import com.smartmeal.common.exception.BusinessException;
import com.smartmeal.common.result.Result;
import com.smartmeal.common.result.ResultCode;
import com.smartmeal.domain.dto.plan.MealPlanDetailVO;
import com.smartmeal.service.mealplan.MealPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 膳食计划查询接口。
 *
 * <p>生成走 SSE（见 {@link AiPlanController}），这里只负责把<b>已落库</b>的计划读出来。
 * 两者分开是因为生命周期完全不同：生成是一次性的流式过程，
 * 而计划详情会被反复读取（刷新页面、分享链接、切换日期）。
 */
@RestController
@RequestMapping("/api/app/meal-plan")
@RequiredArgsConstructor
@Tag(name = "膳食计划", description = "查询已生成的膳食计划详情")
public class MealPlanController {

    private final MealPlanService mealPlanService;
    private final CurrentUserResolver currentUserResolver;

    /**
     * 查询最近一次生成的计划。
     *
     * <p>注意路由顺序：{@code /latest} 必须能压过 {@code /{planId}}。
     * Spring 的路径匹配会优先选择字面量而非路径变量，所以这里不需要额外处理，
     * 但 {@code planId} 声明成 {@code Long} 是必要的 —— 万一匹配错了，
     * 「latest」转 Long 会直接 400，而不是悄悄查出一个错误的结果。
     */
    @GetMapping("/latest")
    @Operation(summary = "查询最近一次生成的计划",
            description = "用户还没生成过任何计划时 data 为 null，前端应展示空状态而不是报错")
    public Result<MealPlanDetailVO> latest() {
        Long userId = currentUserResolver.currentUserId();
        return Result.success(mealPlanService.findLatestByUser(userId));
    }

    @GetMapping("/{planId}")
    @Operation(summary = "按 ID 查询计划详情",
            description = "返回按天组织的餐次与食材，已由服务端组装好父子关系")
    public Result<MealPlanDetailVO> detail(@PathVariable Long planId) {
        MealPlanDetailVO detail = mealPlanService.findDetail(planId);
        BusinessException.throwIf(detail == null, ResultCode.NOT_FOUND);
        return Result.success(detail);
    }
}
