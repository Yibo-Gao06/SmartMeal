package com.smartmeal.ai.service;

import com.smartmeal.ai.client.ChatResult;
import com.smartmeal.ai.client.LlmClient;
import com.smartmeal.ai.config.AiProperties;
import com.smartmeal.ai.parser.PlanJsonParser;
import com.smartmeal.ai.prompt.PromptBuilder;
import com.smartmeal.ai.rag.KnowledgeChunk;
import com.smartmeal.ai.rag.RetrievalService;
import com.smartmeal.ai.sse.SseHelper;
import com.smartmeal.ai.template.TemplatePlanFactory;
import com.smartmeal.ai.validator.PlanValidator;
import com.smartmeal.ai.validator.ValidationReport;
import com.smartmeal.common.exception.BusinessException;
import com.smartmeal.common.result.ResultCode;
import com.smartmeal.domain.dto.MealPlanRequest;
import com.smartmeal.domain.dto.plan.MealPlanResult;
import com.smartmeal.domain.entity.MealPlan;
import com.smartmeal.domain.enums.PlanStatusEnum;
import com.smartmeal.service.mealplan.MealPlanService;
import com.smartmeal.service.shopping.ShoppingListService;
import com.smartmeal.service.support.RateLimitService;
import com.smartmeal.service.user.UserProfileService;
import com.smartmeal.service.user.bo.UserProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * AI 膳食规划编排服务。
 *
 * <p>这是整个项目的中枢，负责把「限流 → 幂等 → 画像 → 检索 → 组装 Prompt →
 * 流式生成 → 解析 → 校验 → 落库 → 生成购物清单」串成一条链。
 *
 * <p>三个关键设计决策，也是这份代码相对「把大模型调用写在 Controller 里」的差异所在：
 *
 * <ol>
 *   <li><b>异步执行 + 先落库。</b>请求线程只负责创建 SseEmitter 并提交任务，立刻返回。
 *       任务一开始就在数据库里落一条 GENERATING 记录，所以用户断线后能用 requestId
 *       查到进度，而不是「白等一场」。</li>
 *   <li><b>心跳保活。</b>大模型可能 20 秒不吐一个字，Nginx 和浏览器都会认为连接空闲并掐断。
 *       所以每 15 秒发一次 heartbeat 事件。</li>
 *   <li><b>失败必须降级而不是报错。</b>模型超时、返回乱码、JSON 截断……
 *       这些都是必然会发生的事。链路末端准备了模板兜底，保证用户始终能拿到可执行的方案。</li>
 * </ol>
 */
@Slf4j
@Service
public class AiPlannerService {

    /** 心跳间隔（秒）。要小于 Nginx proxy_read_timeout 与浏览器空闲超时。 */
    private static final long HEARTBEAT_INTERVAL_SECONDS = 15L;

    /** SSE 连接超时（毫秒），要大于单次生成的最长耗时。 */
    private static final long SSE_TIMEOUT_MILLIS = 180_000L;

    private final UserProfileService userProfileService;
    private final RetrievalService retrievalService;
    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final PlanJsonParser planJsonParser;
    private final PlanValidator planValidator;
    private final MealPlanService mealPlanService;
    private final ShoppingListService shoppingListService;
    private final RateLimitService rateLimitService;
    private final MealPlanTaskRegistry taskRegistry;
    private final TemplatePlanFactory templatePlanFactory;
    private final AiProperties properties;
    private final ThreadPoolTaskExecutor aiTaskExecutor;
    private final ScheduledExecutorService heartbeatScheduler;

    public AiPlannerService(UserProfileService userProfileService,
                            RetrievalService retrievalService,
                            PromptBuilder promptBuilder,
                            LlmClient llmClient,
                            PlanJsonParser planJsonParser,
                            PlanValidator planValidator,
                            MealPlanService mealPlanService,
                            ShoppingListService shoppingListService,
                            RateLimitService rateLimitService,
                            MealPlanTaskRegistry taskRegistry,
                            TemplatePlanFactory templatePlanFactory,
                            AiProperties properties,
                            @Qualifier("aiTaskExecutor") ThreadPoolTaskExecutor aiTaskExecutor,
                            @Qualifier("aiHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler) {
        this.userProfileService = userProfileService;
        this.retrievalService = retrievalService;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.planJsonParser = planJsonParser;
        this.planValidator = planValidator;
        this.mealPlanService = mealPlanService;
        this.shoppingListService = shoppingListService;
        this.rateLimitService = rateLimitService;
        this.taskRegistry = taskRegistry;
        this.templatePlanFactory = templatePlanFactory;
        this.properties = properties;
        this.aiTaskExecutor = aiTaskExecutor;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    /** 供 Controller 使用的 SSE 超时值。 */
    public static long sseTimeoutMillis() {
        return SSE_TIMEOUT_MILLIS;
    }

    /**
     * 提交一次流式生成。
     *
     * <p>方法本身立即返回，真正的生成在独立线程池里跑。
     */
    public void generatePlanStream(Long userId, MealPlanRequest request, SseEmitter emitter) {
        SseHelper sse = new SseHelper(emitter);
        try {
            aiTaskExecutor.execute(() -> doGenerate(userId, request, sse));
        } catch (RejectedExecutionException e) {
            log.warn("AI 线程池已满，拒绝任务 userId={}", userId);
            sse.sendError(ResultCode.TOO_MANY_REQUESTS.getCode(),
                    "当前生成任务较多，请稍后再试");
            sse.complete();
        }
    }

    /** 查询任务进度，供前端断线重连。 */
    public MealPlanTaskRegistry.TaskState getTaskState(String requestId) {
        MealPlanTaskRegistry.TaskState state = taskRegistry.get(requestId);
        if (state != null) {
            return state;
        }
        // 内存里没有（可能已重启），退回数据库查终态
        MealPlan plan = mealPlanService.findByRequestId(requestId);
        if (plan == null) {
            throw new BusinessException(ResultCode.AI_TASK_NOT_FOUND);
        }
        MealPlanTaskRegistry.TaskState fallback = new MealPlanTaskRegistry.TaskState();
        fallback.setRequestId(requestId);
        fallback.setPlanId(plan.getId());
        fallback.setStage(plan.getStatus());
        fallback.setFinished(!PlanStatusEnum.GENERATING.getCode().equals(plan.getStatus()));
        fallback.setErrorMsg(plan.getErrorMsg());
        return fallback;
    }

    // ==================== 主流程 ====================

    private void doGenerate(Long userId, MealPlanRequest request, SseHelper sse) {
        String requestId = request.getRequestId();
        ScheduledFuture<?> heartbeat = startHeartbeat(sse);
        Long planId = null;

        try {
            // ---- 1. 限流：AI 接口不设防等于给攻击者开了一张无限额度的账单 ----
            if (!rateLimitService.tryAcquireAiPlan(userId)) {
                sse.sendError(ResultCode.AI_RATE_LIMITED.getCode(), ResultCode.AI_RATE_LIMITED.getMessage());
                sse.complete();
                return;
            }

            // ---- 2. 幂等：同一个 requestId 已生成过就直接返回，不重复烧 token ----
            MealPlan existing = mealPlanService.findByRequestId(requestId);
            if (existing != null && !PlanStatusEnum.GENERATING.getCode().equals(existing.getStatus())) {
                log.info("命中幂等 requestId={} 直接返回已有计划 planId={}", requestId, existing.getId());
                sse.sendResult(Map.of(
                        "planId", existing.getId(),
                        "status", existing.getStatus(),
                        "idempotent", true));
                sse.sendDone();
                sse.complete();
                return;
            }

            sse.sendProgress("INIT", "正在初始化生成任务");
            taskRegistry.register(requestId, null, userId);

            // ---- 3. 清洗用户输入：冰箱食材是自由文本，是 Prompt 注入的入口 ----
            request.setFridgeIngredients(promptBuilder.sanitizeInputs(request.getFridgeIngredients()));

            // ---- 4. 构建画像（含过敏原展开与热量计算）----
            UserProfile profile = userProfileService.buildProfile(userId, request);

            // ---- 5. 先落库 GENERATING，保证断线可查 ----
            MealPlan plan = mealPlanService.createGenerating(
                    userId, profile, requestId, llmClient.modelName());
            planId = plan.getId();
            taskRegistry.updateStage(requestId, "GENERATING");

            // ---- 6. RAG 检索 ----
            List<KnowledgeChunk> chunks = List.of();
            if (properties.isRagEnabled()) {
                sse.sendProgress("RETRIEVE", "正在检索营养知识库");
                chunks = retrievalService.retrieve(profile, properties.getTopK());
            }

            // ---- 7. 组装 Prompt ----
            sse.sendProgress("PROMPT", "正在构建提示词");
            String systemPrompt = promptBuilder.buildSystemPrompt();
            String userPrompt = promptBuilder.buildUserPrompt(profile, chunks);

            // ---- 8. 流式生成并实时转发 ----
            sse.sendProgress("GENERATE", "正在生成一周食谱");
            final SseHelper sseRef = sse;
            ChatResult chatResult = llmClient.streamChat(systemPrompt, userPrompt, token -> {
                if (!sseRef.isClientGone()) {
                    sseRef.sendToken(token);
                }
            });

            // ---- 9. 解析（失败则让模型自我修复）----
            sse.sendProgress("PARSING", "正在解析生成结果");
            MealPlanResult result = parseWithRepair(chatResult.content(), systemPrompt);

            boolean fallback = false;
            if (result == null) {
                // 修复也救不回来 → 降级为模板，而不是给用户一个错误页
                log.warn("JSON 修复失败，降级为模板食谱 planId={}", planId);
                result = templatePlanFactory.build(profile);
                fallback = true;
                sse.sendProgress("FALLBACK", "AI 输出异常，已切换为模板食谱");
            }

            // ---- 10. 后置校验：不信任模型，关键约束全部重算 ----
            sse.sendProgress("VALIDATING", "正在校验过敏原与营养区间");
            ValidationReport report = planValidator.validate(result, profile);
            if (report.hasError()) {
                // 过敏原命中属于硬错误，整份拒绝
                String errorMsg = report.errorSummary();
                mealPlanService.markFailed(planId, errorMsg);
                taskRegistry.fail(requestId, errorMsg);
                sse.sendError(ResultCode.AI_ALLERGEN_HIT.getCode(), errorMsg);
                sse.complete();
                return;
            }
            // 警告透传给前端，不阻断
            if (!report.getWarnings().isEmpty()) {
                result.getWarnings().addAll(report.getWarnings());
            }

            // ---- 11. 落库 ----
            sse.sendProgress("SAVING", "正在保存膳食计划");
            if (fallback) {
                mealPlanService.markFallback(planId, result, profile);
            } else {
                mealPlanService.saveResult(planId, result, profile);
            }
            mealPlanService.updateTokenUsage(planId, chatResult.promptTokens(),
                    chatResult.completionTokens(), chatResult.totalTokens());

            // ---- 12. 生成购物清单 ----
            sse.sendProgress("SHOPPING_LIST", "正在生成购物清单");
            Long shoppingListId = shoppingListService.generate(userId, planId, result, profile);

            // ---- 13. 收尾 ----
            taskRegistry.complete(requestId, planId, shoppingListId);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("planId", planId);
            payload.put("shoppingListId", shoppingListId);
            payload.put("fallback", fallback);
            payload.put("mock", llmClient.isMock());
            payload.put("warnings", result.getWarnings());
            // 同样不采信 result 里的值：校验、落库、返回前端必须是同一个数，
            // 否则前端展示的「目标热量」会和校验器判偏离度用的基准对不上。
            payload.put("dailyCalorieTarget", profile.getDailyCalorieTarget());
            payload.put("summary", result.getSummary());
            sse.sendResult(payload);
            sse.sendDone();

            log.info("生成完成 userId={} planId={} 清单={} 降级={} mock={} tokens={}",
                    userId, planId, shoppingListId, fallback, llmClient.isMock(),
                    chatResult.totalTokens());

        } catch (BusinessException e) {
            log.warn("生成业务异常 requestId={} code={} msg={}", requestId, e.getCode(), e.getMessage());
            if (planId != null) {
                mealPlanService.markFailed(planId, e.getMessage());
            }
            taskRegistry.fail(requestId, e.getMessage());
            sse.sendError(e.getCode(), e.getMessage());
            sse.complete();

        } catch (Exception e) {
            log.error("生成失败 requestId={}", requestId, e);
            if (planId != null) {
                mealPlanService.markFailed(planId, e.getMessage());
            }
            taskRegistry.fail(requestId, e.getMessage());
            sse.sendError(ResultCode.AI_GENERATE_FAILED.getCode(),
                    "生成失败，请稍后重试：" + e.getMessage());
            sse.complete();

        } finally {
            heartbeat.cancel(false);
            sse.complete();
        }
    }

    /**
     * 解析 + 自我修复。
     *
     * <p>模型返回非法 JSON 是高频事件（尤其输出被 max_tokens 截断时）。
     * 直接把错误抛给用户很浪费 —— 把错误信息回喂给模型，通常一次就能修好。
     * 修复仍失败才降级。
     *
     * @return 解析成功的计划；彻底失败返回 {@code null}，由调用方降级
     */
    private MealPlanResult parseWithRepair(String rawContent, String systemPrompt) {
        Optional<MealPlanResult> first = planJsonParser.tryParse(rawContent);
        if (first.isPresent()) {
            return first.get();
        }

        String current = rawContent;
        String error = "输出不是合法 JSON";
        int maxAttempts = properties.getMaxRepairAttempts();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            log.warn("JSON 解析失败，发起第 {} 次修复", attempt);
            try {
                String repairPrompt = promptBuilder.buildRepairPrompt(current, error);
                ChatResult repaired = llmClient.chat(systemPrompt, repairPrompt);
                Optional<MealPlanResult> retry = planJsonParser.tryParse(repaired.content());
                if (retry.isPresent()) {
                    log.info("第 {} 次修复成功", attempt);
                    return retry.get();
                }
                current = repaired.content();
                error = "修复后仍不是合法 JSON";
            } catch (Exception e) {
                log.warn("第 {} 次修复调用异常: {}", attempt, e.getMessage());
                error = e.getMessage();
            }
        }
        return null;
    }

    /** 启动心跳，返回可取消的句柄。 */
    private ScheduledFuture<?> startHeartbeat(SseHelper sse) {
        return heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (sse.isClientGone()) {
                return;
            }
            sse.sendHeartbeat();
        }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** 健康检查用：当前 AI 链路各组件状态。 */
    public Map<String, Object> describeComponents() {
        Map<String, Object> info = new HashMap<>();
        info.put("llmClient", llmClient.getClass().getSimpleName());
        info.put("model", llmClient.modelName());
        info.put("mock", llmClient.isMock());
        info.put("retrievalBackend", retrievalService.backend());
        info.put("ragEnabled", properties.isRagEnabled());
        info.put("topK", properties.getTopK());
        return info;
    }
}
