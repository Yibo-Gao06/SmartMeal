package com.smartmeal.app.controller;

import com.smartmeal.ai.service.AiPlannerService;
import com.smartmeal.ai.service.MealPlanTaskRegistry;
import com.smartmeal.app.support.CurrentUserResolver;
import com.smartmeal.common.result.Result;
import com.smartmeal.domain.dto.MealPlanRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * AI 膳食规划接口。
 *
 * <p><b>关于 SSE 与 POST 的兼容性问题（重要）</b>
 *
 * <p>本接口用 POST 提交（请求体字段多，且包含身体数据等隐私信息，不适合放 URL），
 * 但浏览器的原生 {@code EventSource} <b>只支持 GET</b>，无法直接消费本接口。
 * 前端必须用 fetch 手动解析事件流：
 *
 * <pre>{@code
 * const resp = await fetch('/api/app/ai/meal-plan/stream', {
 *   method: 'POST',
 *   headers: { 'Content-Type': 'application/json' },
 *   body: JSON.stringify(payload)
 * });
 * const reader = resp.body.getReader();
 * const decoder = new TextDecoder();
 * let buffer = '';
 * while (true) {
 *   const { done, value } = await reader.read();
 *   if (done) break;
 *   buffer += decoder.decode(value, { stream: true });
 *   // 按 \n\n 切分事件块，再解析 event: / data: 两行
 *   let idx;
 *   while ((idx = buffer.indexOf('\n\n')) >= 0) {
 *     const raw = buffer.slice(0, idx);
 *     buffer = buffer.slice(idx + 2);
 *     handleEvent(raw);
 *   }
 * }
 * }</pre>
 *
 * <p>如果坚持用 EventSource，就必须把接口改成 GET + query 传参，
 * 届时 requestId、goal、days 等十余个字段全塞进 URL，既难看又有长度和隐私风险，
 * 因此这里选择了 POST + fetch 方案。
 */
@Slf4j
@RestController
@RequestMapping("/api/app/ai")
@RequiredArgsConstructor
@Tag(name = "AI 膳食规划", description = "流式生成一周食谱并自动生成购物清单")
public class AiPlanController {

    private final AiPlannerService aiPlannerService;
    private final CurrentUserResolver currentUserResolver;

    /**
     * 流式生成膳食计划。
     *
     * <p>方法立即返回 {@link SseEmitter}，真正的生成在独立线程池执行，
     * 因此不会占用 Tomcat 请求线程（这是本项目相对「把大模型调用写进 Controller」
     * 的关键差异之一）。
     */
    @PostMapping(value = "/meal-plan/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式生成一周膳食计划",
            description = """
                    返回 text/event-stream，事件类型：
                    - progress：阶段进度
                    - token：增量文本（打字机效果）
                    - result：最终结果（planId / shoppingListId）
                    - done：正常结束
                    - error：异常结束
                    - heartbeat：心跳（每 15 秒，用于保活）

                    前端必须使用 fetch + ReadableStream 消费，EventSource 不支持 POST。
                    """)
    public SseEmitter generateMealPlanStream(@RequestBody @Valid MealPlanRequest request) {
        Long userId = currentUserResolver.currentUserId();
        log.info("收到生成请求 userId={} requestId={} goal={} days={}",
                userId, request.getRequestId(), request.getGoal(), request.getDays());

        SseEmitter emitter = new SseEmitter(AiPlannerService.sseTimeoutMillis());
        aiPlannerService.generatePlanStream(userId, request, emitter);
        return emitter;
    }

    /** 断线重连时查询任务进度。前端凭 requestId 轮询本接口即可恢复上下文。 */
    @GetMapping("/meal-plan/status/{requestId}")
    @Operation(summary = "查询生成任务状态（断线重连用）")
    public Result<MealPlanTaskRegistry.TaskState> getTaskStatus(@PathVariable String requestId) {
        return Result.success(aiPlannerService.getTaskState(requestId));
    }

    /** 当前 AI 链路组件状态，用于自检：跑在 Mock 还是真实模型、RAG 用的哪种检索。 */
    @GetMapping("/status")
    @Operation(summary = "AI 链路自检", description = "查看当前使用的是真实模型还是 Mock、检索后端类型等")
    public Result<Map<String, Object>> describe() {
        return Result.success(aiPlannerService.describeComponents());
    }
}
