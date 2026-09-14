package com.smartmeal.app.controller;

import com.smartmeal.common.result.Result;
import com.smartmeal.domain.dto.shopping.ShoppingListVO;
import com.smartmeal.domain.entity.ShoppingList;
import com.smartmeal.domain.entity.ShoppingListItem;
import com.smartmeal.service.shopping.ShoppingListService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 购物清单查询接口。
 *
 * <p>清单在生成计划时同步产出，这里只做读取。
 * 返回体里额外算了一个 {@code itemsTotal}（所有明细小计之和），
 * 目的是让前端能自己发现「明细加起来和总价对不上」这种数据问题 ——
 * 正常情况两者应该相等，不等就说明有明细在落库时被漏算或价格被改过。
 */
@RestController
@RequestMapping("/api/app/shopping-list")
@RequiredArgsConstructor
@Tag(name = "购物清单", description = "查询清单与明细")
public class ShoppingListController {

    private final ShoppingListService shoppingListService;

    @GetMapping("/{listId}")
    @Operation(summary = "按清单 ID 查询（含明细）",
            description = "明细含冰箱扣减、整包取整后的购买份数与小计")
    public Result<ShoppingListVO> detail(@PathVariable Long listId) {
        // getById 在找不到时会抛 NOT_FOUND，这里不需要再判空
        return Result.success(toVO(shoppingListService.getById(listId)));
    }

    /**
     * 按计划 ID 查询清单。
     *
     * <p>为什么需要这条：前端刷新页面后是从 {@code /meal-plan/latest} 拿到 planId 的，
     * 手里并没有 shoppingListId，只能反过来按 planId 找清单。
     */
    @GetMapping("/by-plan/{planId}")
    @Operation(summary = "按计划 ID 查询购物清单",
            description = "清单还没生成时 data 为 null，前端应展示「生成中」而不是报错")
    public Result<ShoppingListVO> byPlan(@PathVariable Long planId) {
        ShoppingList list = shoppingListService.getByPlanId(planId);
        return Result.success(list == null ? null : toVO(list));
    }

    private ShoppingListVO toVO(ShoppingList list) {
        ShoppingListVO vo = new ShoppingListVO();
        vo.setListId(list.getId());
        vo.setPlanId(list.getPlanId());
        vo.setTitle(list.getTitle());
        vo.setTotalPrice(list.getTotalPrice());
        vo.setStatus(list.getStatus());

        BigDecimal itemsTotal = BigDecimal.ZERO;
        List<ShoppingListVO.ItemVO> itemVOs = new ArrayList<>();
        for (ShoppingListItem item : shoppingListService.listItems(list.getId())) {
            ShoppingListVO.ItemVO itemVO = new ShoppingListVO.ItemVO();
            itemVO.setIngredientId(item.getIngredientId());
            itemVO.setIngredientName(item.getIngredientName());
            itemVO.setRequiredAmount(item.getRequiredAmount());
            itemVO.setUnit(item.getUnit());
            itemVO.setFridgeAmount(item.getFridgeAmount());
            itemVO.setNeedBuyAmount(item.getNeedBuyAmount());
            itemVO.setSkuId(item.getSkuId());
            itemVO.setSkuName(item.getSkuName());
            itemVO.setQuantity(item.getQuantity());
            itemVO.setPrice(item.getPrice());
            itemVO.setStatus(item.getStatus());

            BigDecimal subtotal = subtotal(item);
            itemVO.setSubtotal(subtotal);
            itemsTotal = itemsTotal.add(subtotal);
            itemVOs.add(itemVO);
        }
        vo.setItems(itemVOs);
        vo.setItemsTotal(itemsTotal);
        return vo;
    }

    /**
     * 小计 = 单价 × 份数。
     *
     * <p>刻意与 {@code ShoppingListServiceImpl} 累加总价时的算法保持一致
     * （那里也是 {@code price.multiply(quantity)}），
     * 否则 itemsTotal 和 totalPrice 会因四舍五入差异永远对不上，对账就失去意义了。
     */
    static BigDecimal subtotal(ShoppingListItem item) {
        if (item.getPrice() == null || item.getQuantity() == null) {
            return BigDecimal.ZERO;
        }
        return item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
    }
}
