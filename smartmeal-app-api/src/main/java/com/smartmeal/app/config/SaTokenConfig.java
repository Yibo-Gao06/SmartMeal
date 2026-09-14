package com.smartmeal.app.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 鉴权与跨域配置。
 *
 * <p>{@code smartmeal.auth.enabled=false} 时完全不注册登录拦截器，
 * 方便本地无感联调。开启时对所有 {@code /api/**} 强制校验登录态。
 */
@Slf4j
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    /** 无需登录即可访问的路径。 */
    private static final String[] WHITELIST = {
            "/api/app/auth/**",
            "/doc.html",
            "/webjars/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/actuator/**",
            "/favicon.ico",
            "/error"
    };

    @Value("${smartmeal.auth.enabled:true}")
    private boolean authEnabled;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (!authEnabled) {
            log.warn("smartmeal.auth.enabled=false，已关闭登录校验。此配置仅用于本地开发，生产环境必须开启！");
            return;
        }
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/api/**")
                .excludePathPatterns(WHITELIST);
        log.info("已启用 Sa-Token 登录校验，白名单 {} 条", WHITELIST.length);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 开发阶段允许任意来源，方便前后端分离联调；生产应收紧到具体域名
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
