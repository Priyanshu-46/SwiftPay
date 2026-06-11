package com.swiftpay.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI swiftPayOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("SwiftPay Transaction Gateway API")
                        .description("Real-time P2P payment ledger — Transaction Gateway service")
                        .version("v1.0.0")
                        .contact(new Contact().name("SwiftPay Team"))
                        .license(new License().name("MIT")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local dev"),
                        new Server().url("http://transaction-gateway:8080").description("Docker network")
                ));
    }
}
