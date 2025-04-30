package com.uniswap.predictor.service;

import com.uniswap.predictor.dto.PoolDataPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
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

    @Value("${async.timeout.seconds:10}")
    private long asyncTimeoutSeconds;

    @Autowired
    public DataCollectionService(BlockchainService blockchainService, GraphQLService graphQLService) {
        this.blockchainService = blockchainService;
        this.graphQLService = graphQLService;
    }

    public PoolDataPoint getCurrentPoolData(String poolAddress) {
        try {
            log.debug("Fetching current pool data for {}", poolAddress);

            // Get on-chain pool state
            BlockchainService.PoolState poolState = blockchainService.getPoolState(poolAddress);

            // Calculate prices using token decimals (now properly adjusted in BlockchainService)
            double token0Price = blockchainService.calculatePrice(poolState.getSqrtPriceX96(), poolAddress, true);
            double token1Price = blockchainService.calculatePrice(poolState.getSqrtPriceX96(), poolAddress, false);

            // Fetch additional data from The Graph asynchronously
            CompletableFuture<Double> volume24hFuture = CompletableFuture.supplyAsync(() ->
                    graphQLService.getVolume24h(poolAddress));

            CompletableFuture<Double> fees24hFuture = CompletableFuture.supplyAsync(() ->
                    graphQLService.getFees24h(poolAddress));

            CompletableFuture<Double> volatility24hFuture = CompletableFuture.supplyAsync(() ->
                    graphQLService.getVolatility24h(poolAddress));

            // Wait for all futures to complete with timeout
            Double volume24h = volume24hFuture.get(asyncTimeoutSeconds, TimeUnit.SECONDS);
            Double fees24h = fees24hFuture.get(asyncTimeoutSeconds, TimeUnit.SECONDS);
            Double volatility24h = volatility24hFuture.get(asyncTimeoutSeconds, TimeUnit.SECONDS);

            // Create the data point with all collected information
            PoolDataPoint dataPoint = PoolDataPoint.builder()
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

            log.debug("Retrieved current pool data for {}: price={}, tick={}",
                    poolAddress, token0Price, poolState.getTick());

            return dataPoint;

        } catch (IOException | InterruptedException | ExecutionException | TimeoutException e) {
            log.error("Error getting pool data for {}: {}", poolAddress, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Error getting pool data for pool: " + poolAddress, e);
        }
    }


    /**
     * Collect historical data with pagination support for longer timeframes
     */
    public List<PoolDataPoint> collectHistoricalData(String poolAddress, Instant startTime, Instant endTime) {
        try {
            log.debug("Collecting historical data for {} from {} to {}",
                    poolAddress, startTime, endTime);

            List<PoolDataPoint> historicalData = new ArrayList<>();

            // Calculate how many days we're requesting
            long daysDifference = Duration.between(startTime, endTime).toDays();

            // For large time periods, paginate the requests to avoid timeouts
            if (daysDifference > 7) {
                // Collect data in 7-day chunks
                Instant chunkStart = startTime;
                while (chunkStart.isBefore(endTime)) {
                    // Calculate end of this chunk (either 7 days later or endTime)
                    Instant chunkEnd = chunkStart.plus(Duration.ofDays(7));
                    if (chunkEnd.isAfter(endTime)) {
                        chunkEnd = endTime;
                    }

                    log.debug("Collecting data chunk from {} to {}", chunkStart, chunkEnd);

                    // Fetch this chunk
                    List<PoolDataPoint> chunkData = fetchHistoricalDataChunk(
                            poolAddress, chunkStart, chunkEnd);

                    historicalData.addAll(chunkData);

                    // Move to next chunk
                    chunkStart = chunkEnd;
                }
            } else {
                // For shorter periods, fetch in one request
                historicalData = fetchHistoricalDataChunk(poolAddress, startTime, endTime);
            }

            log.debug("Collected {} historical data points for {}",
                    historicalData.size(), poolAddress);

            return historicalData;
        } catch (Exception e) {
            log.error("Error collecting historical data for pool {}: {}",
                    poolAddress, e.getMessage());
            throw new RuntimeException("Error collecting historical data", e);
        }
    }

    /**
     * Fetch a single chunk of historical data
     */
    private List<PoolDataPoint> fetchHistoricalDataChunk(
            String poolAddress, Instant startTime, Instant endTime) {
        try {
            List<PoolDataPoint> result = new ArrayList<>();

            // Fetch historical swap and liquidity events asynchronously
            CompletableFuture<List<GraphQLService.SwapEvent>> swapsFuture =
                    CompletableFuture.supplyAsync(() -> graphQLService.getHistoricalSwaps(
                            poolAddress, startTime.getEpochSecond(), endTime.getEpochSecond()));

            CompletableFuture<List<GraphQLService.LiquidityEvent>> liquidityFuture =
                    CompletableFuture.supplyAsync(() -> graphQLService.getHistoricalLiquidity(
                            poolAddress, startTime.getEpochSecond(), endTime.getEpochSecond()));

            // Wait for futures to complete with timeout
            List<GraphQLService.SwapEvent> swapEvents =
                    swapsFuture.get(asyncTimeoutSeconds, TimeUnit.SECONDS);

            List<GraphQLService.LiquidityEvent> liquidityEvents =
                    liquidityFuture.get(asyncTimeoutSeconds, TimeUnit.SECONDS);

            // Process the retrieved events
            processHistoricalEvents(result, swapEvents, liquidityEvents, poolAddress);

            return result;
        } catch (Exception e) {
            log.error("Error fetching data chunk: {}", e.getMessage());
            throw new RuntimeException("Error fetching historical data chunk", e);
        }
    }



    private void processHistoricalEvents(List<PoolDataPoint> result,
                                         List<GraphQLService.SwapEvent> swapEvents,
                                         List<GraphQLService.LiquidityEvent> liquidityEvents,
                                         String poolAddress) {
        try {
            // Process each swap event and convert to a PoolDataPoint
            for (GraphQLService.SwapEvent swap : swapEvents) {
                // Use BlockchainService to calculate prices with proper decimal adjustment
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

                // Create data point with the event data and calculated metrics
                PoolDataPoint dataPoint = PoolDataPoint.builder()
                        .timestamp(swap.getTimestamp())
                        .sqrtPriceX96(swap.getSqrtPriceX96())
                        .tick(swap.getTick())
                        .token0Price(BigDecimal.valueOf(token0Price))
                        .token1Price(BigDecimal.valueOf(token1Price))
                        .liquidity(new BigDecimal(swap.getLiquidity()))
                        .volume24h(BigDecimal.valueOf(calculateVolumeForEvent(swap)))
                        .fees24h(BigDecimal.valueOf(calculateFeesForEvent(swap, poolAddress)))
                        .volatility24h(BigDecimal.valueOf(calculateVolatilityForEvent(swap, result)))
                        .build();

                result.add(dataPoint);
            }

            // Sort the results by timestamp
            result.sort((a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()));

        } catch (Exception e) {
            log.error("Error processing historical events for pool {}: {}", poolAddress, e.getMessage());
            throw new RuntimeException("Error processing historical events for pool: " + poolAddress, e);
        }
    }

    private double calculateVolumeForEvent(GraphQLService.SwapEvent swap) {
        return swap.getAmountUSD().doubleValue();
    }

    private double calculateFeesForEvent(GraphQLService.SwapEvent swap, String poolAddress) {
        try {
            // Get the fee tier dynamically instead of hardcoding 0.003 (0.3%)
            int feeTier = getFeePercentageForPool(poolAddress);
            return swap.getAmountUSD().multiply(BigDecimal.valueOf(feeTier / 1000000.0)).doubleValue();
        } catch (Exception e) {
            // Fallback to 0.3% if fee tier can't be determined
            return swap.getAmountUSD().multiply(new BigDecimal("0.003")).doubleValue();
        }
    }

    // Helper method to get fee percentage for a pool
    private int getFeePercentageForPool(String poolAddress) {
        try {
            // This method would ideally call blockchainService to get the fee tier
            // For now, defaulting to 3000 (0.3%) as a common fee tier
            return 3000;
        } catch (Exception e) {
            log.warn("Failed to determine fee tier for pool {}, using default 0.3%", poolAddress);
            return 3000; // 0.3% default fee tier
        }
    }

    private double calculateVolatilityForEvent(GraphQLService.SwapEvent currentSwap, List<PoolDataPoint> previousData) {
        if (previousData.isEmpty()) {
            return 0.0;
        }

        // Calculate volatility based on price returns
        List<Double> returns = new ArrayList<>();
        double currentPrice = currentSwap.getSqrtPriceX96().doubleValue();

        // Consider data points from the last 24 hours
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

        // Calculate volatility as standard deviation of log returns
        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = returns.stream()
                .mapToDouble(r -> Math.pow(r - mean, 2))
                .average()
                .orElse(0.0);

        return Math.sqrt(variance);
    }
}