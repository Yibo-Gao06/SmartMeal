package com.smartmeal.app.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 接口文档元信息。UI 由 Knife4j 提供，访问 /doc.html。 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI smartMealOpenApi() {
        return new OpenAPI().info(new Info()
                .title("SmartMeal 用户端 API")
                .version("1.0.0")
                .description("""
                        智能膳食规划与生鲜导购平台 - 用户端接口

                        核心链路：健康档案 → AI 生成一周食谱（SSE 流式）→ 购物清单 → 一键加购 → 下单

                        注意：/api/app/ai/meal-plan/stream 返回 text/event-stream，
                        浏览器原生 EventSource 只支持 GET，因此该接口需要用 fetch + ReadableStream 消费。
                        """)
                .contact(new Contact().name("SmartMeal")));
    }
}
