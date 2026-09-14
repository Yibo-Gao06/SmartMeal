package com.smartmeal.domain.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 膳食规划请求。
 *
 * <p>{@code requestId} 由前端生成并保证同一逻辑请求复用同一个值，
 * 后端据此实现三件事：防重复提交、断线后查询任务状态、结果幂等落库。
 *
 * <p><b>字段的可选性约定</b>：身体数据（身高 / 体重 / 年龄 / 性别）都是<b>可选的覆盖项</b>，
 * 不传就用用户档案里已存的值（见 {@code UserProfileServiceImpl.buildProfile}）。
 * 这是刻意的：档案里本来就有这些数据，要求客户端每次都重新采集一遍既冗余，
 * 也会让「我只想换个目标试试」这种场景变得很别扭。
 * 传了就必须落在合理区间内，所以范围校验（{@code @DecimalMin} 等）依然保留 ——
 * 它们对 null 不生效。
 *
 * <p>{@code goal} 则是必填：目标是这次请求的<b>核心意图</b>，让它隐式取档案值
 * 容易生成一份用户并不想要的计划。
 */
@Data
public class MealPlanRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "requestId 不能为空")
    @Size(max = 64, message = "requestId 长度不能超过 64")
    private String requestId;

    @NotBlank(message = "健康目标不能为空")
    private String goal;

    /** 可选，不传则用档案里的身高。 */
    @DecimalMin(value = "100", message = "身高不在合理范围")
    @DecimalMax(value = "250", message = "身高不在合理范围")
    private BigDecimal heightCm;

    /** 可选，不传则用档案里的体重。 */
    @DecimalMin(value = "30", message = "体重不在合理范围")
    @DecimalMax(value = "300", message = "体重不在合理范围")
    private BigDecimal weightKg;

    /** 可选，不传则由档案里的出生日期推算。 */
    @Min(value = 14, message = "年龄不在合理范围")
    @Max(value = 100, message = "年龄不在合理范围")
    private Integer age;

    /** 可选，不传则用档案里的性别。0 未知 / 1 男 / 2 女。 */
    @Min(value = 0, message = "性别取值非法")
    @Max(value = 2, message = "性别取值非法")
    private Integer gender;

    /** low / middle / high，缺省按中等活动量。 */
    private String activityLevel = "middle";

    /** 过敏原编码列表，如 ["PEANUT"]。建议传编码而非中文名。 */
    @Size(max = 20, message = "过敏原数量过多")
    private List<String> allergens = new ArrayList<>();

    /**
     * 冰箱现有食材。
     *
     * <p><b>注意：这是用户自由输入，属于不可信内容，必须清洗后才能进 Prompt。</b>
     * 详见 PromptBuilder 的 sanitize 逻辑。
     */
    @Size(max = 50, message = "冰箱食材数量过多")
    private List<String> fridgeIngredients = new ArrayList<>();

    @Min(value = 1, message = "天数至少为 1")
    @Max(value = 30, message = "天数最多为 30")
    private Integer days = 7;

    @Size(max = 4, message = "餐次配置非法")
    private List<String> mealsPerDay = new ArrayList<>(List.of("breakfast", "lunch", "dinner"));

    @DecimalMin(value = "0", message = "预算不能为负")
    private BigDecimal weeklyBudget;

    /** 口味偏好，如「清淡」「微辣」。 */
    @Size(max = 32, message = "口味偏好过长")
    private String tastePreference;
}
