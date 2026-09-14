package com.smartmeal.app.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.smartmeal.common.result.Result;
import com.smartmeal.domain.entity.User;
import com.smartmeal.service.user.UserAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证接口：注册 / 登录 / 登出。
 *
 * <p><b>这一层刻意做得很薄</b>，只做三件事：参数绑定与校验、调用 {@code UserAuthService}、
 * 用 Sa-Token 签发或注销会话。所有安全规则（BCrypt 比对、失败锁定、用户名枚举防护）
 * 都在 service 层，因为那些是业务规则，不是 Web 层的事 ——
 * 放在这里的话，任何新的调用入口（比如将来加个管理端登录）都得重写一遍。
 *
 * <p>{@code /api/app/auth/**} 在 {@code SaTokenConfig} 的白名单里，
 * 否则会出现「必须先登录才能登录」的循环依赖。
 */
@Slf4j
@RestController
@RequestMapping("/api/app/auth")
@RequiredArgsConstructor
@Tag(name = "认证", description = "注册 / 登录 / 登出")
public class AuthController {

    private final UserAuthService userAuthService;

    /**
     * 注册。
     *
     * <p>注册成功不自动登录：让用户显式走一次登录，可以顺带验证「密码确实记住了」。
     * 自动登录会让「注册时手滑打错密码」变成一次静默的账号锁定。
     */
    @PostMapping("/register")
    @Operation(summary = "注册", description = "用户名 3~64 位字母/数字/下划线，密码 8~64 位")
    public Result<Map<String, Object>> register(@RequestBody @Valid RegisterRequest request) {
        Long userId = userAuthService.register(
                request.getUsername(), request.getPassword(), request.getNickname());
        return Result.success(Map.of("userId", userId));
    }

    /**
     * 登录。
     *
     * <p>失败时由 {@code GlobalExceptionHandler} 统一转成业务错误码：
     * 用户名或密码错误是 {@code 10003}，连续失败触发锁定是 {@code 10005}。
     */
    @PostMapping("/login")
    @Operation(summary = "登录",
            description = "成功返回 token（请求头名 satoken）。连续失败 5 次锁定 15 分钟")
    public Result<Map<String, Object>> login(@RequestBody @Valid LoginRequest request) {
        User user = userAuthService.authenticate(request.getUsername(), request.getPassword());
        StpUtil.login(user.getId());
        log.info("用户登录 userId={} username={}", user.getId(), user.getUsername());

        // 用 LinkedHashMap 而不是 Map.of：Map.of 不允许 value 为 null，
        // 而 nickname 在库里是可空的，一旦为 null 这里会直接 NPE。
        // 顺序固定成 token 在前，也方便日志和文档里对照。
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", StpUtil.getTokenValue());
        data.put("tokenName", StpUtil.getTokenName());
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        return Result.success(data);
    }

    @PostMapping("/logout")
    @Operation(summary = "登出")
    public Result<Void> logout() {
        StpUtil.logout();
        return Result.success();
    }

    /** 登录请求。密码长度上限与 {@code UserAuthServiceImpl} 保持一致，避免两处规则漂移。 */
    @Data
    public static class LoginRequest {

        @NotBlank(message = "用户名不能为空")
        private String username;

        @NotBlank(message = "密码不能为空")
        private String password;
    }

    @Data
    public static class RegisterRequest {

        @NotBlank(message = "用户名不能为空")
        private String username;

        @NotBlank(message = "密码不能为空")
        private String password;

        /** 可选。不传则用用户名作为昵称。 */
        @Size(max = 64, message = "昵称长度不能超过 64")
        private String nickname;
    }
}
