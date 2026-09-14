package com.smartmeal.admin.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 管理端接口文档元信息。 */
@Configuration
public class AdminOpenApiConfig {

    @Bean
    public OpenAPI adminOpenApi() {
        return new OpenAPI().info(new Info()
                .title("SmartMeal 管理端 API")
                .version("1.0.0")
                .description("食材 / 菜谱 / 商品 SKU / 知识库 / Prompt 模板 / AI 生成日志 管理"));
    }
}
