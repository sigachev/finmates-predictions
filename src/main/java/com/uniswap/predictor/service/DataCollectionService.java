// ----- Data Collection Service -----
package com.uniswap.predictor.service;

import com.uniswap.predictor.dto.PoolDataPoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@Service
public class DataCollectionService {

    private final com.uniswap.predictor.service.BlockchainService blockchainService;
    private final GraphQLService graphQLService;

    @Autowired
    public DataCollectionService(com.uniswap.predictor.service.BlockchainService blockchainService, GraphQLService graphQLService) {
        this.blockchainService = blockchainService;
        this.graphQLService = graphQLService;
    }

    public PoolDataPoint getCurrentPoolData(String poolAddress) {
        try {
            com.uniswap.predictor.service.BlockchainService.PoolState poolState = blockchainService.getPoolState(poolAddress);

            double token0Price = blockchainService.calculatePrice(poolState.getSqrtPriceX96(), true);
            double token1Price = blockchainService.calculatePrice(poolState.getSqrtPriceX96(), false);

            // Get recent volumes and fees in parallel
            CompletableFuture<Double> volume24hFuture = CompletableFuture.supplyAsync(() ->
                    graphQLService.getVolume24h(poolAddress));

            CompletableFuture<Double> fees24hFuture = CompletableFuture.supplyAsync(() ->
                    graphQLService.getFees24h(poolAddress));

            CompletableFuture<Double> volatility24hFuture = CompletableFuture.supplyAsync(() ->
                    graphQLService.getVolatility24h(poolAddress));

            return PoolDataPoint.builder()
                    .timestamp(Instant.now())
                    .sqrtPriceX96(new BigDecimal(poolState.getSqrtPriceX96()))
                    .liquidity(poolState.getLiquidity())
                    .tick(poolState.getTick())
                    .token0Price(BigDecimal.valueOf(token0Price))
                    .token1Price(BigDecimal.valueOf(token1Price))
                    .volume24h(BigDecimal.valueOf(volume24hFuture.get()))
                    .fees24h(BigDecimal.valueOf(fees24hFuture.get()))
                    .volatility24h(BigDecimal.valueOf(volatility24hFuture.get()))
                    .build();

        } catch (IOException | InterruptedException | ExecutionException e) {
            throw new RuntimeException("Error getting pool data", e);
        }
    }

    public List<PoolDataPoint> collectHistoricalData(String poolAddress, Instant startTime, Instant endTime) {
        // Use GraphQL queries to get historical data from Uniswap subgraph
        List<PoolDataPoint> historicalData = new ArrayList<>();

        // Get historical swap events
        List<GraphQLService.SwapEvent> swapEvents = graphQLService.getHistoricalSwaps(
                poolAddress,
                startTime.getEpochSecond(),
                endTime.getEpochSecond()
        );

        // Get historical liquidity change events
        List<GraphQLService.LiquidityEvent> liquidityEvents = graphQLService.getHistoricalLiquidity(
                poolAddress,
                startTime.getEpochSecond(),
                endTime.getEpochSecond()
        );

        // Combine and process events into a coherent time series
        processHistoricalEvents(historicalData, swapEvents, liquidityEvents);

        return historicalData;
    }

    private void processHistoricalEvents(List<PoolDataPoint> result,
                                         List<GraphQLService.SwapEvent> swapEvents,
                                         List<GraphQLService.LiquidityEvent> liquidityEvents) {
        // Process events chronologically and build a time series
        // (Simplified implementation - a real system would need more sophisticated processing)

        // For demonstration, we'll just convert swap events to data points
        for (GraphQLService.SwapEvent swap : swapEvents) {
            PoolDataPoint dataPoint = PoolDataPoint.builder()
                    .timestamp(Instant.ofEpochSecond(swap.getTimestamp()))
                    .sqrtPriceX96(swap.getSqrtPriceX96())
                    .tick(swap.getTick())
                    .token0Price(calculatePrice(swap.getSqrtPriceX96()))
                    .token1Price(BigDecimal.ONE.divide(calculatePrice(swap.getSqrtPriceX96()), 18, BigDecimal.ROUND_HALF_UP))
                    .liquidity(swap.getLiquidity())
                    .build();

            result.add(dataPoint);
        }
    }

    private BigDecimal calculatePrice(BigDecimal sqrtPriceX96) {
        BigDecimal q96 = new BigDecimal(BigInteger.ONE.shiftLeft(96));
        BigDecimal sqrtPrice = sqrtPriceX96.divide(q96, 18, BigDecimal.ROUND_HALF_UP);
        return sqrtPrice.multiply(sqrtPrice);
    }
}
