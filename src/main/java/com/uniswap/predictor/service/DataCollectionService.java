package com.uniswap.predictor.service;

import com.uniswap.predictor.dto.PoolDataPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class DataCollectionService {
    private final BlockchainService blockchainService;
    private final GraphQLService graphQLService;
    private static final long ASYNC_TIMEOUT_SECONDS = 10;

    @Autowired
    public DataCollectionService(BlockchainService blockchainService, GraphQLService graphQLService) {
        this.blockchainService = blockchainService;
        this.graphQLService = graphQLService;
    }

    public PoolDataPoint getCurrentPoolData(String poolAddress) {
        try {
            BlockchainService.PoolState poolState = blockchainService.getPoolState(poolAddress);

            double token0Price = blockchainService.calculatePrice(poolState.getSqrtPriceX96(), poolAddress, true);
            double token1Price = blockchainService.calculatePrice(poolState.getSqrtPriceX96(), poolAddress, false);

            CompletableFuture<Double> volume24hFuture = CompletableFuture.supplyAsync(() ->
                    graphQLService.getVolume24h(poolAddress));

            CompletableFuture<Double> fees24hFuture = CompletableFuture.supplyAsync(() ->
                    graphQLService.getFees24h(poolAddress));

            CompletableFuture<Double> volatility24hFuture = CompletableFuture.supplyAsync(() ->
                    graphQLService.getVolatility24h(poolAddress));

            Double volume24h = volume24hFuture.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Double fees24h = fees24hFuture.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Double volatility24h = volatility24hFuture.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            return PoolDataPoint.builder()
                    .timestamp(Instant.now())
                    .sqrtPriceX96(new BigDecimal(poolState.getSqrtPriceX96()))
                    .liquidity(new BigDecimal(poolState.getLiquidity()))
                    .tick(poolState.getTick())
                    .token0Price(BigDecimal.valueOf(token0Price))
                    .token1Price(BigDecimal.valueOf(token1Price))
                    .volume24h(BigDecimal.valueOf(volume24h))
                    .fees24h(BigDecimal.valueOf(fees24h))
                    .volatility24h(BigDecimal.valueOf(volatility24h))
                    .build();

        } catch (IOException | InterruptedException | ExecutionException | TimeoutException e) {
            log.error("Error getting pool data for {}: {}", poolAddress, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Error getting pool data", e);
        }
    }

    public List<PoolDataPoint> collectHistoricalData(String poolAddress, Instant startTime, Instant endTime) {
        try {
            List<PoolDataPoint> historicalData = new ArrayList<>();

            CompletableFuture<List<GraphQLService.SwapEvent>> swapsFuture = CompletableFuture.supplyAsync(() ->
                    graphQLService.getHistoricalSwaps(poolAddress, startTime.getEpochSecond(), endTime.getEpochSecond()));

            CompletableFuture<List<GraphQLService.LiquidityEvent>> liquidityFuture = CompletableFuture.supplyAsync(() ->
                    graphQLService.getHistoricalLiquidity(poolAddress, startTime.getEpochSecond(), endTime.getEpochSecond()));

            List<GraphQLService.SwapEvent> swapEvents = swapsFuture.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<GraphQLService.LiquidityEvent> liquidityEvents = liquidityFuture.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            processHistoricalEvents(historicalData, swapEvents, liquidityEvents, poolAddress);

            return historicalData;

        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            log.error("Error collecting historical data for pool {}: {}", poolAddress, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Error collecting historical data", e);
        }
    }

    private void processHistoricalEvents(List<PoolDataPoint> result,
                                         List<GraphQLService.SwapEvent> swapEvents,
                                         List<GraphQLService.LiquidityEvent> liquidityEvents,
                                         String poolAddress) {
        try {
            for (GraphQLService.SwapEvent swap : swapEvents) {
                double token0Price = blockchainService.calculatePrice(
                        swap.getSqrtPriceX96().toBigInteger(),
                        poolAddress,
                        true
                );

                double token1Price = blockchainService.calculatePrice(
                        swap.getSqrtPriceX96().toBigInteger(),
                        poolAddress,
                        false
                );

                PoolDataPoint dataPoint = PoolDataPoint.builder()
                        .timestamp(swap.getTimestamp())
                        .sqrtPriceX96(swap.getSqrtPriceX96())
                        .tick(swap.getTick())
                        .token0Price(BigDecimal.valueOf(token0Price))
                        .token1Price(BigDecimal.valueOf(token1Price))
                        .liquidity(new BigDecimal(swap.getLiquidity()))
                        .volume24h(BigDecimal.valueOf(calculateVolumeForEvent(swap)))
                        .fees24h(BigDecimal.valueOf(calculateFeesForEvent(swap)))
                        .volatility24h(BigDecimal.valueOf(calculateVolatilityForEvent(swap, result)))
                        .build();

                result.add(dataPoint);
            }

            result.sort((a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()));

        } catch (Exception e) {
            log.error("Error processing historical events for pool {}: {}", poolAddress, e.getMessage());
            throw new RuntimeException("Error processing historical events", e);
        }
    }

    private double calculateVolumeForEvent(GraphQLService.SwapEvent swap) {
        return swap.getAmountUSD().doubleValue();
    }

    private double calculateFeesForEvent(GraphQLService.SwapEvent swap) {
        return swap.getAmountUSD().multiply(new BigDecimal("0.003")).doubleValue();
    }

    private double calculateVolatilityForEvent(GraphQLService.SwapEvent currentSwap, List<PoolDataPoint> previousData) {
        if (previousData.isEmpty()) {
            return 0.0;
        }

        List<Double> returns = new ArrayList<>();
        double currentPrice = currentSwap.getSqrtPriceX96().doubleValue();

        for (PoolDataPoint point : previousData) {
            if (currentSwap.getTimestamp() - point.getTimestamp() <= 24 * 3600) {
                double previousPrice = point.getSqrtPriceX96().doubleValue();
                if (previousPrice > 0) {
                    returns.add(Math.log(currentPrice / previousPrice));
                }
            }
        }

        if (returns.isEmpty()) {
            return 0.0;
        }

        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = returns.stream()
                .mapToDouble(r -> Math.pow(r - mean, 2))
                .average()
                .orElse(0.0);

        return Math.sqrt(variance);
    }
}
