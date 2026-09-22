package com.smartmeal.app.support;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.exception.SaTokenException;
import com.smartmeal.common.result.Result;
import com.smartmeal.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把 Sa-Token 抛出的鉴权异常统一转成项目的 {@link Result} 结构。
 *
 * <p>为什么必须单独有一层：{@code SaTokenConfig} 的拦截器在「进入控制器之前」就调用
 * {@code StpUtil.checkLogin()}，未登录会抛 {@code NotLoginException}。这个异常发生在
 * Controller 方法之外，不会被 common 模块的 {@code GlobalExceptionHandler}（它只管
 * {@code BusinessException}，且不依赖 sa-token）拦截，不处理就会冒泡到兜底分支变成
 * 500「系统异常，请稍后重试」——前端既没法据此判断「该弹登录框」，对外也暴露成系统错误。
 *
 * <p>这里显式转成 401 / 403，和 {@code CurrentUserResolver} 的语义对齐：前端只要看
 * {@code body.code} 是 401 就知道会话失效、该弹登录框，是 403 就知道无权限。
 *
 * <p>注意：本项目所有接口（含错误响应）统一走 HTTP 200 + {@code body.code} 的约定，
 * 所以这里返回的是带 code=401/403 的 {@link Result}，而不是改 HTTP 状态码。
 */
@Slf4j
@RestControllerAdvice
public class SaTokenExceptionResolver {

    @ExceptionHandler(NotLoginException.class)
    public Result<Void> handleNotLogin(NotLoginException e) {
        // 未登录是高频正常分支，记 warn 即可，不打堆栈
        log.warn("未登录或登录已过期: {}", e.getMessage());
        return Result.failed(ResultCode.UNAUTHORIZED);
    }

    @ExceptionHandler(NotPermissionException.class)
    public Result<Void> handleNotPermission(NotPermissionException e) {
        return Result.failed(ResultCode.FORBIDDEN);
    }

    @ExceptionHandler(NotRoleException.class)
    public Result<Void> handleNotRole(NotRoleException e) {
        return Result.failed(ResultCode.FORBIDDEN);
    }

    /** 兜底：其它 Sa-Token 异常（极少见）也归一成「未登录」语义，避免泄露内部细节。 */
    @ExceptionHandler(SaTokenException.class)
    public Result<Void> handleSaToken(SaTokenException e) {
        log.warn("Sa-Token 异常: {}", e.getMessage());
        return Result.failed(ResultCode.UNAUTHORIZED);
    }
}
