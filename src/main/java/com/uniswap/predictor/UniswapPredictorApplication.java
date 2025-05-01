package com.uniswap.predictor;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
//need this for Swagger UI to call relative path backend
@OpenAPIDefinition(servers = {@Server(url = "/", description = "Default Server URL")})
public class UniswapPredictorApplication {
    public static void main(String[] args) {
        SpringApplication.run(UniswapPredictorApplication.class, args);
    }
}