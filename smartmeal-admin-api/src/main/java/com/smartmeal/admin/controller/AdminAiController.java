package com.smartmeal.admin.controller;

import com.smartmeal.ai.service.AiPlannerService;
import com.smartmeal.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 运维接口。
 *
 * <p>管理端最该有的 AI 相关能力不是「再生成一次」，而是<b>可观测性</b>：
 * 当前跑的是真实模型还是 Mock、RAG 用的哪种检索、检索 TopK 是多少。
 * 这些信息在排查「为什么今天生成质量变差了」时是第一步要看的。
 */
@RestController
@RequestMapping("/api/admin/ai")
@RequiredArgsConstructor
@Tag(name = "AI 运维", description = "AI 链路状态与生成日志")
public class AdminAiController {

    private final AiPlannerService aiPlannerService;

    @GetMapping("/components")
    @Operation(summary = "查看 AI 链路组件状态")
    public Result<Map<String, Object>> components() {
        return Result.success(aiPlannerService.describeComponents());
    }
}
