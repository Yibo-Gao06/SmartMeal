package com.smartmeal.app.controller;

import com.smartmeal.app.support.CurrentUserResolver;
import com.smartmeal.common.result.Result;
import com.smartmeal.service.user.UserProfileService;
import com.smartmeal.service.user.bo.UserProfile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 用户健康档案。 */
@RestController
@RequestMapping("/api/app/user")
@RequiredArgsConstructor
@Tag(name = "用户中心", description = "健康档案与画像")
public class UserController {

    private final UserProfileService userProfileService;
    private final CurrentUserResolver currentUserResolver;

    /**
     * 查询当前用户的健康画像。
     *
     * <p>返回的不只是原始字段，还包含后端算好的 BMR / TDEE / 每日目标热量，
     * 以及过敏原展开后的食材列表 —— 前端可以直接展示「系统已为你屏蔽以下食材」。
     */
    @GetMapping("/profile")
    @Operation(summary = "查询健康画像",
            description = "含 BMR、TDEE、每日目标热量，以及过敏原展开后的禁忌食材清单")
    public Result<UserProfile> getProfile() {
        Long userId = currentUserResolver.currentUserId();
        return Result.success(userProfileService.getProfile(userId));
    }
}
