package com.smartmeal.service.user;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartmeal.common.cache.CacheService;
import com.smartmeal.common.constant.CommonConstants;
import com.smartmeal.common.exception.BusinessException;
import com.smartmeal.common.result.ResultCode;
import com.smartmeal.domain.dto.MealPlanRequest;
import com.smartmeal.domain.entity.AllergenIngredient;
import com.smartmeal.domain.entity.Ingredient;
import com.smartmeal.domain.entity.User;
import com.smartmeal.domain.entity.UserAllergy;
import com.smartmeal.domain.entity.UserFridgeIngredient;
import com.smartmeal.repository.mapper.AllergenIngredientMapper;
import com.smartmeal.repository.mapper.IngredientMapper;
import com.smartmeal.repository.mapper.UserAllergyMapper;
import com.smartmeal.repository.mapper.UserFridgeIngredientMapper;
import com.smartmeal.repository.mapper.UserMapper;
import com.smartmeal.service.user.bo.UserProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 用户画像服务实现。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {

    private static final Duration PROFILE_TTL = Duration.ofMinutes(30);

    private final UserMapper userMapper;
    private final UserAllergyMapper userAllergyMapper;
    private final UserFridgeIngredientMapper fridgeMapper;
    private final AllergenIngredientMapper allergenIngredientMapper;
    private final IngredientMapper ingredientMapper;
    private final NutritionCalculator nutritionCalculator;
    private final CacheService cacheService;

    @Override
    public UserProfile buildProfile(Long userId, MealPlanRequest request) {
        User user = userMapper.selectById(userId);
        BusinessException.throwIf(user == null, ResultCode.USER_NOT_FOUND);

        UserProfile profile = new UserProfile();
        profile.setUserId(userId);

        // 请求里的值优先（用户可能只想试算，不想改库里的档案）
        profile.setGoal(request.getGoal() != null ? request.getGoal() : user.getGoal());
        profile.setHeightCm(request.getHeightCm() != null ? request.getHeightCm() : user.getHeightCm());
        profile.setWeightKg(request.getWeightKg() != null ? request.getWeightKg() : user.getWeightKg());
        // 年龄优先用请求里的；请求没带就从出生日期推算。
        // 少了这一步，age 恒为 null，NutritionCalculator 会走兜底分支，
        // BMI/BMR/TDEE 全是 0，热量目标退化成硬编码的 1800。
        profile.setAge(request.getAge() != null ? request.getAge() : calcAge(user.getBirthDate()));
        profile.setGender(request.getGender() != null ? request.getGender() : user.getGender());
        profile.setActivityLevel(request.getActivityLevel() != null
                ? request.getActivityLevel() : user.getActivityLevel());
        profile.setWeeklyBudget(request.getWeeklyBudget() != null
                ? request.getWeeklyBudget() : user.getWeeklyBudget());
        profile.setTastePreference(request.getTastePreference());

        profile.setDays(request.getDays() == null ? 7 : request.getDays());
        profile.setMealsPerDay(request.getMealsPerDay() == null || request.getMealsPerDay().isEmpty()
                ? List.of("breakfast", "lunch", "dinner")
                : request.getMealsPerDay());

        // 过敏原：请求与库里的取并集，宁可多拦不可漏拦
        Set<String> allergenCodes = new LinkedHashSet<>();
        if (request.getAllergens() != null) {
            request.getAllergens().stream().filter(s -> s != null && !s.isBlank())
                    .map(String::trim).forEach(allergenCodes::add);
        }
        List<UserAllergy> storedAllergies = userAllergyMapper.selectList(
                Wrappers.<UserAllergy>lambdaQuery().eq(UserAllergy::getUserId, userId));
        storedAllergies.stream()
                .map(UserAllergy::getAllergenCode)
                .filter(code -> code != null && !code.isBlank())
                .forEach(allergenCodes::add);
        profile.setAllergenCodes(allergenCodes);
        profile.setAllergenIngredientNames(expandAllergens(allergenCodes));

        // 冰箱食材：请求里的优先，否则读库
        List<String> fridge = request.getFridgeIngredients() == null
                ? Collections.emptyList()
                : request.getFridgeIngredients().stream()
                        .filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
        if (fridge.isEmpty()) {
            fridge = loadFridgeNames(userId);
        }
        profile.setFridgeIngredients(new ArrayList<>(fridge));
        // 数量明细始终从库里取：请求体只传名称，拿不到数量，
        // 但购物清单扣减需要真实数量，否则会把「冰箱有 300g 番茄」当成 1g。
        profile.setFridgeStock(loadFridgeStock(userId));

        nutritionCalculator.fill(profile);

        log.info("画像构建完成 userId={} goal={} 目标热量={}kcal 过敏原={} 冰箱食材={}",
                userId, profile.getGoal(), profile.getDailyCalorieTarget(),
                profile.getAllergenCodes(), profile.getFridgeIngredients());
        return profile;
    }

    @Override
    public UserProfile getProfile(Long userId) {
        String key = CommonConstants.CACHE_USER_PROFILE + userId;
        return cacheService.getOrLoad(key, UserProfile.class, PROFILE_TTL, () -> {
            User user = userMapper.selectById(userId);
            BusinessException.throwIf(user == null, ResultCode.USER_NOT_FOUND);

            UserProfile profile = new UserProfile();
            profile.setUserId(userId);
            profile.setGoal(user.getGoal());
            profile.setHeightCm(user.getHeightCm());
            profile.setWeightKg(user.getWeightKg());
            profile.setAge(calcAge(user.getBirthDate()));
            profile.setGender(user.getGender());
            profile.setActivityLevel(user.getActivityLevel());
            profile.setWeeklyBudget(user.getWeeklyBudget());
            profile.setFridgeIngredients(new ArrayList<>(loadFridgeNames(userId)));
            profile.setFridgeStock(loadFridgeStock(userId));

            Set<String> codes = userAllergyMapper.selectList(
                            Wrappers.<UserAllergy>lambdaQuery().eq(UserAllergy::getUserId, userId))
                    .stream().map(UserAllergy::getAllergenCode)
                    .filter(c -> c != null && !c.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            profile.setAllergenCodes(codes);
            profile.setAllergenIngredientNames(expandAllergens(codes));
            nutritionCalculator.fill(profile);
            return profile;
        });
    }

    /**
     * 把过敏原编码展开成食材 ID 集合。
     *
     * <p>这一步是整个安全链路的关键：不做展开，「花生」就匹配不到「花生油」。
     */
    private Map<Long, String> expandAllergens(Set<String> allergenCodes) {
        if (allergenCodes.isEmpty()) {
            return Collections.emptyMap();
        }
        List<AllergenIngredient> relations = allergenIngredientMapper.selectList(
                Wrappers.<AllergenIngredient>lambdaQuery()
                        .in(AllergenIngredient::getAllergenCode, allergenCodes));
        if (relations.isEmpty()) {
            // 注意：这里只是拿不到食材 ID，校验并不会因此停摆 ——
            // PlanValidator 会改走「名称兜底」并在报告里给用户一条警告。
            log.warn("过敏原 {} 在 t_allergen_ingredient 中没有映射记录，将退化为按名称兜底匹配",
                    allergenCodes);
            return Collections.emptyMap();
        }
        List<Long> ingredientIds = relations.stream()
                .map(AllergenIngredient::getIngredientId).distinct().toList();
        return ingredientMapper.selectBatchIds(ingredientIds).stream()
                .collect(Collectors.toMap(Ingredient::getId, Ingredient::getName,
                        (a, b) -> a, java.util.LinkedHashMap::new));
    }

    /**
     * 由出生日期推算年龄。
     *
     * <p>表里存的是 {@code birth_date} 而不是年龄 —— 年龄是会变的，存下来第二年就错了。
     * 用 {@link Period} 而不是「年份相减」，是为了处理「生日还没到」的情况：
     * 2000-05-20 出生的人，在 2026-03-01 应该算 25 岁而不是 26 岁。
     *
     * @return 出生日期为空时返回 null，让 {@code NutritionCalculator} 走兜底分支
     */
    static Integer calcAge(LocalDate birthDate) {
        return calcAge(birthDate, LocalDate.now());
    }

    /** 便于测试注入「今天」，避免测试结果随运行日期漂移。 */
    static Integer calcAge(LocalDate birthDate, LocalDate today) {
        if (birthDate == null) {
            return null;
        }
        return Period.between(birthDate, today).getYears();
    }

    private List<String> loadFridgeNames(Long userId) {
        return loadFridgeStock(userId).stream()
                .map(UserProfile.FridgeItem::name)
                .toList();
    }

    /**
     * 读出冰箱食材的<b>数量</b>明细。
     *
     * <p>过期食材必须排除：过期了就不能算作「已有」，否则会少买，用户到手发现做不成菜。
     */
    private List<UserProfile.FridgeItem> loadFridgeStock(Long userId) {
        LocalDate today = LocalDate.now();
        return fridgeMapper.selectList(Wrappers.<UserFridgeIngredient>lambdaQuery()
                        .eq(UserFridgeIngredient::getUserId, userId))
                .stream()
                .filter(item -> item.getExpireDate() == null || !item.getExpireDate().isBefore(today))
                .filter(item -> item.getIngredientName() != null && !item.getIngredientName().isBlank())
                .map(item -> new UserProfile.FridgeItem(
                        item.getIngredientName(), item.getAmount(), item.getUnit()))
                .toList();
    }

    /** 未使用，保留给「按食材名反查 ID」的场景。 */
    @SuppressWarnings("unused")
    private Map<String, Ingredient> indexByName(List<Ingredient> ingredients) {
        return ingredients.stream().collect(Collectors.toMap(Ingredient::getName, Function.identity(),
                (a, b) -> a));
    }

    /** 便捷方法：把 BigDecimal 空值转 0。 */
    static BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
