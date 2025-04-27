package com.finmates.predictions.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class PricePredictionConfig {

    @Value("${retry.max-attempts:3}")
    private int maxRetries;

    @Value("${retry.initial-delay:1000}")
    private long initialRetryDelay;

    @Value("${retry.max-delay:10000}")
    private long maxRetryDelay;

    @Value("${retry.multiplier:2}")
    private double multiplier;

    @Bean
    public CircuitBreaker uniswapCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .slidingWindowSize(10)
                .build();
        return CircuitBreaker.of("uniswap", config);
    }

    @Bean
    public RetryTemplate retryTemplate() {
        RetryTemplate template = new RetryTemplate();

        // Configure exponential backoff
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(initialRetryDelay);
        backOffPolicy.setMaxInterval(maxRetryDelay);
        backOffPolicy.setMultiplier(multiplier);
        template.setBackOffPolicy(backOffPolicy);

        // Configure retry policy
        Map<Class<? extends Throwable>, Boolean> retryableExceptions = new HashMap<>();
        retryableExceptions.put(Exception.class, true);
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(maxRetries, retryableExceptions);
        template.setRetryPolicy(retryPolicy);

        return template;
    }
}
