// ----- Data Collection Service -----
package com.uniswap.predictor.service;

import com.uniswap.predictor.dto.PoolDataPoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@Service
public class DataCollectionService {

    private final BlockchainService blockchainService;
    private final GraphQLService graphQLService;

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
        processHistoricalEvents(historicalData, swapEvents, liquidityEvents, poolAddress);

        return historicalData;
    }

    private void processHistoricalEvents(List<PoolDataPoint> result,
                                       List<GraphQLService.SwapEvent> swapEvents,
                                       List<GraphQLService.LiquidityEvent> liquidityEvents,
                                       String poolAddress) {
        // Process events chronologically and build a time series
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
                    .timestamp(Instant.ofEpochSecond(swap.getTimestamp()))
                    .sqrtPriceX96(swap.getSqrtPriceX96())
                    .tick(swap.getTick())
                    .token0Price(BigDecimal.valueOf(token0Price))
                    .token1Price(BigDecimal.valueOf(token1Price))
                    .liquidity(swap.getLiquidity())
                    .build();

            result.add(dataPoint);
        }
    }
}