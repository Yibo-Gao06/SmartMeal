package com.smartmeal.domain.dto.plan;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 膳食计划详情，供前端渲染「一周食谱」页面。
 *
 * <p>为什么要单独做一个 VO，而不是把实体直接吐给前端：
 * <ol>
 *   <li>实体是扁平的（{@code t_meal_plan_day} 只存 {@code plan_id}，
 *       餐次只存 {@code plan_day_id}），前端拿到三个平铺的数组还得自己 join，
 *       把父子关系在服务端组装好，前端渲染逻辑能少一大截；</li>
 *   <li>实体里有些字段不适合暴露，比如 {@code userId}、{@code requestId}；</li>
 *   <li>可以顺带补上实体没有的展示字段，比如 {@code mealTypeLabel}（英文餐次转中文）、
 *       {@code goalLabel}（目标编码转中文）。</li>
 * </ol>
 *
 * <p>与 {@link MealPlanResult} 的区别：那个是「模型输出的原始契约」，
 * 这个是「已落库数据的展示形态」。前者的字段可能被模型编造，后者一定来自数据库。
 */
@Data
public class MealPlanDetailVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long planId;
    private String planNo;
    /** 目标编码，如 loss_fat。 */
    private String goal;
    /** 目标中文，如「减脂」。 */
    private String goalLabel;
    private Integer dailyCalorieTarget;
    private Integer days;
    /** GENERATING / SUCCESS / FAILED / FALLBACK。 */
    private String status;
    private String modelName;
    /** 生成失败时的原因，成功时为 null。 */
    private String errorMsg;
    private LocalDateTime createTime;

    /**
     * 生成过程的警告。
     *
     * <p>从数据库读出来的，所以刷新页面后依然能看到 ——
     * 其中「过敏原尚未配置映射」这类条目关乎用户安全，不能只在 SSE 里推一次就丢。
     */
    private List<String> warnings = new ArrayList<>();

    /** 全周期热量合计，前端用来显示「本周共摄入 xxxx 千卡」。 */
    private BigDecimal totalCalories;

    private List<DayVO> dayList = new ArrayList<>();

    /** 一天。 */
    @Data
    public static class DayVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Integer dayNo;
        private LocalDate planDate;
        private String summary;
        private BigDecimal totalCalories;
        private BigDecimal totalProtein;
        private BigDecimal totalFat;
        private BigDecimal totalCarb;
        private List<MealVO> meals = new ArrayList<>();
    }

    /** 一餐。 */
    @Data
    public static class MealVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long mealId;
        /** breakfast / lunch / dinner / snack。 */
        private String mealType;
        /** 中文餐次，避免前端再维护一份映射表。 */
        private String mealTypeLabel;
        private Long recipeId;
        private String recipeName;
        private Integer servings;
        private BigDecimal calories;
        private BigDecimal protein;
        private BigDecimal fat;
        private BigDecimal carb;
        private String reason;
        private List<ItemVO> ingredients = new ArrayList<>();
    }

    /** 一餐中的一种食材。 */
    @Data
    public static class ItemVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long ingredientId;
        private String ingredientName;
        private BigDecimal amount;
        private String unit;
    }
}
