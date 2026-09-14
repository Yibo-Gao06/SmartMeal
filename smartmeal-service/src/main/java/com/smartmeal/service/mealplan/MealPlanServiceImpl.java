package com.smartmeal.service.mealplan;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartmeal.domain.dto.plan.MealPlanDetailVO;
import com.smartmeal.domain.dto.plan.MealPlanResult;
import com.smartmeal.domain.entity.MealPlan;
import com.smartmeal.domain.entity.MealPlanDay;
import com.smartmeal.domain.entity.MealPlanMeal;
import com.smartmeal.domain.entity.MealPlanMealItem;
import com.smartmeal.domain.enums.GoalEnum;
import com.smartmeal.domain.enums.PlanStatusEnum;
import com.smartmeal.repository.mapper.MealPlanDayMapper;
import com.smartmeal.repository.mapper.MealPlanMapper;
import com.smartmeal.repository.mapper.MealPlanMealItemMapper;
import com.smartmeal.repository.mapper.MealPlanMealMapper;
import com.smartmeal.service.user.bo.UserProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/** 膳食计划持久化实现。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MealPlanServiceImpl implements MealPlanService {

    private static final DateTimeFormatter PLAN_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final MealPlanMapper mealPlanMapper;
    private final MealPlanDayMapper dayMapper;
    private final MealPlanMealMapper mealMapper;
    private final MealPlanMealItemMapper mealItemMapper;

    @Override
    public MealPlan createGenerating(Long userId, UserProfile profile, String requestId, String modelName) {
        MealPlan plan = new MealPlan();
        plan.setUserId(userId);
        plan.setPlanNo(generatePlanNo());
        plan.setGoal(profile.getGoal());
        plan.setDailyCalorieTarget(profile.getDailyCalorieTarget());
        plan.setDays(profile.getDays());
        plan.setStatus(PlanStatusEnum.GENERATING.getCode());
        plan.setRequestId(requestId);
        plan.setModelName(modelName);
        mealPlanMapper.insert(plan);
        log.info("创建生成任务 planId={} planNo={} requestId={}", plan.getId(), plan.getPlanNo(), requestId);
        return plan;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveResult(Long planId, MealPlanResult result, UserProfile profile) {
        persistDetails(planId, result);

        MealPlan update = new MealPlan();
        update.setId(planId);
        update.setStatus(PlanStatusEnum.SUCCESS.getCode());
        // 热量目标一律以后端实算的 profile 为准，不采信模型回传的数字。
        //
        // result.getDailyCalorieTarget() 是模型对 Prompt 输入的复述，没有独立信息量，
        // 采信它会直接造成「两套标准」：PlanValidator 按 profile 的 2155 判偏离度，
        // 而这里把 1600 落进 t_meal_plan.daily_calorie_target —— 前端展示与校验依据对不上。
        // 这与项目约定「模型给的数字一律不信任，营养合计后端重算」是一致的。
        update.setDailyCalorieTarget(profile.getDailyCalorieTarget());
        update.setWarnings(joinWarnings(result.getWarnings()));
        mealPlanMapper.updateById(update);
        log.info("计划落库完成 planId={} 天数={} 警告={}", planId,
                result.getDays() == null ? 0 : result.getDays().size(),
                result.getWarnings() == null ? 0 : result.getWarnings().size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markFallback(Long planId, MealPlanResult result, UserProfile profile) {
        persistDetails(planId, result);

        MealPlan update = new MealPlan();
        update.setId(planId);
        update.setStatus(PlanStatusEnum.FALLBACK.getCode());
        update.setWarnings(joinWarnings(result.getWarnings()));
        mealPlanMapper.updateById(update);
        log.warn("计划已降级为模板 planId={}", planId);
    }

    @Override
    public void markFailed(Long planId, String errorMsg) {
        MealPlan update = new MealPlan();
        update.setId(planId);
        update.setStatus(PlanStatusEnum.FAILED.getCode());
        // 字段有长度限制，截断避免插入报错
        update.setErrorMsg(errorMsg == null ? null
                : errorMsg.substring(0, Math.min(errorMsg.length(), 1000)));
        mealPlanMapper.updateById(update);
        log.warn("计划生成失败 planId={} err={}", planId, errorMsg);
    }

    @Override
    public MealPlan findByRequestId(String requestId) {
        return mealPlanMapper.selectOne(Wrappers.<MealPlan>lambdaQuery()
                .eq(MealPlan::getRequestId, requestId)
                .orderByDesc(MealPlan::getId)
                .last("limit 1"));
    }

    @Override
    public MealPlan findById(Long planId) {
        return mealPlanMapper.selectById(planId);
    }

    /**
     * 组装计划详情。
     *
     * <p>关键在于<b>三次批量查询 + 内存组装</b>，而不是按层级逐个查。
     * 后者是典型的 N+1：7 天各查一次餐次、每餐再查一次食材，一共 7 + 21 + 42 ≈ 70 次往返。
     * 这里先取出全部天，再用 {@code in} 一次捞出这些天底下的所有餐次，
     * 再用一次 {@code in} 捞出所有食材，总共 3 次查询。
     */
    @Override
    public MealPlanDetailVO findDetail(Long planId) {
        MealPlan plan = mealPlanMapper.selectById(planId);
        if (plan == null) {
            return null;
        }

        MealPlanDetailVO vo = new MealPlanDetailVO();
        vo.setPlanId(plan.getId());
        vo.setPlanNo(plan.getPlanNo());
        vo.setGoal(plan.getGoal());
        vo.setGoalLabel(GoalEnum.of(plan.getGoal()).getLabel());
        vo.setDailyCalorieTarget(plan.getDailyCalorieTarget());
        vo.setDays(plan.getDays());
        vo.setStatus(plan.getStatus());
        vo.setModelName(plan.getModelName());
        vo.setErrorMsg(plan.getErrorMsg());
        vo.setCreateTime(plan.getCreateTime());
        vo.setWarnings(splitWarnings(plan.getWarnings()));

        List<MealPlanDay> days = dayMapper.selectList(Wrappers.<MealPlanDay>lambdaQuery()
                .eq(MealPlanDay::getPlanId, planId)
                .orderByAsc(MealPlanDay::getDayNo));
        if (days.isEmpty()) {
            // 生成中或生成失败的计划就是没有明细，返回空壳让前端显示对应状态
            vo.setTotalCalories(BigDecimal.ZERO);
            return vo;
        }

        List<Long> dayIds = days.stream().map(MealPlanDay::getId).toList();
        List<MealPlanMeal> meals = mealMapper.selectList(Wrappers.<MealPlanMeal>lambdaQuery()
                .in(MealPlanMeal::getPlanDayId, dayIds)
                .orderByAsc(MealPlanMeal::getId));

        List<Long> mealIds = meals.stream().map(MealPlanMeal::getId).toList();
        List<MealPlanMealItem> items = mealIds.isEmpty()
                ? List.of()
                : mealItemMapper.selectList(Wrappers.<MealPlanMealItem>lambdaQuery()
                        .in(MealPlanMealItem::getMealId, mealIds)
                        .orderByAsc(MealPlanMealItem::getId));

        Map<Long, List<MealPlanMealItem>> itemsByMeal = items.stream()
                .collect(Collectors.groupingBy(MealPlanMealItem::getMealId));
        Map<Long, List<MealPlanMeal>> mealsByDay = meals.stream()
                .collect(Collectors.groupingBy(MealPlanMeal::getPlanDayId));

        BigDecimal grandTotal = BigDecimal.ZERO;
        List<MealPlanDetailVO.DayVO> dayVOs = new ArrayList<>(days.size());
        for (MealPlanDay day : days) {
            MealPlanDetailVO.DayVO dayVO = new MealPlanDetailVO.DayVO();
            dayVO.setDayNo(day.getDayNo());
            dayVO.setPlanDate(day.getPlanDate());
            dayVO.setSummary(day.getSummary());
            dayVO.setTotalCalories(day.getTotalCalories());
            dayVO.setTotalProtein(day.getTotalProtein());
            dayVO.setTotalFat(day.getTotalFat());
            dayVO.setTotalCarb(day.getTotalCarb());
            if (day.getTotalCalories() != null) {
                grandTotal = grandTotal.add(day.getTotalCalories());
            }

            List<MealPlanDetailVO.MealVO> mealVOs = new ArrayList<>();
            for (MealPlanMeal meal : mealsByDay.getOrDefault(day.getId(), List.of())) {
                MealPlanDetailVO.MealVO mealVO = new MealPlanDetailVO.MealVO();
                mealVO.setMealId(meal.getId());
                mealVO.setMealType(meal.getMealType());
                mealVO.setMealTypeLabel(mealTypeLabel(meal.getMealType()));
                mealVO.setRecipeId(meal.getRecipeId());
                mealVO.setRecipeName(meal.getRecipeName());
                mealVO.setServings(meal.getServings());
                mealVO.setCalories(meal.getCalories());
                mealVO.setProtein(meal.getProtein());
                mealVO.setFat(meal.getFat());
                mealVO.setCarb(meal.getCarb());
                mealVO.setReason(meal.getReason());

                List<MealPlanDetailVO.ItemVO> itemVOs = new ArrayList<>();
                for (MealPlanMealItem item : itemsByMeal.getOrDefault(meal.getId(), List.of())) {
                    MealPlanDetailVO.ItemVO itemVO = new MealPlanDetailVO.ItemVO();
                    itemVO.setIngredientId(item.getIngredientId());
                    itemVO.setIngredientName(item.getIngredientName());
                    itemVO.setAmount(item.getAmount());
                    itemVO.setUnit(item.getUnit());
                    itemVOs.add(itemVO);
                }
                mealVO.setIngredients(itemVOs);
                mealVOs.add(mealVO);
            }
            dayVO.setMeals(mealVOs);
            dayVOs.add(dayVO);
        }

        vo.setDayList(dayVOs);
        vo.setTotalCalories(grandTotal);
        return vo;
    }

    @Override
    public MealPlanDetailVO findLatestByUser(Long userId) {
        MealPlan latest = mealPlanMapper.selectOne(Wrappers.<MealPlan>lambdaQuery()
                .eq(MealPlan::getUserId, userId)
                .orderByDesc(MealPlan::getId)
                .last("limit 1"));
        return latest == null ? null : findDetail(latest.getId());
    }

    /**
     * 餐次编码转中文。
     *
     * <p>抽成包级静态方法是为了能直接测 —— 前端拿到的是中文，
     * 如果这里漏了一个分支，页面上就会出现裸露的 {@code brunch} 之类的编码。
     */
    static String mealTypeLabel(String mealType) {
        if (mealType == null || mealType.isBlank()) {
            return "";
        }
        return switch (mealType) {
            case "breakfast" -> "早餐";
            case "lunch" -> "午餐";
            case "dinner" -> "晚餐";
            case "snack" -> "加餐";
            default -> mealType;
        };
    }

    /**
     * 警告列表转存文本。
     *
     * <p>用换行分隔而不是序列化成 JSON：这些警告是我们自己拼的短句，本身不含换行，
     * 存成纯文本在数据库里直接可读，排查问题时不用先解一层 JSON。
     *
     * <p>空列表返回 {@code null} 而不是空串，避免库里堆积一堆无意义的空字符串，
     * 查询时也能用 {@code warnings IS NOT NULL} 直接筛出「有警告」的记录。
     */
    static String joinWarnings(List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return null;
        }
        String joined = warnings.stream()
                .filter(w -> w != null && !w.isBlank())
                .collect(Collectors.joining("\n"));
        return joined.isBlank() ? null : joined;
    }

    /** 与 {@link #joinWarnings} 对称，把库里的文本还原成列表供前端渲染。 */
    static List<String> splitWarnings(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return text.lines().filter(line -> !line.isBlank()).toList();
    }

    @Override
    public void updateTokenUsage(Long planId, Integer promptTokens, Integer completionTokens,
                                 Integer totalTokens) {
        MealPlan update = new MealPlan();
        update.setId(planId);
        update.setPromptTokens(promptTokens);
        update.setCompletionTokens(completionTokens);
        update.setTotalTokens(totalTokens);
        mealPlanMapper.updateById(update);
    }

    /** 逐层写入 天 → 餐 → 食材，并顺带汇总每天的营养合计。 */
    private void persistDetails(Long planId, MealPlanResult result) {
        if (result.getDays() == null) {
            return;
        }
        LocalDate today = LocalDate.now();

        for (MealPlanResult.Day day : result.getDays()) {
            int dayNo = day.getDayNo() == null ? 1 : day.getDayNo();

            MealPlanDay dayEntity = new MealPlanDay();
            dayEntity.setPlanId(planId);
            dayEntity.setDayNo(dayNo);
            dayEntity.setPlanDate(today.plusDays(dayNo - 1L));
            dayEntity.setSummary(day.getSummary());
            // 后端自己再算一遍合计，不信任模型给的汇总值
            dayEntity.setTotalCalories(sum(day, MealPlanResult.Nutrition::getCalories));
            dayEntity.setTotalProtein(sum(day, MealPlanResult.Nutrition::getProtein));
            dayEntity.setTotalFat(sum(day, MealPlanResult.Nutrition::getFat));
            dayEntity.setTotalCarb(sum(day, MealPlanResult.Nutrition::getCarb));
            dayMapper.insert(dayEntity);

            if (day.getMeals() == null) {
                continue;
            }
            for (MealPlanResult.Meal meal : day.getMeals()) {
                MealPlanMeal mealEntity = new MealPlanMeal();
                mealEntity.setPlanDayId(dayEntity.getId());
                mealEntity.setMealType(meal.getMealType());
                mealEntity.setRecipeId(meal.getRecipeId());
                mealEntity.setRecipeName(meal.getRecipeName());
                mealEntity.setServings(meal.getServings() == null ? 1 : meal.getServings());
                mealEntity.setReason(meal.getReason());
                mealEntity.setSourceIds(meal.getSourceIds() == null ? null
                        : String.join(",", meal.getSourceIds()));
                if (meal.getNutrition() != null) {
                    mealEntity.setCalories(meal.getNutrition().getCalories());
                    mealEntity.setProtein(meal.getNutrition().getProtein());
                    mealEntity.setFat(meal.getNutrition().getFat());
                    mealEntity.setCarb(meal.getNutrition().getCarb());
                }
                mealMapper.insert(mealEntity);

                if (meal.getIngredients() == null) {
                    continue;
                }
                for (MealPlanResult.IngredientItem item : meal.getIngredients()) {
                    MealPlanMealItem itemEntity = new MealPlanMealItem();
                    itemEntity.setMealId(mealEntity.getId());
                    itemEntity.setIngredientId(item.getIngredientId());
                    itemEntity.setIngredientName(item.getIngredientName());
                    itemEntity.setAmount(item.getAmount());
                    itemEntity.setUnit(item.getUnit());
                    itemEntity.setSubstituteIngredientId(item.getSubstituteIngredientId());
                    mealItemMapper.insert(itemEntity);
                }
            }
        }
    }

    private BigDecimal sum(MealPlanResult.Day day, java.util.function.Function<MealPlanResult.Nutrition,
            BigDecimal> getter) {
        if (day.getMeals() == null) {
            return BigDecimal.ZERO;
        }
        return day.getMeals().stream()
                .map(MealPlanResult.Meal::getNutrition)
                .filter(java.util.Objects::nonNull)
                .map(getter)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 计划号：时间戳 + 4 位随机数。生产环境建议换成「日期 + 分库分表号 + 序列」。
     *
     * <p>这里必须用 {@link LocalDateTime} 而不是 {@link LocalDate}：
     * 格式串 {@code yyyyMMddHHmmss} 含时分秒，而 {@code LocalDate} 没有 HourOfDay 字段，
     * 拿去格式化会直接抛 {@code UnsupportedTemporalTypeException}。
     *
     * <p>抽成静态包级方法是为了能直接被测试覆盖 —— 这个 bug 就是线上真实踩到后才发现的，
     * 靠测试钉住，防止哪次重构又把 LocalDateTime 改回 LocalDate。
     */
    static String generatePlanNo() {
        return "MP" + LocalDateTime.now().format(PLAN_NO_FORMAT)
                + ThreadLocalRandom.current().nextInt(1000, 10000);
    }

    /** 查询某计划的完整明细，供前端渲染。 */
    public List<MealPlanDay> listDays(Long planId) {
        return dayMapper.selectList(Wrappers.<MealPlanDay>lambdaQuery()
                .eq(MealPlanDay::getPlanId, planId)
                .orderByAsc(MealPlanDay::getDayNo));
    }
}
