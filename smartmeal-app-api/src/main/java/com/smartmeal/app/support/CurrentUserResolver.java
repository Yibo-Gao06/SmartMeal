package com.smartmeal.app.support;

import cn.dev33.satoken.stp.StpUtil;
import com.smartmeal.common.constant.CommonConstants;
import com.smartmeal.common.exception.BusinessException;
import com.smartmeal.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 当前登录用户解析。
 *
 * <p>为什么要有这一层：本地开发和联调阶段，每个接口都先登录拿 token 太啰嗦，
 * 会严重拖慢验证速度。所以提供 {@code smartmeal.auth.enabled=false} 开关，
 * 关闭鉴权时统一返回一个开发用户 ID。
 *
 * <p><b>生产环境必须设为 true</b>，否则等于没有权限控制。
 * 默认值取 true 是刻意为之 —— 让「忘记配置」的结果是安全而不是裸奔。
 */
@Slf4j
@Component
public class CurrentUserResolver {

    @Value("${smartmeal.auth.enabled:true}")
    private boolean authEnabled;

    @Value("${smartmeal.auth.dev-user-id:1}")
    private Long devUserId;

    public Long currentUserId() {
        if (!authEnabled) {
            return devUserId;
        }
        if (!StpUtil.isLogin()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return StpUtil.getLoginIdAsLong();
    }

    /** 登录类型，用于隔离用户端与管理端会话。 */
    public String loginType() {
        return CommonConstants.LOGIN_TYPE_APP;
    }

    public boolean isAuthEnabled() {
        return authEnabled;
    }
}
