package com.smartmeal.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** AI 膳食计划主表 t_meal_plan。同时承担「生成任务」的角色。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_meal_plan")
public class MealPlan extends BaseEntity {

    private Long userId;
    private String planNo;
    private String goal;
    private Integer dailyCalorieTarget;
    private Integer days;

    /** GENERATING / SUCCESS / FAILED，见 PlanStatusEnum。 */
    private String status;

    /** 幂等键：同一个 requestId 只允许生成一次，用于防重复提交与断线重连查询。 */
    private String requestId;

    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private String modelName;
    private String errorMsg;

    /**
     * 生成过程中产生的警告，按行分隔。
     *
     * <p>落库而不是只推给前端，是因为其中有安全相关的条目 ——
     * 比如「以下过敏原尚未配置食材映射，本次无法做精确校验」。
     * 如果只在 SSE 里推一次，用户刷新页面后这条提示就消失了，
     * 会误以为「系统已经帮我完整校验过了」。
     */
    private String warnings;
}
