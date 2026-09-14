package com.smartmeal.app;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 用户端启动类。
 *
 * <p>{@code scanBasePackages = "com.smartmeal"}：多模块项目里各个模块的 Bean
 * 分散在不同 artifact 下，必须把扫描根提升到公共父包，否则 {@code smartmeal-ai}、
 * {@code smartmeal-service} 里的 {@code @Service} 不会被注册。
 * Mapper 的扫描在 {@code MybatisPlusConfig} 上，不在这里重复声明。
 */
@Slf4j
@SpringBootApplication(scanBasePackages = "com.smartmeal")
@EnableScheduling
public class SmartMealAppApplication {

    public static void main(String[] args) {
        Environment env = SpringApplication.run(SmartMealAppApplication.class, args).getEnvironment();
        String port = env.getProperty("server.port", "8080");
        String contextPath = env.getProperty("server.servlet.context-path", "");
        log.info("""

                ==========================================================
                  SmartMeal 用户端启动成功
                  本地地址   : http://localhost:{}{}
                  接口文档   : http://localhost:{}{}/doc.html
                  健康检查   : http://localhost:{}{}/actuator/health
                  当前 Profile: {}
                ==========================================================
                """, port, contextPath, port, contextPath, port, contextPath,
                String.join(",", env.getActiveProfiles()));
    }
}
