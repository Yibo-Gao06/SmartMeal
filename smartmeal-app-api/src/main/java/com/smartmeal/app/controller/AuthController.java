package com.smartmeal.app.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.smartmeal.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口。
 *
 * <p><b>骨架实现</b>：只演示 Sa-Token 的登录态建立，没有校验密码。
 * 完整实现需要：BCrypt 密码比对、注册唯一性校验、登录失败次数限制、验证码。
 *
 * <p>之所以先留成这样，是为了让 {@code smartmeal.auth.enabled=true} 时也能快速拿到 token 联调，
 * 而不是被注册流程卡住。
 */
@Slf4j
@RestController
@RequestMapping("/api/app/auth")
@RequiredArgsConstructor
@Tag(name = "认证", description = "登录 / 登出（骨架实现）")
public class AuthController {

    @PostMapping("/login")
    @Operation(summary = "登录（骨架实现，不校验密码）",
            description = "传入任意 userId 即可获得 token，用于联调。生产需接入真实密码校验。")
    public Result<Map<String, Object>> login(@RequestBody LoginRequest request) {
        StpUtil.login(request.getUserId());
        log.info("用户登录 userId={}", request.getUserId());
        return Result.success(Map.of(
                "token", StpUtil.getTokenValue(),
                "tokenName", StpUtil.getTokenName(),
                "userId", request.getUserId()));
    }

    @PostMapping("/logout")
    @Operation(summary = "登出")
    public Result<Void> logout() {
        StpUtil.logout();
        return Result.success();
    }

    @Data
    public static class LoginRequest {
        private Long userId;
    }
}
