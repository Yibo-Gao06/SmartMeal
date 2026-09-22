package com.smartmeal.app.controller;

import com.smartmeal.app.support.CurrentUserResolver;
import com.smartmeal.common.result.Result;
import com.smartmeal.domain.dto.CartBatchAddRequest;
import com.smartmeal.domain.dto.cart.CartBatchAddResultVO;
import com.smartmeal.domain.dto.cart.CartVO;
import com.smartmeal.domain.entity.ShoppingListItem;
import com.smartmeal.service.cart.CartService;
import com.smartmeal.service.shopping.ShoppingListService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 购物车与购物清单。
 *
 * <p>「一键加购」是本项目交易闭环的入口：把 AI 生成的购物清单一次性转成购物车条目。
 *
 * <p>库存策略：加购只做软校验（把缺货风险提前暴露给用户），
 * 真正的扣减与防超卖发生在下单那一刻的条件 UPDATE 上。
 */
@RestController
@RequestMapping("/api/app/cart")
@RequiredArgsConstructor
@Tag(name = "购物车", description = "购物清单加购与购物车管理")
public class CartController {

    private final ShoppingListService shoppingListService;
    private final CartService cartService;
    private final CurrentUserResolver currentUserResolver;

    /** 查看购物清单明细。 */
    @GetMapping("/shopping-list/{listId}")
    @Operation(summary = "查看购物清单明细",
            description = "每条明细包含：菜谱需要量 / 冰箱已有量 / 实际需买量 / 匹配到的 SKU 与份数")
    public Result<List<ShoppingListItem>> listShoppingListItems(@PathVariable Long listId) {
        return Result.success(shoppingListService.listItems(listId));
    }

    /** 一键加购：购物清单条目按 SKU 合并写入购物车。 */
    @PostMapping("/batch")
    @Operation(summary = "购物清单一键加入购物车",
            description = "同 SKU 条目自动合并累加；缺货条目跳过并在结果中说明原因，"
                    + "allowSubstitute=true 时尝试用清单里记录的替代 SKU 补齐")
    public Result<CartBatchAddResultVO> batchAdd(@RequestBody @Valid CartBatchAddRequest request) {
        Long userId = currentUserResolver.currentUserId();
        return Result.success(cartService.batchAdd(userId, request));
    }

    @GetMapping
    @Operation(summary = "查看购物车", description = "含 SKU 快照、小计与勾选合计，标记下架/缺库存条目")
    public Result<CartVO> cart() {
        return Result.success(cartService.getCart(currentUserResolver.currentUserId()));
    }

    @PutMapping("/{cartId}/quantity")
    @Operation(summary = "修改条目数量", description = "校验归属与当前库存，1~99")
    public Result<Void> updateQuantity(@PathVariable Long cartId, @RequestParam int quantity) {
        cartService.updateQuantity(currentUserResolver.currentUserId(), cartId, quantity);
        return Result.success(null);
    }

    @PutMapping("/{cartId}/selected")
    @Operation(summary = "勾选 / 取消勾选条目")
    public Result<Void> updateSelected(@PathVariable Long cartId, @RequestParam boolean selected) {
        cartService.updateSelected(currentUserResolver.currentUserId(), cartId, selected);
        return Result.success(null);
    }

    @DeleteMapping("/{cartId}")
    @Operation(summary = "删除条目")
    public Result<Void> remove(@PathVariable Long cartId) {
        cartService.remove(currentUserResolver.currentUserId(), cartId);
        return Result.success(null);
    }
}
