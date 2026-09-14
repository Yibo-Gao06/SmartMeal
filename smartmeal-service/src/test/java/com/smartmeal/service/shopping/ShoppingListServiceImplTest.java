package com.smartmeal.service.shopping;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ShoppingListServiceImpl} 的四步链路。
 *
 * <p>这个类是从「食谱」跨到「电商」的桥梁，也是整个项目里最容易被面试官追问的地方。
 *
 * <p><b>关于测试写法</b>：MyBatis-Plus 3.5.x 把条件参数的填充推迟到了 SQL 生成阶段，
 * 在只调用了 {@code eq(...)} 之后 {@code getParamNameValuePairs()} 仍是空 Map，
 * 所以 mock 无法从 Wrapper 里反推出 {@code ingredientId}。与其去反射框架内部，
 * 不如把最容易算错的那步（份数换算）抽成纯函数 {@link ShoppingListServiceImpl#packsNeeded} 直接测，
 * 其余用例则每个只涉及一种食材，让 mock 无需过滤也能正确返回。
 */
@ExtendWith(MockitoExtension.class)
class ShoppingListServiceImplTest {

    @Mock
    private ShoppingListMapper shoppingListMapper;
    @Mock
    private ShoppingListItemMapper shoppingListItemMapper;
    @Mock
    private IngredientMapper ingredientMapper;
    @Mock
    private ProductSkuMapper productSkuMapper;

    private ShoppingListServiceImpl service;

    private final List<Ingredient> ingredientTable = new ArrayList<>();
    private final List<ProductSku> skuTable = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ShoppingListServiceImpl(shoppingListMapper, shoppingListItemMapper,
                ingredientMapper, productSkuMapper);
        ingredientTable.clear();
        skuTable.clear();

        // insert 时回填自增主键，否则后续 item.setListId(list.getId()) 拿到 null
        lenient().doAnswer(inv -> {
            ShoppingList list = inv.getArgument(0);
            if (list.getId() == null) {
                list.setId(1L);
            }
            return 1;
        }).when(shoppingListMapper).insert(any(ShoppingList.class));

        // 冰箱名称归一需要全量食材
        lenient().when(ingredientMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(ingredientTable));

        // 每个用例只注册一种食材的 SKU，因此无需按 ingredientId 过滤
        lenient().when(productSkuMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(skuTable));
    }

    // ==================== 测试数据构造 ====================

    private void registerIngredient(long id, String name) {
        registerIngredient(id, name, null);
    }

    private void registerIngredient(long id, String name, String alias) {
        Ingredient ingredient = new Ingredient();
        ingredient.setId(id);
        ingredient.setName(name);
        ingredient.setAlias(alias);
        ingredient.setStatus(1);
        ingredientTable.add(ingredient);
    }

    private void registerSku(long skuId, long ingredientId, String skuName, String price,
                             int stock, BigDecimal conversionRate) {
        ProductSku sku = new ProductSku();
        sku.setId(skuId);
        sku.setIngredientId(ingredientId);
        sku.setSkuName(skuName);
        sku.setPrice(new BigDecimal(price));
        sku.setStock(stock);
        sku.setStatus(1);
        sku.setConversionRate(conversionRate);
        skuTable.add(sku);
    }

    /** 构造一份计划：把 (食材ID, 食材名, 数量, 单位) 依次放进不同天不同餐。 */
    private MealPlanResult planOf(Object[]... entries) {
        MealPlanResult result = new MealPlanResult();
        List<MealPlanResult.Day> days = new ArrayList<>();
        int dayNo = 1;
        for (Object[] entry : entries) {
            MealPlanResult.IngredientItem item = new MealPlanResult.IngredientItem();
            item.setIngredientId((Long) entry[0]);
            item.setIngredientName((String) entry[1]);
            item.setAmount(new BigDecimal(entry[2].toString()));
            item.setUnit((String) entry[3]);

            MealPlanResult.Meal meal = new MealPlanResult.Meal();
            meal.setMealType("lunch");
            meal.setRecipeId(5001L);
            meal.setRecipeName("测试菜");
            meal.setIngredients(new ArrayList<>(List.of(item)));

            MealPlanResult.Day day = new MealPlanResult.Day();
            day.setDayNo(dayNo++);
            day.setMeals(new ArrayList<>(List.of(meal)));
            days.add(day);
        }
        result.setDays(new ArrayList<>(days));
        return result;
    }

    private UserProfile profileOf() {
        UserProfile profile = new UserProfile();
        profile.setUserId(1L);
        return profile;
    }

    private List<ShoppingListItem> capturedItems(int expectedCount) {
        ArgumentCaptor<ShoppingListItem> captor = ArgumentCaptor.forClass(ShoppingListItem.class);
        verify(shoppingListItemMapper, times(expectedCount)).insert(captor.capture());
        return captor.getAllValues();
    }

    // ==================== 份数换算（纯函数，逐个边界钉死） ====================

    @Nested
    @DisplayName("packsNeeded：整包向上取整")
    class PacksNeededTest {

        private BigDecimal bd(String v) {
            return new BigDecimal(v);
        }

        @Test
        @DisplayName("★ 400g / 500g 规格 → 1 份（不能是 0.8 份）")
        void roundsUpHalfPackage() {
            assertEquals(1, ShoppingListServiceImpl.packsNeeded(bd("400"), bd("500")));
        }

        @Test
        @DisplayName("★ 1200g / 500g 规格 → 3 份（2.4 向上取整；若用四舍五入会得 2 份，用户就不够用）")
        void roundsUpAcrossPackages() {
            assertEquals(3, ShoppingListServiceImpl.packsNeeded(bd("1200"), bd("500")));
        }

        @Test
        @DisplayName("恰好整除时不多买")
        void exactMultiple() {
            assertEquals(2, ShoppingListServiceImpl.packsNeeded(bd("1000"), bd("500")));
            assertEquals(1, ShoppingListServiceImpl.packsNeeded(bd("500"), bd("500")));
        }

        @Test
        @DisplayName("需要量再小也至少买 1 份")
        void atLeastOnePack() {
            assertEquals(1, ShoppingListServiceImpl.packsNeeded(bd("1"), bd("500")));
            assertEquals(1, ShoppingListServiceImpl.packsNeeded(bd("0.1"), bd("500")));
        }

        @Test
        @DisplayName("换算率缺失或非法时按 1:1 处理")
        void missingConversionRate() {
            assertEquals(3, ShoppingListServiceImpl.packsNeeded(bd("3"), null));
            assertEquals(3, ShoppingListServiceImpl.packsNeeded(bd("3"), BigDecimal.ZERO));
            assertEquals(3, ShoppingListServiceImpl.packsNeeded(bd("3"), bd("-5")));
        }

        @Test
        @DisplayName("需要量非正数时返回 0，不产生购买")
        void nonPositiveNeed() {
            assertEquals(0, ShoppingListServiceImpl.packsNeeded(BigDecimal.ZERO, bd("500")));
            assertEquals(0, ShoppingListServiceImpl.packsNeeded(bd("-100"), bd("500")));
            assertEquals(0, ShoppingListServiceImpl.packsNeeded(null, bd("500")));
        }

        @Test
        @DisplayName("换算是按基准单位算的：2 枚鸡蛋 / 10 枚一盒 → 1 盒")
        void pieceUnitConversion() {
            assertEquals(1, ShoppingListServiceImpl.packsNeeded(bd("2"), bd("10")));
            assertEquals(1, ShoppingListServiceImpl.packsNeeded(bd("10"), bd("10")));
            assertEquals(2, ShoppingListServiceImpl.packsNeeded(bd("11"), bd("10")));
        }
    }

    // ==================== 端到端：聚合 / 匹配 / 扣减 ====================

    @Nested
    @DisplayName("generate：完整链路")
    class GenerateTest {

        @Test
        @DisplayName("跨天跨餐聚合：三次 200g 合成 600g，按 500g 规格买 2 份")
        void aggregatesAcrossDaysAndMeals() {
            registerIngredient(1001L, "番茄");
            registerSku(2001L, 1001L, "番茄 500g/盒", "5.50", 100, new BigDecimal("500"));

            service.generate(1L, 10L, planOf(
                    new Object[]{1001L, "番茄", "200", "g"},
                    new Object[]{1001L, "番茄", "200", "g"},
                    new Object[]{1001L, "番茄", "200", "g"}), profileOf());

            ShoppingListItem item = capturedItems(1).get(0);
            assertEquals(0, item.getRequiredAmount().compareTo(new BigDecimal("600.00")),
                    "三次 200g 应聚合为 600g");
            assertEquals(2, item.getQuantity(), "600 / 500 = 1.2，向上取整为 2");
        }

        @Test
        @DisplayName("单位隔离：同为番茄，g 和 piece 必须拆成两条明细")
        void differentUnitsAreNotMerged() {
            registerIngredient(1001L, "番茄");
            registerSku(2001L, 1001L, "番茄 500g/盒", "5.50", 100, new BigDecimal("500"));

            service.generate(1L, 10L, planOf(
                    new Object[]{1001L, "番茄", "400", "g"},
                    new Object[]{1001L, "番茄", "2", "piece"}), profileOf());

            List<ShoppingListItem> items = capturedItems(2);
            assertEquals(2, items.size(), "g 和 piece 量纲不同，不能相加成一条");
            assertTrue(items.stream().anyMatch(i -> "g".equals(i.getUnit())));
            assertTrue(items.stream().anyMatch(i -> "piece".equals(i.getUnit())));
        }

        @Test
        @DisplayName("数量为 0 或负数的食材不进入清单")
        void skipsNonPositiveAmounts() {
            registerIngredient(1001L, "番茄");
            registerSku(2001L, 1001L, "番茄 500g/盒", "5.50", 100, new BigDecimal("500"));

            service.generate(1L, 10L, planOf(
                    new Object[]{1001L, "番茄", "0", "g"},
                    new Object[]{1001L, "番茄", "-5", "g"}), profileOf());

            verify(shoppingListItemMapper, never()).insert(any(ShoppingListItem.class));
        }

        @Test
        @DisplayName("SKU 匹配：多个可选项时取有库存里最便宜的那个")
        void picksCheapestAvailableSku() {
            registerIngredient(1001L, "番茄");
            registerSku(2001L, 1001L, "贵的 500g/盒", "9.90", 100, new BigDecimal("500"));
            registerSku(2002L, 1001L, "便宜的 500g/盒", "4.50", 100, new BigDecimal("500"));

            service.generate(1L, 10L, planOf(new Object[]{1001L, "番茄", "400", "g"}), profileOf());

            ShoppingListItem item = capturedItems(1).get(0);
            assertEquals(2002L, item.getSkuId(), "应选价格最低的");
            assertEquals("便宜的 500g/盒", item.getSkuName());
            assertEquals("AVAILABLE", item.getStatus());
        }

        @Test
        @DisplayName("SKU 匹配：最便宜的没库存时跳过它，选次便宜且有货的")
        void skipsOutOfStockSku() {
            registerIngredient(1001L, "番茄");
            registerSku(2001L, 1001L, "便宜的但缺货", "4.50", 0, new BigDecimal("500"));
            registerSku(2002L, 1001L, "稍贵但有货", "6.00", 100, new BigDecimal("500"));

            service.generate(1L, 10L, planOf(new Object[]{1001L, "番茄", "400", "g"}), profileOf());

            assertEquals(2002L, capturedItems(1).get(0).getSkuId());
        }

        @Test
        @DisplayName("SKU 匹配：完全没有可购买 SKU 时标记缺货")
        void marksOutOfStockWhenNoSku() {
            registerIngredient(1001L, "番茄");
            // 不注册任何 SKU

            service.generate(1L, 10L, planOf(new Object[]{1001L, "番茄", "400", "g"}), profileOf());

            ShoppingListItem item = capturedItems(1).get(0);
            assertEquals("OUT_OF_STOCK", item.getStatus());
            assertEquals(0, item.getQuantity(), "缺货就不该算购买份数");
        }

        @Test
        @DisplayName("SKU 匹配：购买份数超过库存时标记缺货，但份数照算便于前端提示")
        void marksOutOfStockWhenQuantityExceedsStock() {
            registerIngredient(1001L, "番茄");
            registerSku(2001L, 1001L, "番茄 500g/盒", "5.50", 1, new BigDecimal("500"));

            // 需要 2000g → 4 份，库存只有 1
            service.generate(1L, 10L, planOf(new Object[]{1001L, "番茄", "2000", "g"}), profileOf());

            ShoppingListItem item = capturedItems(1).get(0);
            assertEquals(4, item.getQuantity());
            assertEquals("OUT_OF_STOCK", item.getStatus());
        }

        @Test
        @DisplayName("总价 = 单价 × 份数，并回写到清单主表")
        void calculatesTotalPrice() {
            registerIngredient(1001L, "番茄");
            registerSku(2001L, 1001L, "番茄 500g/盒", "5.50", 100, new BigDecimal("500"));

            service.generate(1L, 10L, planOf(new Object[]{1001L, "番茄", "1000", "g"}), profileOf());

            ArgumentCaptor<ShoppingList> captor = ArgumentCaptor.forClass(ShoppingList.class);
            verify(shoppingListMapper).updateById(captor.capture());

            // 2 份 × 5.50 = 11.00
            assertEquals(0, captor.getValue().getTotalPrice().compareTo(new BigDecimal("11.00")));
        }

        @Test
        @DisplayName("清单主表落库时带上用户、计划与标题")
        void persistsListHeader() {
            registerIngredient(1001L, "番茄");
            registerSku(2001L, 1001L, "番茄 500g/盒", "5.50", 100, new BigDecimal("500"));

            service.generate(7L, 99L, planOf(new Object[]{1001L, "番茄", "400", "g"}), profileOf());

            ArgumentCaptor<ShoppingList> captor = ArgumentCaptor.forClass(ShoppingList.class);
            verify(shoppingListMapper).insert(captor.capture());
            ShoppingList saved = captor.getValue();

            assertEquals(7L, saved.getUserId());
            assertEquals(99L, saved.getPlanId());
            assertEquals("DRAFT", saved.getStatus());
        }
    }

    // ==================== 冰箱扣减 ====================

    @Nested
    @DisplayName("冰箱扣减")
    class FridgeTest {

        @Test
        @DisplayName("★ 冰箱有 300g 番茄时按真实数量扣，而不是象征性扣 1g")
        void deductsByRealAmount() {
            registerIngredient(1001L, "番茄");
            registerSku(2001L, 1001L, "番茄 500g/盒", "5.50", 100, new BigDecimal("500"));

            UserProfile profile = profileOf();
            profile.setFridgeIngredients(new ArrayList<>(List.of("番茄")));
            profile.setFridgeStock(new ArrayList<>(List.of(
                    new UserProfile.FridgeItem("番茄", new BigDecimal("300"), "g"))));

            // 需要 800g，冰箱有 300g → 只买 500g → 1 份
            service.generate(1L, 10L, planOf(new Object[]{1001L, "番茄", "800", "g"}), profile);

            ShoppingListItem item = capturedItems(1).get(0);
            assertEquals(0, item.getFridgeAmount().compareTo(new BigDecimal("300.00")),
                    "冰箱里的 300g 必须被如实扣掉");
            assertEquals(0, item.getNeedBuyAmount().compareTo(new BigDecimal("500.00")));
            assertEquals(1, item.getQuantity());
        }

        @Test
        @DisplayName("冰箱数量足够时不需要购买")
        void fridgeCoversEverything() {
            registerIngredient(1001L, "番茄");
            registerSku(2001L, 1001L, "番茄 500g/盒", "5.50", 100, new BigDecimal("500"));

            UserProfile profile = profileOf();
            profile.setFridgeIngredients(new ArrayList<>(List.of("番茄")));
            profile.setFridgeStock(new ArrayList<>(List.of(
                    new UserProfile.FridgeItem("番茄", new BigDecimal("1000"), "g"))));

            service.generate(1L, 10L, planOf(new Object[]{1001L, "番茄", "400", "g"}), profile);

            ShoppingListItem item = capturedItems(1).get(0);
            assertEquals(0, item.getNeedBuyAmount().compareTo(BigDecimal.ZERO));
            assertEquals(0, item.getQuantity(), "冰箱够了就不买");
            assertEquals("AVAILABLE", item.getStatus());
        }

        @Test
        @DisplayName("单位不一致时不扣，宁可多买也不能错扣")
        void doesNotDeductWhenUnitMismatches() {
            registerIngredient(1002L, "鸡蛋");
            registerSku(2003L, 1002L, "鸡蛋 10枚/盒", "12.00", 100, new BigDecimal("10"));

            UserProfile profile = profileOf();
            profile.setFridgeIngredients(new ArrayList<>(List.of("鸡蛋")));
            // 冰箱记的是 4 枚，但清单要的是 6g —— 单位对不上，不能相减
            profile.setFridgeStock(new ArrayList<>(List.of(
                    new UserProfile.FridgeItem("鸡蛋", new BigDecimal("4"), "piece"))));

            service.generate(1L, 10L, planOf(new Object[]{1002L, "鸡蛋", "6", "g"}), profile);

            ShoppingListItem item = capturedItems(1).get(0);
            assertEquals(0, item.getFridgeAmount().compareTo(BigDecimal.ZERO),
                    "单位不同无法换算，应当不扣减");
        }

        @Test
        @DisplayName("只有名称没有数量时，按 1 个基准单位保守扣减")
        void nameOnlyDeductsOneUnit() {
            registerIngredient(1001L, "番茄");
            registerSku(2001L, 1001L, "番茄 500g/盒", "5.50", 100, new BigDecimal("500"));

            UserProfile profile = profileOf();
            // 请求体只传了名称，没有数量
            profile.setFridgeIngredients(new ArrayList<>(List.of("番茄")));

            service.generate(1L, 10L, planOf(new Object[]{1001L, "番茄", "400", "g"}), profile);

            ShoppingListItem item = capturedItems(1).get(0);
            assertEquals(0, item.getFridgeAmount().compareTo(BigDecimal.ONE),
                    "拿不到数量时保守扣 1 个单位，至少表明「家里有」");
        }

        @Test
        @DisplayName("名称通过别名归一时也能匹配上")
        void matchesFridgeByAlias() {
            registerIngredient(1001L, "番茄", "西红柿,洋柿子");
            registerSku(2001L, 1001L, "番茄 500g/盒", "5.50", 100, new BigDecimal("500"));

            UserProfile profile = profileOf();
            // 用户手填的是别名「西红柿」
            profile.setFridgeIngredients(new ArrayList<>(List.of("西红柿")));
            profile.setFridgeStock(new ArrayList<>(List.of(
                    new UserProfile.FridgeItem("番茄", new BigDecimal("300"), "g"))));

            service.generate(1L, 10L, planOf(new Object[]{1001L, "番茄", "400", "g"}), profile);

            ShoppingListItem item = capturedItems(1).get(0);
            assertEquals(0, item.getFridgeAmount().compareTo(new BigDecimal("300.00")),
                    "「西红柿」应能归一到「番茄」并扣掉 300g");
        }

        @Test
        @DisplayName("冰箱里匹配不到的食材直接跳过，不影响其他条目")
        void unmatchedFridgeItemIsSkipped() {
            registerIngredient(1001L, "番茄");
            registerSku(2001L, 1001L, "番茄 500g/盒", "5.50", 100, new BigDecimal("500"));

            UserProfile profile = profileOf();
            profile.setFridgeIngredients(new ArrayList<>(List.of("松露"))); // 食材库里没有

            service.generate(1L, 10L, planOf(new Object[]{1001L, "番茄", "400", "g"}), profile);

            ShoppingListItem item = capturedItems(1).get(0);
            assertEquals(0, item.getFridgeAmount().compareTo(BigDecimal.ZERO));
            assertEquals(1, item.getQuantity());
        }
    }
}
