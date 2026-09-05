package com.j4mb.payment_orchestrator.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI/Swagger metadata for the public payment API. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI paymentOrchestratorOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Payment Orchestrator API")
                        .description("Unified API for processing payments across multiple providers.")
                        .version("v1")
                        .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")));
    }
}