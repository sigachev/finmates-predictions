package com.finmates.predictions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class FinmatesPredictionsApplication {
    public static void main(String[] args) {
        SpringApplication.run(FinmatesPredictionsApplication.class, args);
    }
}
