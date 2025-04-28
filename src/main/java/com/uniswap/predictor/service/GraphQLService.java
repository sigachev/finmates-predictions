package com.uniswap.predictor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class GraphQLService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private static final String UNISWAP_SUBGRAPH_URL = "https://gateway.thegraph.com/api/[api-key]/subgraphs/id/FbCGRftH4a3yZugY7TnbYgPJVEv2LvMT6oF1fxPe9aJM";

    @Value("${thegraph.api.key:}")
    private String graphApiKey;

    @Value("${http.connect.timeout:10000}")
    private int connectTimeout;

    @Value("${http.read.timeout:30000}")
    private int readTimeout;

    private String fullGraphUrl;

    public GraphQLService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void initialize() {
        // Replace [api-key] placeholder with actual API key from application properties
        fullGraphUrl = UNISWAP_SUBGRAPH_URL.replace("[api-key]", graphApiKey);
        log.info("GraphQLService initialized with URL: {}", fullGraphUrl.replaceAll(graphApiKey, "***"));
    }

    public double getVolume24h(String poolAddress) {
        String query = String.format(
                "{ pool(id: \"%s\") { volumeUSD } }",
                poolAddress.toLowerCase()
        );

        JsonNode response = executeQuery(query);

        if (response != null && response.has("data") &&
                response.get("data").has("pool") &&
                !response.get("data").get("pool").isNull() &&
                response.get("data").get("pool").has("volumeUSD")) {

            return response.get("data").get("pool").get("volumeUSD").asDouble();
        }

        return 0.0;
    }

    public double getFees24h(String poolAddress) {
        String query = String.format(
                "{ pool(id: \"%s\") { feesUSD } }",
                poolAddress.toLowerCase()
        );

        JsonNode response = executeQuery(query);

        if (response != null && response.has("data") &&
                response.get("data").has("pool") &&
                !response.get("data").get("pool").isNull() &&
                response.get("data").get("pool").has("feesUSD")) {

            return response.get("data").get("pool").get("feesUSD").asDouble();
        }

        return 0.0;
    }

    public double getVolatility24h(String poolAddress) {
        // Get hourly price points for the last 24 hours
        String query = String.format(
                "{ poolHourDatas(where: { pool: \"%s\" }, orderBy: periodStartUnix, orderDirection: desc, first: 24) { close } }",
                poolAddress.toLowerCase()
        );

        JsonNode response = executeQuery(query);
        double volatility = 0.0;

        if (response != null && response.has("data") && response.get("data").has("poolHourDatas")) {
            JsonNode hourDatas = response.get("data").get("poolHourDatas");

            List<Double> prices = new ArrayList<>();
            for (JsonNode hourData : hourDatas) {
                if (hourData.has("close") && !hourData.get("close").isNull()) {
                    prices.add(hourData.get("close").asDouble());
                }
            }

            if (prices.size() > 1) {
                // Calculate standard deviation of returns
                List<Double> returns = new ArrayList<>();
                for (int i = 1; i < prices.size(); i++) {
                    if (prices.get(i - 1) > 0) {
                        double returnValue = Math.log(prices.get(i) / prices.get(i - 1));
                        returns.add(returnValue);
                    }
                }

                double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                double variance = returns.stream()
                        .mapToDouble(r -> Math.pow(r - mean, 2))
                        .average()
                        .orElse(0.0);

                volatility = Math.sqrt(variance) * Math.sqrt(24); // Annualized
            }
        }

        return volatility;
    }

    public List<SwapEvent> getHistoricalSwaps(String poolAddress, long startTime, long endTime) {
        // Adjusted query for the Uniswap v3 official subgraph schema
        String query = String.format(
                "{ swaps(where: { pool: \"%s\", timestamp_gte: %d, timestamp_lte: %d }, orderBy: timestamp, orderDirection: asc, first: 1000) " +
                        "{ timestamp sqrtPriceX96 tick amountUSD amount0 amount1 } }",
                poolAddress.toLowerCase(), startTime, endTime
        );

        JsonNode response = executeQuery(query);
        List<SwapEvent> swapEvents = new ArrayList<>();

        if (response != null && response.has("data") && response.get("data").has("swaps")) {
            JsonNode swaps = response.get("data").get("swaps");

            for (JsonNode swap : swaps) {
                try {
                    // Check if liquidity field exists, it might not in some subgraph schemas
                    BigInteger liquidity = swap.has("liquidity") ?
                            new BigInteger(swap.get("liquidity").asText()) :
                            BigInteger.ZERO;

                    SwapEvent event = new SwapEvent(
                            swap.get("timestamp").asLong(),
                            new BigDecimal(swap.get("sqrtPriceX96").asText()),
                            swap.get("tick").asInt(),
                            liquidity,
                            new BigDecimal(swap.get("amount0").asText()),
                            new BigDecimal(swap.get("amount1").asText()),
                            swap.has("amountUSD") ? new BigDecimal(swap.get("amountUSD").asText()) : BigDecimal.ZERO
                    );

                    swapEvents.add(event);
                } catch (Exception e) {
                    log.warn("Error parsing swap event: {}", e.getMessage());
                    // Skip this event but continue processing others
                }
            }
        }

        log.debug("Retrieved {} historical swap events for pool {}", swapEvents.size(), poolAddress);
        return swapEvents;
    }

    public List<LiquidityEvent> getHistoricalLiquidity(String poolAddress, long startTime, long endTime) {
        // Query mint events - adjusted for the Uniswap v3 official subgraph schema
        String mintsQuery = String.format(
                "{ mints(where: { pool: \"%s\", timestamp_gte: %d, timestamp_lte: %d }, orderBy: timestamp, orderDirection: asc, first: 1000) " +
                        "{ timestamp liquidity amount0 amount1 tickLower tickUpper amountUSD } }",
                poolAddress.toLowerCase(), startTime, endTime
        );

        JsonNode mintsResponse = executeQuery(mintsQuery);
        List<LiquidityEvent> liquidityEvents = new ArrayList<>();

        if (mintsResponse != null && mintsResponse.has("data") && mintsResponse.get("data").has("mints")) {
            JsonNode mints = mintsResponse.get("data").get("mints");

            for (JsonNode mint : mints) {
                try {
                    LiquidityEvent event = new LiquidityEvent(
                            mint.get("timestamp").asLong(),
                            LiquidityEventType.MINT,
                            new BigDecimal(mint.get("amount0").asText()),
                            new BigDecimal(mint.get("amount1").asText()),
                            mint.get("tickLower").asInt(),
                            mint.get("tickUpper").asInt(),
                            new BigDecimal(mint.get("liquidity").asText()),
                            mint.has("amountUSD") ? new BigDecimal(mint.get("amountUSD").asText()) : BigDecimal.ZERO
                    );

                    liquidityEvents.add(event);
                } catch (Exception e) {
                    log.warn("Error parsing mint event: {}", e.getMessage());
                    // Skip this event but continue processing others
                }
            }
        }

        // Query burn events
        String burnsQuery = String.format(
                "{ burns(where: { pool: \"%s\", timestamp_gte: %d, timestamp_lte: %d }, orderBy: timestamp, orderDirection: asc, first: 1000) " +
                        "{ timestamp liquidity amount0 amount1 tickLower tickUpper amountUSD } }",
                poolAddress.toLowerCase(), startTime, endTime
        );

        JsonNode burnsResponse = executeQuery(burnsQuery);

        if (burnsResponse != null && burnsResponse.has("data") && burnsResponse.get("data").has("burns")) {
            JsonNode burns = burnsResponse.get("data").get("burns");

            for (JsonNode burn : burns) {
                try {
                    LiquidityEvent event = new LiquidityEvent(
                            burn.get("timestamp").asLong(),
                            LiquidityEventType.BURN,
                            new BigDecimal(burn.get("amount0").asText()),
                            new BigDecimal(burn.get("amount1").asText()),
                            burn.get("tickLower").asInt(),
                            burn.get("tickUpper").asInt(),
                            new BigDecimal(burn.get("liquidity").asText()),
                            burn.has("amountUSD") ? new BigDecimal(burn.get("amountUSD").asText()) : BigDecimal.ZERO
                    );

                    liquidityEvents.add(event);
                } catch (Exception e) {
                    log.warn("Error parsing burn event: {}", e.getMessage());
                    // Skip this event but continue processing others
                }
            }
        }

        log.debug("Retrieved {} historical liquidity events for pool {}", liquidityEvents.size(), poolAddress);
        return liquidityEvents;
    }

    private JsonNode executeQuery(String query) {
        try {
            // Prepare HTTP headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Create request body
            String requestBody = String.format("{ \"query\": \"%s\" }", query.replace("\"", "\\\""));

            // Execute HTTP request
            HttpEntity<String> request = new HttpEntity<>(requestBody, headers);

            // Use the URL with the API key inserted
            ResponseEntity<String> response = restTemplate.postForEntity(fullGraphUrl, request, String.class);

            // Parse response
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            log.error("Error executing GraphQL query: {}", e.getMessage());
            throw new RuntimeException("Error executing GraphQL query", e);
        }
    }

    // Event classes for parsing GraphQL results
    public static class SwapEvent {
        private final long timestamp;
        private final BigDecimal sqrtPriceX96;
        private final int tick;
        private final BigInteger liquidity;
        private final BigDecimal amount0;
        private final BigDecimal amount1;
        private final BigDecimal amountUSD;

        public SwapEvent(long timestamp, BigDecimal sqrtPriceX96, int tick,
                         BigInteger liquidity, BigDecimal amount0, BigDecimal amount1) {
            this.timestamp = timestamp;
            this.sqrtPriceX96 = sqrtPriceX96;
            this.tick = tick;
            this.liquidity = liquidity;
            this.amount0 = amount0;
            this.amount1 = amount1;
            this.amountUSD = BigDecimal.ZERO; // Default
        }

        public SwapEvent(long timestamp, BigDecimal sqrtPriceX96, int tick,
                         BigInteger liquidity, BigDecimal amount0, BigDecimal amount1,
                         BigDecimal amountUSD) {
            this.timestamp = timestamp;
            this.sqrtPriceX96 = sqrtPriceX96;
            this.tick = tick;
            this.liquidity = liquidity;
            this.amount0 = amount0;
            this.amount1 = amount1;
            this.amountUSD = amountUSD;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public BigDecimal getSqrtPriceX96() {
            return sqrtPriceX96;
        }

        public int getTick() {
            return tick;
        }

        public BigInteger getLiquidity() {
            return liquidity;
        }

        public BigDecimal getAmount0() {
            return amount0;
        }

        public BigDecimal getAmount1() {
            return amount1;
        }

        public BigDecimal getAmountUSD() {
            return amountUSD;
        }
    }

    public static class LiquidityEvent {
        private final long timestamp;
        private final LiquidityEventType type;
        private final BigDecimal amount0;
        private final BigDecimal amount1;
        private final int tickLower;
        private final int tickUpper;
        private final BigDecimal liquidity;
        private final BigDecimal amountUSD;

        public LiquidityEvent(long timestamp, LiquidityEventType type,
                              BigDecimal amount0, BigDecimal amount1,
                              int tickLower, int tickUpper,
                              BigDecimal liquidity, BigDecimal amountUSD) {
            this.timestamp = timestamp;
            this.type = type;
            this.amount0 = amount0;
            this.amount1 = amount1;
            this.tickLower = tickLower;
            this.tickUpper = tickUpper;
            this.liquidity = liquidity;
            this.amountUSD = amountUSD;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public LiquidityEventType getType() {
            return type;
        }

        public BigDecimal getAmount0() {
            return amount0;
        }

        public BigDecimal getAmount1() {
            return amount1;
        }

        public int getTickLower() {
            return tickLower;
        }

        public int getTickUpper() {
            return tickUpper;
        }

        public BigDecimal getLiquidity() {
            return liquidity;
        }

        public BigDecimal getAmountUSD() {
            return amountUSD;
        }
    }

    public enum LiquidityEventType {
        MINT,
        BURN
    }


}