package com.smartmeal.service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器。
 *
 * <p><b>为什么是 BCrypt 而不是 MD5/SHA-256</b>：密码哈希要的是「慢」，不是「快」。
 * MD5 每秒能算几十亿次，一张彩虹表就能反查常见口令；BCrypt 的 cost 因子
 * 让单次计算固定在几十毫秒量级，暴力破解的成本直接高几个数量级。
 *
 * <p><b>为什么不需要额外的 salt 列</b>：BCrypt 把 salt 编码在哈希串自身里
 * （{@code $2a$10$<22位salt><31位摘要>}），同一个口令每次加密结果都不同，
 * 但 {@code matches()} 能正确校验。自己拼 salt 再存一列是常见的画蛇添足。
 *
 * <p><b>为什么 strength 用默认的 10</b>：10 对应约 2^10 次迭代。
 * 调高更安全，但登录接口的响应时间会线性变长 —— 这是个需要按机器性能实测的权衡，
 * 不该拍脑袋定。10 是 Spring Security 的默认值，也是目前最广泛使用的取值。
 *
 * <p>这个 Bean 放在 service 模块而不是 app-api：密码校验是业务规则，
 * 不是 Web 层的事。放这里，service 的单元测试可以直接注入它，
 * 不需要起 Spring 容器。
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
