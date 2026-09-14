package com.smartmeal.service.user;

import com.smartmeal.domain.dto.MealPlanRequest;
import com.smartmeal.service.user.bo.UserProfile;

/** 用户画像服务：把数据库里的用户数据与本次请求合并成一份完整画像。 */
public interface UserProfileService {

    /**
     * 构建画像。
     *
     * @param userId  当前登录用户
     * @param request 本次生成请求，优先级高于库里的存量数据（用户可能临时改目标）
     */
    UserProfile buildProfile(Long userId, MealPlanRequest request);

    /** 读取画像（带缓存），供非生成场景复用。 */
    UserProfile getProfile(Long userId);
}
