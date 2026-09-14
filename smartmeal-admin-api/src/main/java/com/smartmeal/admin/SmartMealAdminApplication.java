package com.smartmeal.admin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;

/**
 * 管理端启动类，独立端口 8081。
 *
 * <p>用户端与管理端拆成两个可执行模块而不是一个应用加 {@code /admin} 前缀，
 * 理由是：两者的安全边界、发布节奏、扩容需求都不同。
 * 管理端一旦被攻破影响是全局的，独立部署可以把爆炸半径收窄，
 * 也方便只对管理端做 IP 白名单和内网访问限制。
 */
@Slf4j
@SpringBootApplication(scanBasePackages = "com.smartmeal")
public class SmartMealAdminApplication {

    public static void main(String[] args) {
        Environment env = SpringApplication.run(SmartMealAdminApplication.class, args).getEnvironment();
        String port = env.getProperty("server.port", "8081");
        log.info("""

                ==========================================================
                  SmartMeal 管理端启动成功
                  本地地址   : http://localhost:{}
                  接口文档   : http://localhost:{}/doc.html
                ==========================================================
                """, port, port);
    }
}
