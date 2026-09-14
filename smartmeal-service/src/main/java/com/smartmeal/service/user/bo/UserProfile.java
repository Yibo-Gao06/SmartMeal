package com.smartmeal.service.user.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组装好的用户画像，是喂给 Prompt 与校验器的唯一输入。
 *
 * <p>设计要点：{@code allergenIngredientIds} 是把过敏原「展开」后的食材 ID 集合。
 * 用户填的是「花生」，这里要变成 {花生, 花生油, 花生酱, 花生碎} 对应的 ID。
 * 校验阶段直接用集合求交，比字符串匹配可靠得多。
 */
@Data
public class UserProfile implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long userId;
    private String goal;
    private BigDecimal heightCm;
    private BigDecimal weightKg;
    private Integer age;
    private Integer gender;
    private String activityLevel;

    /** 用户申报的过敏原编码。 */
    private Set<String> allergenCodes = new LinkedHashSet<>();

    /** 过敏原展开后的食材 ID → 食材名，用于生成人类可读的拦截原因。 */
    private Map<Long, String> allergenIngredientNames = new LinkedHashMap<>();

    /** 冰箱现有食材名称（已清洗）。仅名称，用于喂 Prompt 与 RAG 过滤。 */
    private List<String> fridgeIngredients = new ArrayList<>();

    /**
     * 冰箱食材的<b>数量</b>明细，来自 t_user_fridge_ingredient。
     *
     * <p>为什么和 {@link #fridgeIngredients} 并存而不是合并成一个字段：
     * 调用方 {@code /api/app/ai/meal-plan/stream} 的请求体里只传食材名（用户手填），
     * 拿不到数量；而精确扣减又必须要数量。所以名称列表负责「有哪些」，
     * 这个列表负责「有多少」，购物清单生成时优先用它。
     */
    private List<FridgeItem> fridgeStock = new ArrayList<>();

    private BigDecimal weeklyBudget;
    private String tastePreference;

    private int days = 7;
    private List<String> mealsPerDay = new ArrayList<>(List.of("breakfast", "lunch", "dinner"));

    // ===== 计算派生字段 =====
    private double bmi;
    /** Mifflin-St Jeor 基础代谢率。 */
    private int bmr;
    /** 每日总消耗。 */
    private int tdee;
    /** 按目标调整后的每日目标热量。 */
    private int dailyCalorieTarget;

    /** 冰箱里的一种食材及其数量。 */
    public record FridgeItem(String name, BigDecimal amount, String unit) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    /** 过敏原展开后的食材 ID 集合，校验器直接用。 */
    public Set<Long> allergenIngredientIds() {
        return allergenIngredientNames.keySet();
    }

    /**
     * 用户是否申报了过敏原。
     *
     * <p><b>注意这里必须同时看 {@code allergenCodes}。</b>
     * 只看展开结果会有一个致命漏洞：当 {@code t_allergen_ingredient} 里
     * 没有该过敏原的映射记录时，展开结果是空 Map，
     * 校验器若以「展开结果为空」作为「无需校验」的依据，
     * 过敏原保护就会<b>静默失效</b>——而这恰恰是最需要兜底的场景。
     */
    public boolean hasAllergy() {
        return !allergenCodes.isEmpty() || !allergenIngredientNames.isEmpty();
    }
}
