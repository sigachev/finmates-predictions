package com.finmates.predictions.util;

import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
public class RateLimiter {
    private final Semaphore semaphore;
    private final int maxRequestsPerMinute;
    private final long requestDelay;

    public RateLimiter(int maxRequestsPerMinute) {
        this.maxRequestsPerMinute = maxRequestsPerMinute;
        this.semaphore = new Semaphore(maxRequestsPerMinute);
        this.requestDelay = 60_000 / maxRequestsPerMinute; // milliseconds between requests
    }

    public void acquire() throws InterruptedException {
        semaphore.acquire();
        try {
            Thread.sleep(requestDelay);
        } finally {
            semaphore.release();
        }
    }

    public void reset() {
        semaphore.drainPermits();
        semaphore.release(maxRequestsPerMinute);
    }
}
