package com.finmates.predictions.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.finmates.predictions.model.PriceData;
import com.finmates.predictions.util.RateLimiter;
import org.web3j.abi.datatypes.generated.Int24;
import org.web3j.abi.datatypes.generated.Uint16;
import org.web3j.abi.datatypes.generated.Uint160;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.*;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import java.util.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import java.math.RoundingMode;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;

@Slf4j
@Service
public class UniswapDataService {

    // Constants for data fetching
    private static final int BLOCKS_PER_DAY = 7200;
    private static final int PRICE_DECIMAL_PLACES = 2;
    private static final int MAX_REQUESTS_PER_MINUTE = 25;
    private static final int BATCH_SIZE = 4;
    private static final long RATE_LIMIT_WAIT = 60_000; // 60 seconds

    // Constants for synthetic data generation
    private static final double DEFAULT_ETH_PRICE = 2000.0;
    private static final double DEFAULT_LIQUIDITY = 5000000.0;
    private static final double VOLATILITY_PERCENTAGE = 0.02;
    private static final double PRICE_CHANGE_FACTOR = 20.0;
    private static final double LIQUIDITY_VARIATION = 1000000.0;
    private static final double MEAN_REVERSION_FACTOR = 0.05;

    private final Web3j web3j;
    private final RateLimiter rateLimiter;
    private final ExecutorService executorService;

    @Value("${uniswap.v3.pool.address}")
    private String uniswapPoolAddress;

    @Getter
    private boolean usingSimulatedData = false;

    public UniswapDataService(Web3j web3j) {
        this.web3j = web3j;
        this.rateLimiter = new RateLimiter(MAX_REQUESTS_PER_MINUTE);
        this.executorService = Executors.newFixedThreadPool(BATCH_SIZE);
    }

    public List<PriceData> getHistoricalData(int days) {
        List<PriceData> historicalData = new ArrayList<>();

        try {
            BigInteger currentBlock = web3j.ethBlockNumber().send().getBlockNumber();
            BigInteger startBlock = currentBlock.subtract(BigInteger.valueOf(days * BLOCKS_PER_DAY));

            // Calculate block interval to get roughly 24 data points per day
            BigInteger blockRange = currentBlock.subtract(startBlock);
            BigInteger interval = blockRange.divide(BigInteger.valueOf(days * 24L));

            List<CompletableFuture<PriceData>> futures = new ArrayList<>();

            for (BigInteger block = startBlock; block.compareTo(currentBlock) <= 0; block = block.add(interval)) {
                final BigInteger blockNumber = block;
                CompletableFuture<PriceData> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        rateLimiter.acquire();
                        return fetchPriceData(blockNumber);
                    } catch (Exception e) {
                        if (e.getMessage() != null && e.getMessage().contains("429")) {
                            log.warn("Rate limit hit, waiting {} seconds", RATE_LIMIT_WAIT / 1000);
                            try {
                                Thread.sleep(RATE_LIMIT_WAIT);
                                rateLimiter.reset();
                                return fetchPriceData(blockNumber);
                            } catch (Exception retryEx) {
                                log.error("Retry failed for block {}", blockNumber, retryEx);
                            }
                        }
                        return null;
                    }
                }, executorService);
                futures.add(future);
            }

            // Wait for all futures to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(5, TimeUnit.MINUTES)
                    .join();

            // Collect results
            for (CompletableFuture<PriceData> future : futures) {
                try {
                    PriceData data = future.get();
                    if (data != null) {
                        historicalData.add(data);
                    }
                } catch (Exception e) {
                    log.warn("Failed to get data from future", e);
                }
            }

            if (!historicalData.isEmpty()) {
                usingSimulatedData = false;
                historicalData.sort(Comparator.comparingLong(PriceData::getTimestamp));
                log.info("Successfully fetched {} real data points", historicalData.size());
            } else {
                throw new Exception("No real data could be fetched");
            }

        } catch (Exception e) {
            log.error("Error fetching real data, falling back to synthetic data", e);
            generateSyntheticData(historicalData, days);
            usingSimulatedData = true;
        }

        return historicalData;
    }

    private PriceData fetchPriceData(BigInteger blockNumber) throws Exception {
        Function slot0Function = new Function("slot0",
                Collections.emptyList(),
                Arrays.asList(
                        new TypeReference<Uint160>() {}, // sqrtPriceX96
                        new TypeReference<Int24>() {}, // tick
                        new TypeReference<Uint16>() {}, // observationIndex
                        new TypeReference<Uint16>() {}, // observationCardinality
                        new TypeReference<Uint16>() {}, // observationCardinalityNext
                        new TypeReference<Uint8>() {}, // feeProtocol
                        new TypeReference<Bool>() {} // unlocked
                ));

        String encodedSlot0 = FunctionEncoder.encode(slot0Function);

        EthCall response = web3j.ethCall(
                Transaction.createEthCallTransaction(
                        null,
                        uniswapPoolAddress,
                        encodedSlot0
                ),
                DefaultBlockParameter.valueOf(blockNumber)
        ).send();

        if (response.hasError()) {
            throw new Exception(response.getError().getMessage());
        }

        List<Type> decoded = FunctionReturnDecoder.decode(
                response.getValue(),
                slot0Function.getOutputParameters()
        );

        BigInteger sqrtPriceX96 = (BigInteger) decoded.get(0).getValue();
        double price = calculatePrice(sqrtPriceX96);

        return new PriceData(
                formatPrice(price),
                DEFAULT_LIQUIDITY,
                blockNumber.longValue() * 12
        );
    }

    private double calculatePrice(BigInteger sqrtPriceX96) {
        BigDecimal base = BigDecimal.valueOf(2);
        BigDecimal sqrt = new BigDecimal(sqrtPriceX96)
                .divide(base.pow(96), 18, RoundingMode.HALF_UP);

        BigDecimal price = sqrt.multiply(sqrt);

        // Adjust for token decimals (USDT=6, ETH=18)
        BigDecimal decimalAdjustment = base.pow(6 - 18);
        return price.multiply(decimalAdjustment)
                .setScale(PRICE_DECIMAL_PLACES, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private void generateSyntheticData(List<PriceData> historicalData, int days) {
        double price = DEFAULT_ETH_PRICE;
        double trend = 0;
        long currentTime = Instant.now().getEpochSecond();
        Random random = new Random();

        for (int i = 0; i < days * 24; i++) {
            // Update trend
            trend = trend * (1 - MEAN_REVERSION_FACTOR) +
                    (random.nextDouble() - 0.5) * VOLATILITY_PERCENTAGE;

            // Calculate price movement
            double randomWalk = (random.nextDouble() - 0.5) * PRICE_CHANGE_FACTOR;
            price = price * (1 + trend) + randomWalk;

            // Ensure price stays positive and reasonable
            price = Math.max(100, Math.min(10000, price));

            // Generate liquidity with some variation
            double liquidity = DEFAULT_LIQUIDITY +
                    (random.nextDouble() - 0.5) * LIQUIDITY_VARIATION;

            historicalData.add(new PriceData(
                    formatPrice(price),
                    formatPrice(liquidity),
                    currentTime - (i * 3600)
            ));
        }

        log.info("Generated {} synthetic data points", historicalData.size());
    }

    private double formatPrice(double price) {
        return BigDecimal.valueOf(price)
                .setScale(PRICE_DECIMAL_PLACES, RoundingMode.HALF_UP)
                .doubleValue();
    }

    @PreDestroy
    public void cleanup() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
