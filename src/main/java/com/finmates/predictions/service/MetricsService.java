package com.finmates.predictions.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Service;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class MetricsService {
    private final MeterRegistry registry;
    private final CacheManager cacheManager;

    public MetricsService(MeterRegistry registry, CacheManager cacheManager) {
        this.registry = registry;
        this.cacheManager = cacheManager;
    }

    public void recordPredictionLatency(long startTime) {
        long duration = System.currentTimeMillis() - startTime;
        registry.timer("prediction.latency").record(duration, TimeUnit.MILLISECONDS);
        log.debug("Recorded prediction latency: {}ms", duration);
    }

    public void recordPredictionAccuracy(double predicted, double actual) {
        double accuracy = Math.abs(predicted - actual) / actual;
        registry.gauge("prediction.accuracy", Collections.emptyList(), accuracy);
        log.debug("Recorded prediction accuracy: {}", accuracy);
    }

    public void recordDataSourceType(boolean isSimulated) {
        registry.counter("data.source", Arrays.asList(
                Tag.of("type", isSimulated ? "simulated" : "real")
        )).increment();
        log.debug("Recorded data source type: {}", isSimulated ? "simulated" : "real");
    }

    public void recordBatchProcessing(int successCount, int totalCount) {
        registry.counter("batch.processing.success").increment(successCount);
        registry.counter("batch.processing.total").increment(totalCount);

        double successRate = totalCount > 0 ? (double) successCount / totalCount : 0;
        registry.gauge("batch.processing.success.rate", Collections.emptyList(), successRate);

        log.debug("Batch processing metrics - Success: {}, Total: {}, Rate: {}",
                successCount, totalCount, String.format("%.2f", successRate));
    }

    public void recordBlockchainRequestMetrics(boolean success, long latency) {
        registry.counter("blockchain.requests", Arrays.asList(
                Tag.of("status", success ? "success" : "failure")
        )).increment();
        registry.timer("blockchain.request.latency").record(latency, TimeUnit.MILLISECONDS);
        log.debug("Blockchain request metrics - Success: {}, Latency: {}ms", success, latency);
    }

    public void incrementErrorCount(String errorType) {
        registry.counter("errors", Arrays.asList(
                Tag.of("type", errorType)
        )).increment();
        log.debug("Incremented error counter for type: {}", errorType);
    }

    public void recordCacheMetrics() {
        cacheManager.getCacheNames().forEach(cacheName -> {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache instanceof CaffeineCache) {
                com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeCache =
                        ((CaffeineCache) cache).getNativeCache();

                CacheStats stats = nativeCache.stats();

                registry.gauge("cache.size", Arrays.asList(Tag.of("name", cacheName)), nativeCache.estimatedSize());
                registry.gauge("cache.hits", Arrays.asList(Tag.of("name", cacheName)), stats.hitCount());
                registry.gauge("cache.misses", Arrays.asList(Tag.of("name", cacheName)), stats.missCount());
                registry.gauge("cache.hit.ratio", Arrays.asList(Tag.of("name", cacheName)), stats.hitRate());

                log.debug("Cache metrics for {}: size={}, hits={}, misses={}, hitRate={}",
                        cacheName, nativeCache.estimatedSize(), stats.hitCount(),
                        stats.missCount(), String.format("%.2f", stats.hitRate()));
            }
        });
    }

    public void recordRequestLatency(String operation, long startTime) {
        long duration = System.currentTimeMillis() - startTime;
        Timer timer = registry.timer("request.latency", Arrays.asList(
                Tag.of("operation", operation)
        ));
        timer.record(duration, TimeUnit.MILLISECONDS);
        log.debug("{} operation latency: {}ms", operation, duration);
    }

    public void recordRateLimitEvent(boolean accepted) {
        registry.counter("rate.limit", Arrays.asList(
                Tag.of("status", accepted ? "accepted" : "rejected")
        )).increment();
        log.debug("Rate limit event recorded: {}", accepted ? "accepted" : "rejected");
    }
}
