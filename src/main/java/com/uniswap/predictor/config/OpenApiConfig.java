package com.uniswap.predictor.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Value("${server.port}")
    private String serverPort;

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Uniswap V3 Price Predictor API")
                        .description("Advanced API for predicting price ranges, optimizing liquidity positions, and calculating profitability metrics for Uniswap V3 pools using machine learning and historical data analysis")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Uniswap Predictor Team")
                                .email("contact@example.com")
                                .url("https://github.com/yourusername/uniswap-predictor"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:" + serverPort)
                                .description("Local development server"),
                        new Server()
                                .url("https://api.uniswap-predictor.example.com")
                                .description("Production server")
                ))
                .tags(List.of(
                        new Tag().name("Price Prediction").description("Endpoints for predicting price ranges and calculating profitability metrics"),
                        new Tag().name("Pool Management").description("Endpoints for managing and retrieving information about Uniswap V3 pools"),
                        new Tag().name("Position Analysis").description("Endpoints for analyzing and optimizing liquidity positions"),
                        new Tag().name("Model Management").description("Endpoints for managing the prediction models")
                ));
    }
}