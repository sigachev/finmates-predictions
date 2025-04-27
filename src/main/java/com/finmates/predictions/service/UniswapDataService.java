package com.finmates.predictions.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.retry.support.RetryTemplate;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import com.finmates.predictions.model.PriceData;
import org.web3j.abi.datatypes.generated.*;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGetCode;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.*;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.web3j.protocol.http.HttpService;
import okhttp3.OkHttpClient;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.util.*;
import java.util.concurrent.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.io.IOException;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;

@Slf4j
@Service
public class UniswapDataService {

    // Blockchain and request constants
    private static final int BLOCKS_PER_DAY = 7200;
    private static final int BATCH_SIZE = 4;
    private static final long REQUEST_TIMEOUT = 10;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY = 1000;

    // Price calculation constants
    private static final int CALCULATION_SCALE = 32;
    private static final int PRICE_DECIMAL_PLACES = 2;
    private static final double DEFAULT_ETH_PRICE = 2000.0;
    private static final double MIN_VALID_PRICE = 100.0;  // $100
    private static final double MAX_VALID_PRICE = 10000.0; // $10,000

    // Token decimals
    private static final int USDT_DECIMALS = 6;
    private static final int ETH_DECIMALS = 18;

    // Price and liquidity constants
    private static final double BASE_LIQUIDITY = 5000000.0;
    private static final double VOLATILITY_PERCENTAGE = 0.02;
    private static final double LIQUIDITY_VARIATION = 1000000.0;
    private static final double MEAN_REVERSION_FACTOR = 0.05;
    private static final int HOURS_IN_DAY = 24;


    // Known Uniswap V3 pool addresses
    private static final String ETH_USDT_POOL = "0x641C00A822e8b671738912358321de0A31C20A70";
    private static final String ETH_USDC_POOL = "0xC31E54c7a869B9FcBEcc14363CF510d1c41fa443";
    private static final String WBTC_ETH_POOL = "0x2f5e87C9312fa29aed5c179E456625D79015299c";

    // Dependencies
    private final Web3j web3j;
    private final ExecutorService executorService;
    private final Random random;
    private final Map<BigInteger, PriceData> priceCache;
    private final CircuitBreaker circuitBreaker;
    private final RetryTemplate retryTemplate;
    private final MetricsService metricsService;
    private final com.finmates.predictions.util.RateLimiter rateLimiter;
    private final CacheManager cacheManager;

    @Value("${ethereum.node.url:https://arb1.arbitrum.io/rpc}")
    private String ethereumNodeUrl;

    @Value("${web3j.http-timeout:10000}")
    private long web3jHttpTimeout;

    @Value("${uniswap.v3.pool.address}")
    private String uniswapPoolAddress;

    @Value("${retry.max-attempts:3}")
    private int maxRetries;

    @Value("${retry.initial-delay:1000}")
    private long initialRetryDelay;

    @Value("${retry.max-delay:10000}")
    private long maxRetryDelay;

    @Getter
    private boolean usingSimulatedData = true;

    public UniswapDataService(Web3j web3j,
                              CircuitBreaker circuitBreaker,
                              RetryTemplate retryTemplate,
                              MetricsService metricsService,
                              com.finmates.predictions.util.RateLimiter rateLimiter,
                              CacheManager cacheManager) {
        this.web3j = web3j;
        this.circuitBreaker = circuitBreaker;
        this.retryTemplate = retryTemplate;
        this.metricsService = metricsService;
        this.rateLimiter = rateLimiter;
        this.cacheManager = cacheManager;
        this.executorService = Executors.newFixedThreadPool(BATCH_SIZE);
        this.random = new Random();
        this.priceCache = new ConcurrentHashMap<>();
    }

    @PostConstruct
    public void validateContract() {
        log.info("Validating Uniswap V3 pool contract at address: {}", uniswapPoolAddress);

        if (!isKnownPoolAddress(uniswapPoolAddress)) {
            log.warn("Unknown pool address: {}. Please verify the address.", uniswapPoolAddress);
        }

        try {
            // Check if contract exists
            EthGetCode ethGetCode = web3j.ethGetCode(uniswapPoolAddress, DefaultBlockParameterName.LATEST).send();
            if (ethGetCode.getCode().equals("0x")) {
                log.error("No contract found at address: {}", uniswapPoolAddress);
                usingSimulatedData = true;
                return;
            }

            // Try to fetch initial price to validate contract interface
            BigInteger currentBlock = web3j.ethBlockNumber().send().getBlockNumber();
            PriceData initialPrice = fetchPriceDataFromBlock(currentBlock);

            if (initialPrice != null && initialPrice.getPrice() > 0) {
                log.info("Successfully validated Uniswap V3 pool contract. Initial price: {}",
                        initialPrice.getPrice());
                usingSimulatedData = false;
            } else {
                log.warn("Could not fetch initial price, falling back to simulation mode");
                usingSimulatedData = true;
            }

        } catch (Exception e) {
            log.error("Failed to validate Uniswap V3 pool contract: {}. Falling back to simulation mode",
                    e.getMessage());
            usingSimulatedData = true;
        }
    }

    private boolean isKnownPoolAddress(String address) {
        return ETH_USDT_POOL.equalsIgnoreCase(address) ||
                ETH_USDC_POOL.equalsIgnoreCase(address) ||
                WBTC_ETH_POOL.equalsIgnoreCase(address);
    }

    @Cacheable(value = "historicalData", key = "#days", unless = "#result.isEmpty()")
    public List<PriceData> getHistoricalData(int days) {
        long startTime = System.currentTimeMillis();
        List<PriceData> historicalData = new ArrayList<>();

        try {
            List<PriceData> realData = circuitBreaker.executeSupplier(() -> fetchRealData(days));

            if (!realData.isEmpty()) {
                historicalData = realData;
                usingSimulatedData = false;
                log.info("Successfully fetched {} real data points in {}ms",
                        historicalData.size(), System.currentTimeMillis() - startTime);
            } else {
                generateSyntheticData(historicalData, days);
                usingSimulatedData = true;
                log.info("Generated {} synthetic data points in {}ms",
                        historicalData.size(), System.currentTimeMillis() - startTime);
            }

            metricsService.recordDataSourceType(usingSimulatedData);
            return historicalData;

        } catch (Exception e) {
            log.error("Error fetching data, using synthetic data", e);
            generateSyntheticData(historicalData, days);
            usingSimulatedData = true;
            metricsService.recordDataSourceType(true);
            return historicalData;
        }
    }

    private List<PriceData> fetchRealData(int days) {
        List<PriceData> realData = new ArrayList<>();

        try {
            BigInteger currentBlock = web3j.ethBlockNumber().send().getBlockNumber();

            // Limit to last 1000 blocks (about 30 minutes on Arbitrum)
            int maxBlocksBack = 1000;
            BigInteger startBlock = currentBlock.subtract(BigInteger.valueOf(maxBlocksBack));

            log.info("Fetching price data from block {} to {}", startBlock, currentBlock);

            // Get 12 data points (every ~2.5 minutes)
            BigInteger interval = BigInteger.valueOf(maxBlocksBack / 12);

            List<CompletableFuture<PriceData>> futures = new ArrayList<>();
            int batchCount = 0;

            for (BigInteger block = currentBlock; block.compareTo(startBlock) >= 0; block = block.subtract(interval)) {
                if (!rateLimiter.tryAcquire()) {
                    log.warn("Rate limit exceeded, waiting...");
                    Thread.sleep(initialRetryDelay);
                    continue;
                }

                final BigInteger blockNumber = block;
                CompletableFuture<PriceData> future = CompletableFuture.supplyAsync(
                        () -> fetchPriceDataWithRetry(blockNumber),
                        executorService
                );
                futures.add(future);

                batchCount++;
                if (batchCount >= BATCH_SIZE) {
                    int successCount = processBatch(futures, realData);
                    futures.clear();
                    batchCount = 0;

                    if (successCount == 0 && realData.isEmpty()) {
                        log.warn("No successful data fetches yet, switching to simulation mode");
                        return Collections.emptyList();
                    }
                }
            }

            if (!futures.isEmpty()) {
                processBatch(futures, realData);
            }

            if (!realData.isEmpty()) {
                realData.sort(Comparator.comparingLong(PriceData::getTimestamp));
                extrapolateHistoricalData(realData, days);
            }

            return realData;

        } catch (Exception e) {
            log.error("Failed to fetch real data", e);
            return Collections.emptyList();
        }
    }


    private int processBatch(List<CompletableFuture<PriceData>> futures, List<PriceData> realData) {
        int successCount = 0;
        try {
            // Wait for all futures to complete or timeout
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(REQUEST_TIMEOUT, TimeUnit.SECONDS);

            // Process each future
            for (CompletableFuture<PriceData> future : futures) {
                try {
                    PriceData data = future.get(1, TimeUnit.SECONDS);
                    if (data != null && isValidPrice(data.getPrice())) {
                        realData.add(data);
                        successCount++;
                    }
                } catch (Exception e) {
                    log.warn("Failed to get data from future: {}", e.getMessage());
                }
            }

            // Record metrics
            metricsService.recordBatchProcessing(successCount, futures.size());

            // Log batch results
            if (successCount > 0) {
                log.debug("Successfully processed {}/{} requests in batch",
                        successCount, futures.size());
            } else {
                log.warn("No successful requests in batch of {}", futures.size());
            }

        } catch (Exception e) {
            log.error("Batch processing error: {}", e.getMessage());
        }

        return successCount;
    }

    private void handleBatchResults(int successCount, int consecutiveFailures) throws InterruptedException {
        if (successCount == 0) {
            // Calculate exponential backoff delay
            long adaptiveDelay = initialRetryDelay * (long) Math.pow(2, Math.min(consecutiveFailures, 5));
            log.warn("No successful requests in batch, increasing delay to {}ms", adaptiveDelay);
            Thread.sleep(adaptiveDelay);
        } else {
            // Reset delay on successful batch
            Thread.sleep(initialRetryDelay);
            log.debug("Batch processed with {} successful requests", successCount);
        }
    }

    // Helper method to validate batch data
    private boolean isValidBatchData(PriceData data) {
        if (data == null) return false;

        // Check price validity
        if (!isValidPrice(data.getPrice())) {
            log.warn("Invalid price in batch data: {}", data.getPrice());
            return false;
        }

        // Check timestamp validity
        long currentTime = Instant.now().getEpochSecond();
        if (data.getTimestamp() > currentTime ||
                data.getTimestamp() < currentTime - (7 * 24 * 3600)) { // Older than 7 days
            log.warn("Invalid timestamp in batch data: {}", data.getTimestamp());
            return false;
        }

        return true;
    }

    // Helper method to handle batch errors
    private void handleBatchError(Exception e, int batchSize) {
        log.error("Batch processing failed: {}", e.getMessage());
        metricsService.recordBatchProcessing(0, batchSize);

        if (e instanceof TimeoutException) {
            metricsService.incrementErrorCount("batch_timeout");
        } else if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            metricsService.incrementErrorCount("batch_interrupted");
        } else {
            metricsService.incrementErrorCount("batch_error");
        }
    }


    private PriceData fetchPriceDataWithRetry(BigInteger blockNumber) {
        int retries = 0;
        Exception lastException = null;
        long delay = initialRetryDelay;

        while (retries <= maxRetries) {
            try {
                if (retries > 0) {
                    log.debug("Retry {} for block {}, waiting {}ms", retries, blockNumber, delay);
                    Thread.sleep(delay);
                    delay = Math.min(delay * 2, maxRetryDelay);
                }

                return fetchPriceDataFromBlock(blockNumber);

            } catch (Exception e) {
                lastException = e;
                retries++;
                log.warn("Attempt {} failed for block {}: {}", retries, blockNumber, e.getMessage());

                if (!isRetryableException(e) || retries > maxRetries) {
                    break;
                }
            }
        }

        log.error("Failed to fetch price data for block {} after {} retries",
                blockNumber, retries);

        metricsService.incrementErrorCount("price_fetch_failure");
        return getFallbackPrice(blockNumber);
    }

    private boolean isRetryableException(Exception e) {
        return e instanceof IOException ||
                e instanceof DataFetchException ||
                (e.getMessage() != null && (
                        e.getMessage().contains("timeout") ||
                                e.getMessage().contains("rate limit") ||
                                e.getMessage().contains("try again") ||
                                e.getMessage().contains("Empty response")
                ));
    }

    @Cacheable(value = "priceData", key = "#blockNumber")
    public PriceData fetchPriceDataFromBlock(BigInteger blockNumber) throws Exception {
        long startTime = System.currentTimeMillis();

        try {
            Function slot0Function = new Function("slot0",
                    Collections.emptyList(),
                    Arrays.asList(
                            new TypeReference<Uint160>() {
                            }, // sqrtPriceX96
                            new TypeReference<Int24>() {
                            }, // tick
                            new TypeReference<Uint16>() {
                            }, // observationIndex
                            new TypeReference<Uint16>() {
                            }, // observationCardinality
                            new TypeReference<Uint16>() {
                            }, // observationCardinalityNext
                            new TypeReference<Uint8>() {
                            }, // feeProtocol
                            new TypeReference<Bool>() {
                            } // unlocked
                    ));

            String encodedFunction = FunctionEncoder.encode(slot0Function);
            EthCall response = web3j.ethCall(
                    Transaction.createEthCallTransaction(null, uniswapPoolAddress, encodedFunction),
                    DefaultBlockParameter.valueOf(blockNumber)
            ).send();

            validateResponse(response, blockNumber);

            List<Type> decoded = FunctionReturnDecoder.decode(
                    response.getValue(),
                    slot0Function.getOutputParameters()
            );

            BigInteger sqrtPriceX96 = validateAndExtractPrice(decoded);
            double price = calculatePrice(sqrtPriceX96);

            // Verify the calculation
            verifyPriceCalculation(sqrtPriceX96, price);

            if (!isValidPrice(price)) {
                log.warn("Invalid price calculated for block {}: {}", blockNumber, price);
                return getFallbackPrice(blockNumber);
            }

            metricsService.recordBlockchainRequestMetrics(true,
                    System.currentTimeMillis() - startTime);

            return new PriceData(
                    formatPrice(price),
                    BASE_LIQUIDITY,
                    blockNumber.longValue() * 12
            );

        } catch (Exception e) {
            metricsService.recordBlockchainRequestMetrics(false,
                    System.currentTimeMillis() - startTime);
            throw e;
        }
    }


    private void validateResponse(EthCall response, BigInteger blockNumber) {
        if (response.hasError()) {
            throw new DataFetchException("RPC error for block " + blockNumber + ": "
                    + response.getError().getMessage());
        }

        String value = response.getValue();
        if (value == null || value.equals("0x")) {
            throw new DataFetchException("Empty response received for block " + blockNumber);
        }
    }

    private BigInteger validateAndExtractPrice(List<Type> decoded) {
        if (decoded == null || decoded.isEmpty()) {
            throw new DataFetchException("Failed to decode response");
        }

        Type sqrtPriceX96Type = decoded.get(0);
        if (sqrtPriceX96Type == null) {
            throw new DataFetchException("Null sqrtPrice received");
        }

        BigInteger sqrtPriceX96 = (BigInteger) sqrtPriceX96Type.getValue();
        if (sqrtPriceX96 == null || sqrtPriceX96.equals(BigInteger.ZERO)) {
            throw new DataFetchException("Invalid sqrtPrice: " + sqrtPriceX96);
        }

        return sqrtPriceX96;
    }

    private double calculatePrice(BigInteger sqrtPriceX96) {
        try {
            // Convert to BigDecimal with high precision
            BigDecimal sqrtPriceX96Decimal = new BigDecimal(sqrtPriceX96);
            BigDecimal twoPow96 = new BigDecimal(2).pow(96);

            // First calculate sqrtPrice with high precision
            BigDecimal sqrtPrice = sqrtPriceX96Decimal.divide(twoPow96, 128, RoundingMode.HALF_UP);

            // Square the sqrtPrice
            BigDecimal price = sqrtPrice.multiply(sqrtPrice);

            // Convert to standard ETH/USDT price
            // For ETH/USDT pool: token0 = USDT (6 decimals), token1 = WETH (18 decimals)
            // Price = (price * 10^(decimals0)) / 10^(decimals1)
            BigDecimal decimalAdjustment = BigDecimal.TEN.pow(ETH_DECIMALS - USDT_DECIMALS);
            price = price.multiply(decimalAdjustment);

            // Convert to double with proper rounding
            double finalPrice = price.setScale(PRICE_DECIMAL_PLACES, RoundingMode.HALF_UP).doubleValue();

            // Log calculation steps for debugging
            log.debug("Price calculation steps for sqrtPriceX96 {}: sqrtPrice = {}, rawPrice = {}, adjustedPrice = {}",
                    sqrtPriceX96, sqrtPrice, price, finalPrice);

            // Validate final price
            if (!isValidPrice(finalPrice)) {
                log.warn("Calculated price {} is outside valid range [{} - {}]",
                        finalPrice, MIN_VALID_PRICE, MAX_VALID_PRICE);
                return DEFAULT_ETH_PRICE;
            }

            return finalPrice;

        } catch (Exception e) {
            log.error("Error calculating price from sqrtPriceX96 {}: {}", sqrtPriceX96, e.getMessage());
            return DEFAULT_ETH_PRICE;
        }
    }

    private boolean isValidPrice(double price) {
        if (Double.isInfinite(price) || Double.isNaN(price)) {
            return false;
        }

        // For ETH/USDT, reasonable price range is $1000 - $5000
        return price >= MIN_VALID_PRICE && price <= MAX_VALID_PRICE;
    }


    private PriceData getFallbackPrice(BigInteger blockNumber) {
        try {
            Cache priceCache = cacheManager.getCache("priceData");
            if (priceCache != null) {
                for (BigInteger i = blockNumber.subtract(BigInteger.ONE);
                     i.compareTo(blockNumber.subtract(BigInteger.valueOf(10))) > 0;
                     i = i.subtract(BigInteger.ONE)) {
                    PriceData cachedPrice = priceCache.get(i, PriceData.class);
                    if (cachedPrice != null) {
                        double variation = (random.nextDouble() - 0.5) * 0.001; // 0.1% variation
                        return new PriceData(
                                formatPrice(cachedPrice.getPrice() * (1 + variation)),
                                BASE_LIQUIDITY,
                                blockNumber.longValue() * 12
                        );
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get fallback price from cache", e);
        }

        double fallbackPrice = DEFAULT_ETH_PRICE * (1 + (random.nextDouble() - 0.5) * 0.01);
        return new PriceData(
                formatPrice(fallbackPrice),
                BASE_LIQUIDITY,
                blockNumber.longValue() * 12
        );
    }

    private void extrapolateHistoricalData(List<PriceData> realData, int requestedDays) {
        if (realData.isEmpty()) return;

        long currentTime = Instant.now().getEpochSecond();
        long oldestRequiredTime = currentTime - (requestedDays * 24 * 3600);
        List<PriceData> extrapolated = new ArrayList<>();

        // Get the most recent real price
        PriceData latestData = realData.get(realData.size() - 1);
        double currentPrice = latestData.getPrice();

        // Calculate volatility from real data
        double volatility = calculateVolatility(realData);

        // Generate historical data
        long timestamp = currentTime;
        while (timestamp >= oldestRequiredTime) {
            // Add some randomness based on observed volatility
            double priceChange = random.nextGaussian() * volatility;
            currentPrice = currentPrice * (1 + priceChange);

            // Keep price within reasonable bounds
            currentPrice = Math.max(MIN_VALID_PRICE, Math.min(MAX_VALID_PRICE, currentPrice));

            extrapolated.add(new PriceData(
                    formatPrice(currentPrice),
                    BASE_LIQUIDITY,
                    timestamp
            ));

            timestamp -= 3600; // One hour intervals
        }

        realData.clear();
        realData.addAll(extrapolated);
    }

    private double calculateVolatility(List<PriceData> data) {
        if (data.size() < 2) return 0.01; // Default volatility

        double sum = 0;
        double sumSquared = 0;
        int count = data.size();

        for (PriceData priceData : data) {
            double price = priceData.getPrice();
            sum += price;
            sumSquared += price * price;
        }

        double mean = sum / count;
        double variance = (sumSquared / count) - (mean * mean);
        return Math.sqrt(variance) / mean; // Return as percentage
    }

    private double formatPrice(double price) {
        return BigDecimal.valueOf(price)
                .setScale(PRICE_DECIMAL_PLACES, RoundingMode.HALF_UP)
                .doubleValue();
    }

    @CacheEvict(value = {"priceData", "historicalData"}, allEntries = true)
    public void clearCache() {
        log.info("Cleared price data cache");
    }

    @PreDestroy
    public void cleanup() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void verifyPriceCalculation(BigInteger sqrtPriceX96, double calculatedPrice) {
        // Calculate tick from sqrtPriceX96
        double tick = Math.log(Math.pow(sqrtPriceX96.doubleValue() / Math.pow(2, 96), 2)) / Math.log(1.0001);

        // Calculate price from tick
        double priceFromTick = Math.pow(1.0001, tick);

        // Adjust for decimals
        priceFromTick = priceFromTick * Math.pow(10, ETH_DECIMALS - USDT_DECIMALS);

        // Compare prices
        double priceDiff = Math.abs(calculatedPrice - priceFromTick) / priceFromTick;
        if (priceDiff > 0.01) { // More than 1% difference
            log.warn("Price calculation mismatch: calculated={}, fromTick={}, diff={}%",
                    calculatedPrice, priceFromTick, priceDiff * 100);
        }
    }


    private void generateSyntheticData(List<PriceData> historicalData, int days) {
        double price = DEFAULT_ETH_PRICE;
        double trend = 0;
        long currentTime = Instant.now().getEpochSecond();

        // Parameters for mean reversion
        double targetPrice = DEFAULT_ETH_PRICE;
        double meanReversionStrength = 0.1; // Strength of mean reversion
        double volatility = 0.02; // Daily volatility

        // Generate hourly data points
        for (int i = days * HOURS_IN_DAY; i >= 0; i--) {
            // Mean reversion component
            double meanReversion = (targetPrice - price) * meanReversionStrength;

            // Random walk component (scaled by time)
            double randomComponent = price * volatility * random.nextGaussian() / Math.sqrt(HOURS_IN_DAY);

            // Combine mean reversion and random walk
            price = price + meanReversion + randomComponent;

            // Ensure price stays within reasonable bounds
            price = Math.max(MIN_VALID_PRICE, Math.min(MAX_VALID_PRICE, price));

            // Add some randomness to liquidity
            double liquidity = BASE_LIQUIDITY + (random.nextDouble() - 0.5) * LIQUIDITY_VARIATION;

            // Create and add the data point
            historicalData.add(new PriceData(
                    formatPrice(price),
                    formatPrice(liquidity),
                    currentTime - (i * 3600) // Convert hours to seconds
            ));
        }

        // Sort by timestamp to ensure proper ordering
        historicalData.sort(Comparator.comparingLong(PriceData::getTimestamp));

        log.debug("Generated {} synthetic price points starting at {} with initial price {}",
                historicalData.size(),
                Instant.ofEpochSecond(historicalData.get(0).getTimestamp()),
                historicalData.get(0).getPrice());
    }

    // Helper method to generate realistic price movements
    private double generatePriceMovement(double currentPrice, double targetPrice, double volatility) {
        // Mean reversion component
        double meanReversion = (targetPrice - currentPrice) * MEAN_REVERSION_FACTOR;

        // Random walk component
        double randomWalk = currentPrice * volatility * (random.nextGaussian());

        // Combine both components
        return currentPrice + meanReversion + randomWalk;
    }

    // Helper method to add market characteristics
    private void addMarketCharacteristics(List<PriceData> data) {
        if (data.isEmpty()) return;

        // Add some trending periods
        int trendDuration = HOURS_IN_DAY * 2; // 2-day trends
        double trendStrength = 0.001; // 0.1% per hour

        for (int i = 0; i < data.size(); i++) {
            int trendPhase = (i / trendDuration) % 2; // Alternating up/down trends
            PriceData point = data.get(i);

            double trendAdjustment = (trendPhase == 0 ? 1 + trendStrength : 1 - trendStrength);
            double newPrice = point.getPrice() * trendAdjustment;

            // Update the price while keeping within bounds
            data.set(i, new PriceData(
                    formatPrice(Math.max(MIN_VALID_PRICE, Math.min(MAX_VALID_PRICE, newPrice))),
                    point.getLiquidity(),
                    point.getTimestamp()
            ));
        }
    }


    public static class DataFetchException extends RuntimeException {
        public DataFetchException(String message) {
            super(message);
        }

        public DataFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
