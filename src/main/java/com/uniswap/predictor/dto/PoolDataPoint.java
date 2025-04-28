package com.uniswap.predictor.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
public class PoolDataPoint {
    @Builder.Default
    private BigDecimal token0Price = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal token1Price = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal sqrtPriceX96 = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal liquidity = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal volume24h = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal fees24h = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal volatility24h = BigDecimal.ZERO;

    private int tick;
    private long timestamp;

    // Custom builder class
    public static class PoolDataPointBuilder {
        // Add these methods to handle different timestamp types
        public PoolDataPointBuilder timestamp(long epochSeconds) {
            this.timestamp = epochSeconds;
            return this;
        }

        public PoolDataPointBuilder timestamp(Instant instant) {
            this.timestamp = instant != null ? instant.getEpochSecond() : 0L;
            return this;
        }
    }

    // Convenience method to get timestamp as Instant
    public Instant getTimestampAsInstant() {
        return Instant.ofEpochSecond(timestamp);
    }

}