package com.smartmeal.service.shopping;

import com.smartmeal.domain.dto.plan.MealPlanResult;
import com.smartmeal.domain.entity.ShoppingList;
import com.smartmeal.domain.entity.ShoppingListItem;
import com.smartmeal.service.user.bo.UserProfile;

import java.util.List;

/** 购物清单服务：把「一周食谱」翻译成「要买什么、买几份」。 */
public interface ShoppingListService {

    /**
     * 生成购物清单。
     *
     * @return 新建清单的 id
     */
    Long generate(Long userId, Long planId, MealPlanResult result, UserProfile profile);

    ShoppingList getById(Long listId);

    /**
     * 按计划 ID 查询购物清单。
     *
     * <p>前端刷新页面后只知道 planId（从 {@code /meal-plan/latest} 拿到的），
     * 不知道 shoppingListId，所以需要这条查询来恢复清单。
     *
     * @return 该计划还没生成清单时返回 {@code null}
     */
    ShoppingList getByPlanId(Long planId);

    List<ShoppingListItem> listItems(Long listId);
}
