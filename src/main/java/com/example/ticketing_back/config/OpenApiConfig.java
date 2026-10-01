package com.example.ticketing_back.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ticketingOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Ticketing API")
                .description("대용량 트래픽 티케팅 백엔드 API")
                .version("v1"));
    }
}
