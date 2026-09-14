package com.smartmeal.app.controller;

import com.smartmeal.app.support.CurrentUserResolver;
import com.smartmeal.common.result.Result;
import com.smartmeal.domain.dto.CartBatchAddRequest;
import com.smartmeal.domain.entity.ShoppingListItem;
import com.smartmeal.service.shopping.ShoppingListService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 购物车与购物清单。
 *
 * <p>「一键加购」是本项目交易闭环的入口：把 AI 生成的购物清单一次性转成购物车条目。
 */
@Slf4j
@RestController
@RequestMapping("/api/app/cart")
@RequiredArgsConstructor
@Tag(name = "购物车", description = "购物清单与批量加购")
public class CartController {

    private final ShoppingListService shoppingListService;
    private final CurrentUserResolver currentUserResolver;

    /** 查看购物清单明细。 */
    @GetMapping("/shopping-list/{listId}")
    @Operation(summary = "查看购物清单明细",
            description = "每条明细包含：菜谱需要量 / 冰箱已有量 / 实际需买量 / 匹配到的 SKU 与份数")
    public Result<List<ShoppingListItem>> listShoppingListItems(@PathVariable Long listId) {
        return Result.success(shoppingListService.listItems(listId));
    }

    /**
     * 一键加购。
     *
     * <p>当前为骨架实现，返回清单中可购买（AVAILABLE）的条目。
     * 完整实现需要补齐：库存预占、购物车合并、并发扣减。
     */
    @PostMapping("/batch")
    @Operation(summary = "购物清单一键加入购物车",
            description = "骨架实现：校验清单状态并返回可加购条目，库存预占与购物车合并待补充")
    public Result<Map<String, Object>> batchAdd(@RequestBody @Valid CartBatchAddRequest request) {
        Long userId = currentUserResolver.currentUserId();
        List<ShoppingListItem> items = shoppingListService.listItems(request.getShoppingListId());

        List<ShoppingListItem> available = items.stream()
                .filter(item -> "AVAILABLE".equals(item.getStatus()))
                .filter(item -> item.getQuantity() != null && item.getQuantity() > 0)
                .toList();
        List<ShoppingListItem> outOfStock = items.stream()
                .filter(item -> "OUT_OF_STOCK".equals(item.getStatus()))
                .toList();

        log.info("一键加购 userId={} 清单={} 可加购={} 缺货={}",
                userId, request.getShoppingListId(), available.size(), outOfStock.size());

        return Result.success(Map.of(
                "shoppingListId", request.getShoppingListId(),
                "addableCount", available.size(),
                "outOfStockCount", outOfStock.size(),
                "outOfStockItems", outOfStock.stream()
                        .map(ShoppingListItem::getIngredientName).toList(),
                "items", available));
    }
}
