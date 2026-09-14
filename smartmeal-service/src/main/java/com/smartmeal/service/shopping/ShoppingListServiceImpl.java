package com.smartmeal.service.shopping;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartmeal.common.exception.BusinessException;
import com.smartmeal.common.result.ResultCode;
import com.smartmeal.domain.dto.plan.MealPlanResult;
import com.smartmeal.domain.entity.Ingredient;
import com.smartmeal.domain.entity.ProductSku;
import com.smartmeal.domain.entity.ShoppingList;
import com.smartmeal.domain.entity.ShoppingListItem;
import com.smartmeal.repository.mapper.IngredientMapper;
import com.smartmeal.repository.mapper.ProductSkuMapper;
import com.smartmeal.repository.mapper.ShoppingListItemMapper;
import com.smartmeal.repository.mapper.ShoppingListMapper;
import com.smartmeal.service.user.bo.UserProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 购物清单生成。
 *
 * <p>整条链路分四步，每一步都对应一个真实会踩的坑：
 * <ol>
 *   <li><b>聚合</b>：同一食材在多天多餐重复出现，必须按「食材 + 单位」归并求和。
 *       单位不同的不能直接相加（g 和 piece），按单位拆成两条明细。</li>
 *   <li><b>扣减冰箱</b>：用户已有的不能重复买。这一步要用归一化后的名称匹配，
 *       否则「西红柿」和「番茄」会被当成两种东西。</li>
 *   <li><b>SKU 匹配</b>：按 ingredient_id 精确匹配优先，再按价格升序取有库存的最便宜 SKU。</li>
 *   <li><b>整包换算</b>：这是最容易被忽略的一步。需要 400g 番茄、SKU 是 500g/份，
 *       不能买 0.8 份 —— 必须向上取整买 1 份。用 ceil 而不是四舍五入。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShoppingListServiceImpl implements ShoppingListService {

    private final ShoppingListMapper shoppingListMapper;
    private final ShoppingListItemMapper shoppingListItemMapper;
    private final IngredientMapper ingredientMapper;
    private final ProductSkuMapper productSkuMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long generate(Long userId, Long planId, MealPlanResult result, UserProfile profile) {
        // ---- 第一步：跨天跨餐聚合食材 ----
        Map<String, Aggregated> aggregated = aggregate(result);
        log.info("购物清单聚合完成 planId={} 聚合后食材种类={}", planId, aggregated.size());

        // ---- 第二步：扣减冰箱已有 ----
        Map<String, BigDecimal> fridgeStock = buildFridgeStock(profile, aggregated.keySet());

        ShoppingList list = new ShoppingList();
        list.setUserId(userId);
        list.setPlanId(planId);
        list.setTitle("一周膳食计划购物清单");
        list.setStatus("DRAFT");
        list.setTotalPrice(BigDecimal.ZERO);
        shoppingListMapper.insert(list);

        BigDecimal totalPrice = BigDecimal.ZERO;
        List<ShoppingListItem> items = new ArrayList<>();

        for (Aggregated agg : aggregated.values()) {
            ShoppingListItem item = new ShoppingListItem();
            item.setListId(list.getId());
            item.setIngredientId(agg.ingredientId);
            item.setIngredientName(agg.ingredientName);
            item.setUnit(agg.unit);
            item.setRequiredAmount(scale(agg.amount));

            BigDecimal fridgeAmount = fridgeStock.getOrDefault(agg.key(), BigDecimal.ZERO);
            item.setFridgeAmount(scale(fridgeAmount));

            BigDecimal needBuy = agg.amount.subtract(fridgeAmount).max(BigDecimal.ZERO);
            item.setNeedBuyAmount(scale(needBuy));

            if (needBuy.compareTo(BigDecimal.ZERO) <= 0) {
                // 冰箱够了，不需要买
                item.setQuantity(0);
                item.setPrice(BigDecimal.ZERO);
                item.setStatus("AVAILABLE");
                items.add(item);
                continue;
            }

            // ---- 第三、四步：匹配 SKU 并换算购买份数 ----
            matchSku(item, agg, needBuy);
            totalPrice = totalPrice.add(
                    item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            items.add(item);
        }

        items.forEach(shoppingListItemMapper::insert);

        ShoppingList update = new ShoppingList();
        update.setId(list.getId());
        update.setTotalPrice(scale(totalPrice));
        shoppingListMapper.updateById(update);

        log.info("购物清单生成完成 listId={} 明细数={} 预估总价={}", list.getId(), items.size(), totalPrice);
        return list.getId();
    }

    @Override
    public ShoppingList getById(Long listId) {
        ShoppingList list = shoppingListMapper.selectById(listId);
        BusinessException.throwIf(list == null, ResultCode.NOT_FOUND, "购物清单不存在");
        return list;
    }

    /**
     * 按计划 ID 查清单。
     *
     * <p>与 {@link #getById} 的行为刻意不同：这里<b>找不到就返回 null 而不抛异常</b>。
     * 原因是语义不同 —— 传一个不存在的 listId 是调用方搞错了，
     * 而「计划已生成但清单还没轮到」是完全正常的中间状态，
     * 前端此时应该展示「清单生成中」而不是收到一个错误。
     */
    @Override
    public ShoppingList getByPlanId(Long planId) {
        return shoppingListMapper.selectOne(Wrappers.<ShoppingList>lambdaQuery()
                .eq(ShoppingList::getPlanId, planId)
                .orderByDesc(ShoppingList::getId)
                .last("limit 1"));
    }

    @Override
    public List<ShoppingListItem> listItems(Long listId) {
        return shoppingListItemMapper.selectList(Wrappers.<ShoppingListItem>lambdaQuery()
                .eq(ShoppingListItem::getListId, listId));
    }

    // ==================== 内部实现 ====================

    /** 聚合键：食材 ID + 单位。单位不同不可相加。 */
    private record Aggregated(String key, Long ingredientId, String ingredientName, String unit,
                              BigDecimal amount) {
    }

    private Map<String, Aggregated> aggregate(MealPlanResult result) {
        Map<String, Aggregated> map = new LinkedHashMap<>();
        if (result.getDays() == null) {
            return map;
        }
        for (MealPlanResult.Day day : result.getDays()) {
            if (day.getMeals() == null) {
                continue;
            }
            for (MealPlanResult.Meal meal : day.getMeals()) {
                if (meal.getIngredients() == null) {
                    continue;
                }
                for (MealPlanResult.IngredientItem item : meal.getIngredients()) {
                    BigDecimal amount = item.getAmount() == null ? BigDecimal.ZERO : item.getAmount();
                    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                        continue;
                    }
                    String unit = item.getUnit() == null ? "g" : item.getUnit().trim();
                    String key = item.getIngredientId() + "|" + unit;

                    Aggregated existing = map.get(key);
                    if (existing == null) {
                        map.put(key, new Aggregated(key, item.getIngredientId(),
                                item.getIngredientName(), unit, amount));
                    } else {
                        map.put(key, new Aggregated(key, existing.ingredientId(),
                                existing.ingredientName(), unit, existing.amount().add(amount)));
                    }
                }
            }
        }
        return map;
    }

    /**
     * 把冰箱食材换算成「聚合键 → 已有数量」。
     *
     * <p>冰箱里的食材是用户手填的名称，需要先归一到食材 ID 才能和聚合结果对齐。
     * 归一顺序：精确名称 → 别名 → 匹配不到就放弃（宁可不扣，也不能错扣）。
     *
     * <p>扣减数量优先取 {@code fridgeStock} 里的真实数量。
     * 只有在拿不到数量时（比如请求体只传了名称），才退化成「扣 1 个基准单位」的保守策略 ——
     * 少扣最多是让用户多买一点，错扣则会让用户该买的没买。
     */
    private Map<String, BigDecimal> buildFridgeStock(UserProfile profile, Iterable<String> aggKeys) {
        Map<String, BigDecimal> stock = new LinkedHashMap<>();
        if (profile.getFridgeIngredients() == null || profile.getFridgeIngredients().isEmpty()) {
            return stock;
        }

        List<Ingredient> all = ingredientMapper.selectList(
                Wrappers.<Ingredient>lambdaQuery().eq(Ingredient::getStatus, 1));
        Map<String, Ingredient> byName = new LinkedHashMap<>();
        for (Ingredient ingredient : all) {
            byName.put(normalize(ingredient.getName()), ingredient);
            if (ingredient.getAlias() != null && !ingredient.getAlias().isBlank()) {
                for (String alias : ingredient.getAlias().split("[,，/]")) {
                    if (!alias.isBlank()) {
                        byName.putIfAbsent(normalize(alias), ingredient);
                    }
                }
            }
        }

        // 名称 → 真实数量，用于精确扣减
        Map<String, UserProfile.FridgeItem> amountByName = new LinkedHashMap<>();
        if (profile.getFridgeStock() != null) {
            for (UserProfile.FridgeItem item : profile.getFridgeStock()) {
                if (item != null && item.name() != null) {
                    amountByName.put(normalize(item.name()), item);
                }
            }
        }

        for (String raw : profile.getFridgeIngredients()) {
            Ingredient matched = byName.get(normalize(raw));
            if (matched == null) {
                log.debug("冰箱食材「{}」未在食材库中匹配到，跳过扣减", raw);
                continue;
            }
            UserProfile.FridgeItem owned = amountByName.get(normalize(matched.getName()));
            for (String key : aggKeys) {
                // key 形如 "1001|g"
                if (key.startsWith(matched.getId() + "|")) {
                    String unit = key.substring(key.indexOf('|') + 1);
                    BigDecimal deduct;
                    if (owned == null || owned.amount() == null) {
                        // 只知道有、不知道有多少（请求体只传了名称）：按 1 个基准单位保守扣
                        deduct = BigDecimal.ONE;
                    } else if (owned.unit() == null || owned.unit().equalsIgnoreCase(unit)) {
                        // 数量与单位都明确：如实扣减
                        deduct = owned.amount();
                    } else {
                        // 有数量但单位对不上（冰箱记 4 枚、清单要 6g）：无法换算，不扣。
                        // 少扣最多是让用户多买一点；乱扣会让用户该买的没买，直接做不成菜。
                        log.debug("冰箱 {} 单位 {} 与清单单位 {} 不一致，跳过扣减",
                                matched.getName(), owned.unit(), unit);
                        continue;
                    }
                    stock.merge(key, deduct, BigDecimal::add);
                    log.debug("冰箱扣减 {} unit={} 数量={}", matched.getName(), unit, deduct);
                }
            }
        }
        return stock;
    }

    /** 匹配 SKU 并计算购买份数。 */
    private void matchSku(ShoppingListItem item, Aggregated agg, BigDecimal needBuy) {
        if (agg.ingredientId == null) {
            item.setStatus("OUT_OF_STOCK");
            item.setQuantity(0);
            item.setPrice(BigDecimal.ZERO);
            return;
        }

        List<ProductSku> candidates = productSkuMapper.selectList(
                Wrappers.<ProductSku>lambdaQuery()
                        .eq(ProductSku::getIngredientId, agg.ingredientId)
                        .eq(ProductSku::getStatus, 1)
                        .orderByAsc(ProductSku::getPrice));

        ProductSku picked = candidates.stream()
                .filter(sku -> sku.getStock() != null && sku.getStock() > 0)
                .min(Comparator.comparing(ProductSku::getPrice))
                .orElse(null);

        if (picked == null) {
            // 无库存：标记缺货，并给出替代 SKU 建议（取最便宜的，哪怕是缺货的）
            item.setStatus("OUT_OF_STOCK");
            item.setQuantity(0);
            item.setPrice(BigDecimal.ZERO);
            candidates.stream().findFirst().ifPresent(alt -> item.setSubstituteSkuId(alt.getId()));
            log.warn("食材 {} 无可用 SKU", agg.ingredientName());
            return;
        }

        item.setSkuId(picked.getId());
        item.setSkuName(picked.getSkuName());
        item.setPrice(picked.getPrice());
        item.setStatus("AVAILABLE");

        BigDecimal conversion = picked.getConversionRate();
        if (conversion == null || conversion.compareTo(BigDecimal.ZERO) <= 0) {
            // 没配换算比例就按 1:1 处理，并留下告警，方便运营补数据
            log.warn("SKU {} 未配置 conversionRate，按 1:1 换算", picked.getSkuName());
        }

        item.setQuantity(packsNeeded(needBuy, conversion));

        if (item.getQuantity() > (picked.getStock() == null ? 0 : picked.getStock())) {
            item.setStatus("OUT_OF_STOCK");
            log.warn("SKU {} 库存不足 需要={} 库存={}", picked.getSkuName(),
                    item.getQuantity(), picked.getStock());
        }
    }

    /**
     * 把「需要多少」换算成「买几份」。
     *
     * <p><b>向上取整，不能四舍五入。</b>生鲜只能整包卖：需要 400g、规格 500g/份时，
     * 买 1 份；需要 1200g 时买 3 份（2.4 份取整为 3）。如果用 {@code HALF_UP}，
     * 400g 会算成 1 份（碰巧对），但 1200g 的 2.4 份会算成 2 份 —— <b>用户就不够用了</b>。
     *
     * <p>另外兜两层：换算率缺失按 1:1 处理；结果至少为 1（需要量再小也得买一整包）。
     *
     * <p>抽成包级静态方法是为了能被单元测试直接覆盖 —— 它是整条链路里最容易算错的一步。
     */
    static int packsNeeded(BigDecimal needBuy, BigDecimal conversionRate) {
        if (needBuy == null || needBuy.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        BigDecimal conversion = (conversionRate == null || conversionRate.compareTo(BigDecimal.ZERO) <= 0)
                ? BigDecimal.ONE
                : conversionRate;
        int packs = needBuy.divide(conversion, 0, RoundingMode.CEILING).intValue();
        return Math.max(packs, 1);
    }

    private String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase().replace(" ", "");
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }
}
