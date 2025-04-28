package com.uniswap.predictor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class UniswapPredictorApplication {
    public static void main(String[] args) {
        SpringApplication.run(UniswapPredictorApplication.class, args);
    }
}