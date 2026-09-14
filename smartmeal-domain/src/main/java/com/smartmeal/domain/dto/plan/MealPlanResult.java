package com.smartmeal.domain.dto.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 大模型输出的结构化膳食计划。
 *
 * <p>这个类同时承担两个角色：
 * <ol>
 *   <li>Prompt 里 JSON Schema 的 Java 映射，Jackson 反序列化的目标；</li>
 *   <li>校验器与购物清单生成器的输入契约。</li>
 * </ol>
 *
 * <p>放在 domain 而不是 ai 模块，是因为 {@code ShoppingListService}（service 模块）
 * 也要消费它，而 ai 模块依赖 service，放 ai 会形成循环依赖。
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)}：模型偶尔会多吐几个字段，
 * 直接忽略比让整条链路失败划算。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MealPlanResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String summary;

    /**
     * 模型回传的目标热量。
     *
     * <p><b>注意：这不是真相源，禁止拿它落库或返回前端。</b>
     * 它只是模型对 Prompt 里 {@code dailyCalorieTarget} 输入的复述，没有独立信息量。
     * 后端一律改用 {@code UserProfile.getDailyCalorieTarget()}（由 NutritionCalculator 实算）。
     *
     * <p>保留该字段的原因有二：一是 Prompt 的 JSON Schema 里有它，删掉会破坏解析契约；
     * 二是可以拿它和实算值比对，用来观察模型是否听话（偏差大说明 Prompt 约束力不足）。
     */
    private Integer dailyCalorieTarget;
    /** 模型主动声明的「知识不足」提示，必须透传给前端而不是静默丢弃。 */
    private List<String> warnings = new ArrayList<>();
    private List<Day> days = new ArrayList<>();
    private List<ShoppingItem> shoppingList = new ArrayList<>();

    /** 计划中的一天。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Day implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private Integer dayNo;
        private String summary;
        private List<Meal> meals = new ArrayList<>();
    }

    /** 一顿饭。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Meal implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        /** breakfast / lunch / dinner / snack。 */
        private String mealType;
        private Long recipeId;
        private String recipeName;
        private String reason;
        private Integer servings = 1;
        private List<IngredientItem> ingredients = new ArrayList<>();
        private Nutrition nutrition;
        /** RAG 引用来源，如 ["RECIPE_5001","INGREDIENT_1001"]，用于溯源。 */
        private List<String> sourceIds = new ArrayList<>();
    }

    /** 一餐中的一种食材。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IngredientItem implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private Long ingredientId;
        private String ingredientName;
        private BigDecimal amount;
        private String unit;
        /** 是否来自用户冰箱，由模型标注（后端仍会自行核算一遍）。 */
        private Boolean fromFridge;
        private Long substituteIngredientId;
    }

    /** 营养四要素。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Nutrition implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private BigDecimal calories;
        private BigDecimal protein;
        private BigDecimal fat;
        private BigDecimal carb;
    }

    /** 购物清单条目（模型给出的原始版本，后端会重新聚合核算）。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ShoppingItem implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private Long ingredientId;
        private String ingredientName;
        private BigDecimal requiredAmount;
        private String unit;
        private BigDecimal fridgeAmount;
        private BigDecimal needBuyAmount;
        private String category;
        private List<Long> recipeRefs = new ArrayList<>();
    }
}
