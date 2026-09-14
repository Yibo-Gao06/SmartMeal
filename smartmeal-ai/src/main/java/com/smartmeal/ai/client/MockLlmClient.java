package com.smartmeal.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmeal.domain.dto.plan.MealPlanResult;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 无 Key 时的兜底客户端。
 *
 * <p>存在的意义：把「模型不可用」和「代码有 bug」这两件事解耦。
 * 没有它，本地开发一旦没配 API Key，整条链路就完全测不了，
 * 分不清是 Prompt 写错了还是网络不通。
 *
 * <p>它返回的是<b>结构完全合法</b>的 JSON（由 Java 对象序列化而来，不是手写字符串），
 * 因此下游的解析、校验、购物清单生成逻辑都能被真实地跑通。
 * 但数据是假的，前端会收到 {@code mock:true} 标记，必须显式提示用户。
 *
 * <p><b>关于演示数据的「像样程度」</b>：菜谱、食材、以及「哪道菜用哪些食材、各用多少」
 * 都照抄 {@code mysql-init.sql} 的种子数据（{@code t_recipe} / {@code t_ingredient} /
 * {@code t_recipe_ingredient} 三张表），不是随手编的。这样演示数据才能通过
 * {@code PlanValidator} 的实体存在性校验，前端的菜谱详情页也真的点得开。
 * 另外天数和目标热量会从 Prompt 里反查，避免出现「请求 3 天给 7 天」
 * 「目标 2155 千卡给 1000 千卡」这种一眼假的数据。
 *
 * <p>这些「像样程度」上的要求看着像强迫症，但它们决定了一件事：
 * 演示时能不能把界面直接投给面试官看。数据一旦自相矛盾，
 * 对方问的第一句话就会变成「这是不是写死的」，而没法聊到真正的设计上。
 */
@Slf4j
public class MockLlmClient implements LlmClient {

    private final ObjectMapper objectMapper;

    /** 演示用菜谱，字段与 {@code t_recipe} 种子数据一一对应。 */
    record MockRecipe(long id, String name, String mealType,
                      String calories, String protein, String fat, String carb) {
    }

    /**
     * 菜谱池。<b>ID、名称、餐次、营养值必须与 {@code t_recipe} 的种子数据严格一致。</b>
     *
     * <p>这里踩过两个坑，都是「看起来没问题」的类型：
     * <ol>
     *   <li>ID 曾经用 {@code 5000 + index} 算出来，而库里是从 <b>5001</b> 开始的，
     *       于是每份计划都夹带一个不存在的菜谱 ID 5000，
     *       {@code PlanValidator} 只能报「菜谱 ID 在平台不存在，可能是模型编造的」；</li>
     *   <li>数组顺序与库里的 ID 顺序不一致，即便 ID 改对了，
     *       也会出现「ID 指向香煎龙利鱼、名称却写着番茄鸡蛋汤面」的错配。</li>
     * </ol>
     */
    private static final List<MockRecipe> RECIPE_POOL = List.of(
            new MockRecipe(5001L, "番茄炒蛋", "breakfast", "220", "14.0", "14.0", "8.0"),
            new MockRecipe(5002L, "燕麦牛奶粥", "breakfast", "310", "12.0", "7.0", "52.0"),
            new MockRecipe(5003L, "清蒸鸡胸肉配西兰花", "lunch", "420", "46.0", "12.0", "26.0"),
            new MockRecipe(5004L, "香煎龙利鱼配糙米", "lunch", "520", "42.0", "14.0", "52.0"),
            new MockRecipe(5005L, "豆腐菌菇煲", "dinner", "300", "22.0", "14.0", "22.0"),
            new MockRecipe(5006L, "凉拌鸡丝黄瓜", "dinner", "280", "32.0", "10.0", "12.0"),
            new MockRecipe(5007L, "蒜蓉炒菠菜", "dinner", "120", "5.0", "8.0", "8.0"),
            new MockRecipe(5008L, "白灼虾配时蔬", "dinner", "350", "40.0", "8.0", "20.0"),
            new MockRecipe(5009L, "番茄鸡蛋汤面", "breakfast", "480", "20.0", "12.0", "72.0"),
            new MockRecipe(5010L, "全麦鸡蛋三明治", "breakfast", "340", "18.0", "12.0", "40.0"));

    /** 按餐次分组，避免出现「早餐推荐蒜蓉炒菠菜」这种不合理的搭配。 */
    private static final Map<String, List<MockRecipe>> RECIPES_BY_MEAL = RECIPE_POOL.stream()
            .collect(Collectors.groupingBy(MockRecipe::mealType));

    /**
     * 演示用食材主数据，ID / 名称 / 单位与 {@code t_ingredient} 种子数据一致。
     *
     * <p><b>注意这里没有「每份用量」字段。</b>早期版本给它加过一个
     * {@code amountPerServing}，结构上就错了 —— 同一种食材在不同菜里用量本来就不同：
     * 鸡胸肉在「清蒸鸡胸肉配西兰花」里是 150g，在「凉拌鸡丝黄瓜」里是 120g；
     * 菠菜在「豆腐菌菇煲」里是 100g，在「蒜蓉炒菠菜」里是 200g。
     * 塞进主数据就只能取其中一个值，另一道菜的用量必然是错的。
     * 用量属于「菜谱 × 食材」这个组合，因此单独放在 {@link #RECIPE_INGREDIENTS}。
     *
     * <p>1012~1014 是花生系衍生品，本文件的演示菜谱都没用到它们 ——
     * 它们的存在是为了配合 {@code t_allergen_ingredient} 的过敏原展开：
     * 用户申报「花生」时，禁忌集合会变成 {花生, 花生油, 花生酱}。
     * 演示数据里不含花生，所以花生过敏不会触发拦截；要演示拦截效果请用 EGG。
     */
    record MockIngredient(long id, String name, String unit) {
    }

    private static final List<MockIngredient> INGREDIENT_POOL = List.of(
            new MockIngredient(1001L, "番茄", "g"),
            new MockIngredient(1002L, "鸡蛋", "piece"),
            new MockIngredient(1003L, "鸡胸肉", "g"),
            new MockIngredient(1004L, "西兰花", "g"),
            new MockIngredient(1005L, "燕麦", "g"),
            new MockIngredient(1006L, "牛奶", "ml"),
            new MockIngredient(1007L, "糙米", "g"),
            new MockIngredient(1008L, "豆腐", "g"),
            new MockIngredient(1009L, "菠菜", "g"),
            new MockIngredient(1010L, "龙利鱼", "g"),
            new MockIngredient(1011L, "虾", "g"),
            new MockIngredient(1012L, "花生", "g"),
            new MockIngredient(1013L, "花生油", "ml"),
            new MockIngredient(1014L, "花生酱", "g"),
            new MockIngredient(1015L, "黄瓜", "g"));

    private static final Map<Long, MockIngredient> INGREDIENT_BY_ID = INGREDIENT_POOL.stream()
            .collect(Collectors.toUnmodifiableMap(MockIngredient::id, i -> i));

    /**
     * 一道菜用到的食材及<b>每份</b>用量。
     *
     * <p>{@code amount} 是字符串而不是 {@code BigDecimal}：它只在构造演示数据时
     * 乘一次份数，用字符串能让下面的常量表读起来和 SQL 种子数据一模一样，
     * 便于逐行核对。
     */
    record MockRecipeIngredient(long ingredientId, String amount) {
    }

    private static MockRecipeIngredient ing(long ingredientId, String amount) {
        return new MockRecipeIngredient(ingredientId, amount);
    }

    /**
     * 菜谱 → 食材用量，对应种子数据里的 {@code t_recipe_ingredient} 表。
     *
     * <p>之所以照着数据库拆成两张表（而不是在 {@link MockRecipe} 里塞一个食材列表），
     * 是因为这样能逐行对着 {@code mysql-init.sql} 核对，出错时一眼就能看出是哪一行的问题。
     *
     * <p><b>这份映射曾经是错的</b>：早先的写法是
     * {@code offset = recipe.id() % 池子大小}，从食材池里随手取两个，
     * 于是「番茄炒蛋」配出了鸡蛋 + 鸡胸肉、「豆腐菌菇煲」配出了牛奶 + 糙米。
     * 这种错在日志里完全看不出来，只有人眼看页面才会发现 —— 而它恰好会毁掉
     * 整个演示的可信度，所以现在改为写死真实关系，并在下面的静态块里做一致性自检。
     */
    private static final Map<Long, List<MockRecipeIngredient>> RECIPE_INGREDIENTS = Map.of(
            5001L, List.of(ing(1001L, "200"), ing(1002L, "2")),
            5002L, List.of(ing(1005L, "50"), ing(1006L, "250")),
            5003L, List.of(ing(1003L, "150"), ing(1004L, "150")),
            5004L, List.of(ing(1010L, "180"), ing(1007L, "80")),
            5005L, List.of(ing(1008L, "200"), ing(1009L, "100")),
            5006L, List.of(ing(1003L, "120"), ing(1015L, "100")),
            5007L, List.of(ing(1009L, "200")),
            5008L, List.of(ing(1011L, "200"), ing(1004L, "100")),
            5009L, List.of(ing(1001L, "150"), ing(1002L, "2")),
            5010L, List.of(ing(1002L, "2"), ing(1005L, "60")));

    /**
     * 演示数据自检，类加载时执行。
     *
     * <p>刻意选择「启动即失败」而不是「运行时降级」：Mock 存在的唯一意义就是
     * 在没配 API Key 时也能跑通整条链路，如果它自己产出的数据是自相矛盾的，
     * 那它比直接报错更糟 —— 报错至少能被发现，错数据会被当成「系统就是这样」。
     */
    static {
        for (MockRecipe recipe : RECIPE_POOL) {
            List<MockRecipeIngredient> items = RECIPE_INGREDIENTS.get(recipe.id());
            if (items == null || items.isEmpty()) {
                throw new IllegalStateException("演示菜谱 " + recipe.id() + "（" + recipe.name()
                        + "）没有配置食材，Mock 数据不完整");
            }
            for (MockRecipeIngredient item : items) {
                if (!INGREDIENT_BY_ID.containsKey(item.ingredientId())) {
                    throw new IllegalStateException("演示菜谱 " + recipe.id() + "（" + recipe.name()
                            + "）引用了不存在的食材 ID " + item.ingredientId());
                }
            }
        }
        for (Long recipeId : RECIPE_INGREDIENTS.keySet()) {
            if (RECIPE_POOL.stream().noneMatch(r -> r.id() == recipeId)) {
                throw new IllegalStateException("食材配置引用了不存在的菜谱 ID " + recipeId);
            }
        }
    }

    /** 按 ID 查食材主数据，找不到返回 null（由调用方或静态自检决定怎么处理）。 */
    static MockIngredient ingredientOf(long ingredientId) {
        return INGREDIENT_BY_ID.get(ingredientId);
    }

    /** 每天固定的餐次，与 {@code UserProfile.mealsPerDay} 的默认值保持一致。 */
    private static final List<String> DEFAULT_MEALS = List.of("breakfast", "lunch", "dinner");

    public MockLlmClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ChatResult chat(String systemPrompt, String userPrompt) {
        return ChatResult.of(buildJson(resolveContext(userPrompt)), "mock");
    }

    @Override
    public ChatResult streamChat(String systemPrompt, String userPrompt, Consumer<String> onToken) {
        String json = buildJson(resolveContext(userPrompt));
        // 切成 60 字符的片段推送，模拟真实流式的节奏，方便前端联调打字机效果
        int chunkSize = 60;
        for (int i = 0; i < json.length(); i += chunkSize) {
            String piece = json.substring(i, Math.min(json.length(), i + chunkSize));
            onToken.accept(piece);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("MockLlmClient 已返回演示数据（未配置真实 API Key）");
        return ChatResult.of(json, "mock");
    }

    @Override
    public String modelName() {
        return "mock-deepseek-chat";
    }

    @Override
    public boolean isMock() {
        return true;
    }

    // ==================== 从 Prompt 反查用户意图 ====================

    /**
     * 从 Prompt 里反查出来的用户意图，Mock 据此生成「像样」的演示数据。
     *
     * <p>{@link LlmClient#chat} 的签名里只有两个字符串，Mock 也拿不到 {@code UserProfile}，
     * 只能从 {@code PromptBuilder} 拼好的用户 JSON 里解析。
     */
    record MockContext(int days, int dailyCalorieTarget, String goalLabel) {
    }

    /** 解析不出天数时用这个值，与 {@code UserProfile.days} 的默认值保持一致。 */
    static final int DEFAULT_DAYS = 7;

    /** 解析不出目标热量时用这个值，与 {@code NutritionCalculator} 的兜底值保持一致。 */
    static final int DEFAULT_CALORIE_TARGET = 1800;

    /** 解析不出目标时的兜底文案。 */
    static final String DEFAULT_GOAL_LABEL = "健康饮食";

    /** 天数上限。用户传 999 天会把 Mock 撑爆，这里做个防御性收敛。 */
    private static final int MAX_DAYS = 14;

    /** 目标热量的防御性区间：过小会把份数全压成 1，过大会生成不合理的用量。 */
    private static final int MIN_CALORIE_TARGET = 800;
    private static final int MAX_CALORIE_TARGET = 6000;

    /** PromptBuilder 会把用户信息序列化成 JSON 塞进 userPrompt，其中含 {@code "days":3}。 */
    private static final Pattern DAYS_PATTERN = Pattern.compile("\"days\"\\s*:\\s*(\\d+)");

    /** 同理，{@code "dailyCalorieTarget":2155} 与 {@code "goalLabel":"减脂"}。 */
    private static final Pattern CALORIE_PATTERN =
            Pattern.compile("\"dailyCalorieTarget\"\\s*:\\s*(\\d+)");
    private static final Pattern GOAL_LABEL_PATTERN =
            Pattern.compile("\"goalLabel\"\\s*:\\s*\"([^\"]+)\"");

    static MockContext resolveContext(String userPrompt) {
        return new MockContext(resolveDays(userPrompt), resolveCalorieTarget(userPrompt),
                resolveGoalLabel(userPrompt));
    }

    /**
     * 从 userPrompt 里反查用户请求的天数。
     *
     * <p>必须这么做的原因：Mock 曾经硬编码 {@code buildJson(7)}，于是「请求 3 天」
     * 会返回 7 天的计划，落库后主表 {@code t_meal_plan.days=3} 而明细
     * {@code t_meal_plan_day} 有 7 行 —— 一份自相矛盾的数据。
     * 现在 {@code PlanValidator} 会把天数不符判为硬错误，Mock 若再解析错就直接生成失败，
     * 所以这个方法的正确性由单元测试钉住。
     *
     * @return 解析到的天数；解析失败返回 {@link #DEFAULT_DAYS}
     */
    static int resolveDays(String userPrompt) {
        if (userPrompt == null || userPrompt.isEmpty()) {
            return DEFAULT_DAYS;
        }
        Matcher matcher = DAYS_PATTERN.matcher(userPrompt);
        if (!matcher.find()) {
            return DEFAULT_DAYS;
        }
        try {
            int days = Integer.parseInt(matcher.group(1));
            return Math.max(1, Math.min(days, MAX_DAYS));
        } catch (NumberFormatException e) {
            // 正则已经保证是纯数字，理论到不了这里；真到了也不该让整条链路失败
            log.warn("解析 Prompt 中的 days 失败，回退为默认值 {}：{}", DEFAULT_DAYS, e.getMessage());
            return DEFAULT_DAYS;
        }
    }

    /**
     * 从 userPrompt 里反查目标热量，Mock 用它来校准演示数据的份量。
     *
     * <p>不校准的后果很直观：3 餐合计只有 1000 千卡上下，对 2155 的目标偏离约 50%，
     * 前端会刷一屏「第 N 天总热量偏离目标 xx%」的警告 —— 看着像系统算错了，
     * 其实只是演示数据太素。校准后偏离能压到 ±20% 容差内。
     */
    static int resolveCalorieTarget(String userPrompt) {
        if (userPrompt == null || userPrompt.isEmpty()) {
            return DEFAULT_CALORIE_TARGET;
        }
        Matcher matcher = CALORIE_PATTERN.matcher(userPrompt);
        if (!matcher.find()) {
            return DEFAULT_CALORIE_TARGET;
        }
        try {
            int target = Integer.parseInt(matcher.group(1));
            if (target <= 0) {
                // PromptBuilder 的 JSON Schema 里有个 "dailyCalorieTarget": 0 的占位值，
                // 万一它排在前面的用户 JSON 之前被匹配到，不能拿它当目标
                return DEFAULT_CALORIE_TARGET;
            }
            return Math.max(MIN_CALORIE_TARGET, Math.min(target, MAX_CALORIE_TARGET));
        } catch (NumberFormatException e) {
            log.warn("解析 Prompt 中的 dailyCalorieTarget 失败，回退为默认值 {}：{}",
                    DEFAULT_CALORIE_TARGET, e.getMessage());
            return DEFAULT_CALORIE_TARGET;
        }
    }

    /** 反查目标文案，只影响 summary 的措辞，解析失败不影响数据合法性。 */
    static String resolveGoalLabel(String userPrompt) {
        if (userPrompt == null || userPrompt.isEmpty()) {
            return DEFAULT_GOAL_LABEL;
        }
        Matcher matcher = GOAL_LABEL_PATTERN.matcher(userPrompt);
        if (!matcher.find()) {
            return DEFAULT_GOAL_LABEL;
        }
        String label = matcher.group(1);
        return label.isBlank() ? DEFAULT_GOAL_LABEL : label;
    }

    // ==================== 生成演示数据 ====================

    /** 用 Java 对象构造再序列化，保证输出一定是合法 JSON。 */
    private String buildJson(MockContext ctx) {
        MealPlanResult result = new MealPlanResult();
        result.setSummary("（演示数据）围绕「" + ctx.goalLabel() + "」目标生成的 "
                + ctx.days() + " 天饮食安排");
        // 刻意不设 dailyCalorieTarget。
        //
        // 这个字段的语义是「模型复述 Prompt 里给它的目标热量」，而 Mock 拿不到 UserProfile，
        // 填任何数字都是编的。曾经填 1600，结果被 saveResult 采信写进了数据库，
        // 出现「校验按 2155 判、落库存 1600」的两套标准。
        // 现在下游已统一改用 profile 实算值，这里保持 null 以表明「本字段无信息量」。
        result.setWarnings(new ArrayList<>(List.of(
                "当前未配置真实大模型 API Key，以下为结构合法的演示数据，营养数值不代表真实建议。",
                "本系统提供膳食建议仅供参考，不构成医疗诊断或治疗方案。")));

        int perMealTarget = ctx.dailyCalorieTarget() / DEFAULT_MEALS.size();

        List<MealPlanResult.Day> dayList = new ArrayList<>();
        for (int dayNo = 1; dayNo <= ctx.days(); dayNo++) {
            MealPlanResult.Day day = new MealPlanResult.Day();
            day.setDayNo(dayNo);
            day.setSummary("第 " + dayNo + " 天：" + ctx.goalLabel() + "配餐，清淡少油，保证优质蛋白摄入");

            List<MealPlanResult.Meal> meals = new ArrayList<>();
            for (String mealType : DEFAULT_MEALS) {
                meals.add(buildMeal(mealType, dayNo, perMealTarget));
            }
            day.setMeals(meals);
            dayList.add(day);
        }
        result.setDays(dayList);
        result.setShoppingList(new ArrayList<>());

        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("Mock 数据序列化失败", e);
        }
    }

    private MealPlanResult.Meal buildMeal(String mealType, int dayNo, int perMealTargetCalories) {
        MockRecipe recipe = pickRecipe(mealType, dayNo);
        int servings = servingsFor(Integer.parseInt(recipe.calories()), perMealTargetCalories);

        MealPlanResult.Meal meal = new MealPlanResult.Meal();
        meal.setMealType(mealType);
        meal.setRecipeId(recipe.id());
        meal.setRecipeName(recipe.name());
        meal.setReason("蛋白质充足且烹饪时间短，适合" + mealTypeLabel(mealType));
        meal.setServings(servings);

        // 食材严格来自该菜谱自己的用量表，不再从池子里按偏移量取 ——
        // 见 RECIPE_INGREDIENTS 的注释，按偏移取会让「番茄炒蛋」配出鸡胸肉
        List<MealPlanResult.IngredientItem> ingredients = new ArrayList<>();
        for (MockRecipeIngredient ref : recipeIngredients(recipe)) {
            MockIngredient master = INGREDIENT_BY_ID.get(ref.ingredientId());
            MealPlanResult.IngredientItem item = new MealPlanResult.IngredientItem();
            item.setIngredientId(master.id());
            item.setIngredientName(master.name());
            item.setUnit(master.unit());
            // 用量随份数走，否则「3 人份」的菜只买 1 人份的料
            item.setAmount(new BigDecimal(ref.amount()).multiply(BigDecimal.valueOf(servings)));
            item.setFromFridge(false);
            ingredients.add(item);
        }
        meal.setIngredients(ingredients);

        MealPlanResult.Nutrition nutrition = new MealPlanResult.Nutrition();
        nutrition.setCalories(scale(recipe.calories(), servings));
        nutrition.setProtein(scale(recipe.protein(), servings));
        nutrition.setFat(scale(recipe.fat(), servings));
        nutrition.setCarb(scale(recipe.carb(), servings));
        meal.setNutrition(nutrition);
        meal.setSourceIds(new ArrayList<>(List.of("RECIPE_" + recipe.id())));
        return meal;
    }

    /**
     * 取一道菜每份所需的食材。
     *
     * <p>直接 {@code get} 而不做 null 兜底：类加载时的静态块已经保证每个菜谱都有配置，
     * 这里再写一遍兜底只会让「配置漏了」变成静默返回空列表，
     * 而空食材列表意味着过敏原校验<b>没有东西可查</b> —— 安全校验被绕过，
     * 这是本项目明确不允许的降级方向。
     */
    static List<MockRecipeIngredient> recipeIngredients(MockRecipe recipe) {
        return RECIPE_INGREDIENTS.get(recipe.id());
    }

    /**
     * 按餐次挑菜，跨天轮换。
     *
     * <p>必须按餐次筛选 —— 否则会出现「早餐推荐蒜蓉炒菠菜」这种不合理的搭配。
     * 用 {@code dayNo - 1} 做下标是为了让第 1 天从池子里第一个开始，
     * 而不是第二个。
     */
    static MockRecipe pickRecipe(String mealType, int dayNo) {
        List<MockRecipe> candidates = RECIPES_BY_MEAL.get(mealType);
        if (candidates == null || candidates.isEmpty()) {
            return RECIPE_POOL.get(0);
        }
        return candidates.get((dayNo - 1) % candidates.size());
    }

    /**
     * 按单餐目标热量反推份数。
     *
     * <p>四舍五入到最近的整数份，至少 1 份。以 2155 千卡、3 餐为例，单餐目标约 718：
     * 番茄炒蛋 220 → 3 份；清蒸鸡胸肉 420 → 2 份；蒜蓉炒菠菜 120 → 6 份。
     * 各餐份数不同是正常的 —— 目的是让当天合计贴近目标，而不是每餐都一样多。
     */
    static int servingsFor(int baseCalories, int perMealTargetCalories) {
        if (baseCalories <= 0 || perMealTargetCalories <= 0) {
            return 1;
        }
        return Math.max(1, Math.round((float) perMealTargetCalories / baseCalories));
    }

    private static BigDecimal scale(String value, int servings) {
        return new BigDecimal(value).multiply(BigDecimal.valueOf(servings));
    }

    private static String mealTypeLabel(String mealType) {
        return switch (mealType) {
            case "breakfast" -> "早上";
            case "lunch" -> "午餐";
            case "dinner" -> "工作日晚餐";
            default -> "加餐";
        };
    }
}
