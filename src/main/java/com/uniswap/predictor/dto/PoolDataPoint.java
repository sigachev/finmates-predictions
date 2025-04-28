package com.uniswap.predictor.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;

@Data
@Builder
public class PoolDataPoint {
    private Instant timestamp;
    private BigDecimal sqrtPriceX96;
    private BigInteger liquidity;
    private int tick;
    private BigDecimal token0Price;
    private BigDecimal token1Price;
    private BigDecimal volume24h;
    private BigDecimal fees24h;
    private BigDecimal volatility24h;
}