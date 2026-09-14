package com.smartmeal.service.mealplan;

import com.smartmeal.domain.dto.plan.MealPlanDetailVO;
import com.smartmeal.domain.dto.plan.MealPlanResult;
import com.smartmeal.domain.entity.MealPlan;
import com.smartmeal.service.user.bo.UserProfile;

/** 膳食计划持久化服务。 */
public interface MealPlanService {

    /**
     * 创建一个处于「生成中」状态的计划记录。
     *
     * <p><b>先落库再异步生成</b>，而不是等生成完再插库。这样做的收益：
     * <ul>
     *   <li>用户断线后可以用 requestId 查到任务，不会「白等一场」；</li>
     *   <li>requestId 唯一索引天然实现幂等，重复提交直接被拦；</li>
     *   <li>生成失败也能留下失败记录，方便排查和重试。</li>
     * </ul>
     *
     * @return 新建的计划（含自增 id）
     */
    MealPlan createGenerating(Long userId, UserProfile profile, String requestId, String modelName);

    /** 生成成功后写入计划明细，并更新状态。 */
    void saveResult(Long planId, MealPlanResult result, UserProfile profile);

    /** 标记失败。 */
    void markFailed(Long planId, String errorMsg);

    /** 标记降级为模板。 */
    void markFallback(Long planId, MealPlanResult result, UserProfile profile);

    /** 按 requestId 查询（幂等键）。 */
    MealPlan findByRequestId(String requestId);

    MealPlan findById(Long planId);

    /**
     * 查询计划详情（含天 / 餐 / 食材），供前端渲染。
     *
     * @return 计划不存在时返回 {@code null}
     */
    MealPlanDetailVO findDetail(Long planId);

    /**
     * 查询用户最近一次生成的计划详情。
     *
     * <p>前端进入页面时用它「恢复上次结果」，避免刷新一次就白屏。
     *
     * @return 该用户还没生成过任何计划时返回 {@code null}
     */
    MealPlanDetailVO findLatestByUser(Long userId);

    /** 更新 token 消耗统计。 */
    void updateTokenUsage(Long planId, Integer promptTokens, Integer completionTokens, Integer totalTokens);
}
