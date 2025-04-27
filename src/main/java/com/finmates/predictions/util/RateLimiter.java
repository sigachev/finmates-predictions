package com.finmates.predictions.util;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class RateLimiter {

    // Sliding Window configuration
    private final Map<Long, AtomicInteger> requestWindows = new ConcurrentHashMap<>();
    private final int windowSizeMs;
    private final int maxRequestsPerWindow;

    // Token Bucket configuration
    private final AtomicLong lastRefillTime;
    private final AtomicInteger availableTokens;
    private final int bucketCapacity;
    private final double refillRate; // tokens per millisecond

    // Request Queue for handling bursts
    private final Queue<RateLimitRequest> requestQueue;
    private final ScheduledExecutorService scheduler;
    private final int queueCapacity;

    public RateLimiter(
            @Value("${rate.limiter.window.size:60000}") int windowSizeMs,
            @Value("${rate.limiter.max.requests:100}") int maxRequestsPerWindow,
            @Value("${rate.limiter.bucket.capacity:100}") int bucketCapacity,
            @Value("${rate.limiter.tokens.per.second:10}") int tokensPerSecond,
            @Value("${rate.limiter.queue.capacity:1000}") int queueCapacity) {

        this.windowSizeMs = windowSizeMs;
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.bucketCapacity = bucketCapacity;
        this.refillRate = tokensPerSecond / 1000.0; // Convert to tokens per millisecond
        this.queueCapacity = queueCapacity;

        this.lastRefillTime = new AtomicLong(System.currentTimeMillis());
        this.availableTokens = new AtomicInteger(bucketCapacity);
        this.requestQueue = new ConcurrentLinkedQueue<>();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();

        // Start background tasks
        startMaintenanceTasks();
    }

    private void startMaintenanceTasks() {
        // Clean up old window entries periodically
        scheduler.scheduleAtFixedRate(
                this::cleanupOldWindows,
                windowSizeMs,
                windowSizeMs / 2,
                TimeUnit.MILLISECONDS
        );

        // Process queued requests
        scheduler.scheduleAtFixedRate(
                this::processQueue,
                100,
                100,
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * Attempts to acquire a permit to proceed with the request
     * @return true if the request can proceed, false otherwise
     */
    public boolean tryAcquire() {
        return tryAcquire(1, 0, TimeUnit.MILLISECONDS);
    }

    /**
     * Attempts to acquire a permit with timeout
     * @param permits number of permits to acquire
     * @param timeout maximum time to wait
     * @param unit time unit for timeout
     * @return true if permits were acquired, false otherwise
     */
    public boolean tryAcquire(int permits, long timeout, TimeUnit unit) {
        long startTime = System.currentTimeMillis();
        long timeoutMs = unit.toMillis(timeout);

        // First check sliding window
        if (!checkSlidingWindowLimit()) {
            return handleRejection("Sliding window limit exceeded");
        }

        // Then check token bucket
        while (true) {
            refillTokens();

            int currentTokens = availableTokens.get();
            if (currentTokens >= permits) {
                if (availableTokens.compareAndSet(currentTokens, currentTokens - permits)) {
                    recordSuccess();
                    return true;
                }
                // CAS failed, try again
                continue;
            }

            // Check if we've exceeded timeout
            if (timeout > 0) {
                long elapsedMs = System.currentTimeMillis() - startTime;
                if (elapsedMs >= timeoutMs) {
                    return handleRejection("Timeout waiting for tokens");
                }

                // Try to queue the request
                return tryQueue(permits, timeoutMs - elapsedMs);
            }

            return handleRejection("Insufficient tokens");
        }
    }

    private boolean checkSlidingWindowLimit() {
        long currentTime = System.currentTimeMillis();
        long windowKey = currentTime / windowSizeMs;

        AtomicInteger counter = requestWindows.computeIfAbsent(windowKey, k -> new AtomicInteger(0));
        int currentCount = counter.get();

        if (currentCount >= maxRequestsPerWindow) {
            return false;
        }

        return counter.compareAndSet(currentCount, currentCount + 1);
    }

    private void refillTokens() {
        long currentTime = System.currentTimeMillis();
        long lastRefill = lastRefillTime.get();
        long elapsedTime = currentTime - lastRefill;

        if (elapsedTime > 0) {
            if (lastRefillTime.compareAndSet(lastRefill, currentTime)) {
                int tokensToAdd = (int) (elapsedTime * refillRate);
                if (tokensToAdd > 0) {
                    addTokens(tokensToAdd);
                }
            }
        }
    }

    private void addTokens(int tokens) {
        while (true) {
            int currentTokens = availableTokens.get();
            int newTokens = Math.min(currentTokens + tokens, bucketCapacity);
            if (availableTokens.compareAndSet(currentTokens, newTokens)) {
                break;
            }
        }
    }

    private boolean tryQueue(int permits, long remainingTimeoutMs) {
        if (requestQueue.size() >= queueCapacity) {
            return handleRejection("Queue capacity exceeded");
        }

        RateLimitRequest request = new RateLimitRequest(permits, remainingTimeoutMs);
        requestQueue.offer(request);

        try {
            return request.await(remainingTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            requestQueue.remove(request);
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void processQueue() {
        while (!requestQueue.isEmpty()) {
            RateLimitRequest request = requestQueue.peek();
            if (request == null) {
                break;
            }

            if (request.isExpired()) {
                requestQueue.poll();
                request.reject();
                continue;
            }

            refillTokens();
            int currentTokens = availableTokens.get();

            if (currentTokens >= request.permits) {
                if (availableTokens.compareAndSet(currentTokens, currentTokens - request.permits)) {
                    requestQueue.poll();
                    request.grant();
                }
            } else {
                // Not enough tokens, wait for next refill
                break;
            }
        }
    }

    private void cleanupOldWindows() {
        long currentTime = System.currentTimeMillis();
        long oldestValidWindow = (currentTime - windowSizeMs) / windowSizeMs;

        requestWindows.entrySet().removeIf(entry -> entry.getKey() < oldestValidWindow);
    }

    private boolean handleRejection(String reason) {
        log.debug("Rate limit rejection: {}", reason);
        return false;
    }

    private void recordSuccess() {
        // Could add metrics here
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Internal class to represent queued rate limit requests
     */
    private static class RateLimitRequest {
        private final int permits;
        private final long expirationTime;
        private final CompletableFuture<Boolean> future;

        RateLimitRequest(int permits, long timeoutMs) {
            this.permits = permits;
            this.expirationTime = System.currentTimeMillis() + timeoutMs;
            this.future = new CompletableFuture<>();
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expirationTime;
        }

        void grant() {
            future.complete(true);
        }

        void reject() {
            future.complete(false);
        }

        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                return future.get(timeout, unit);
            } catch (TimeoutException e) {
                return false;
            } catch (ExecutionException e) {
                return false;
            }
        }
    }
}
