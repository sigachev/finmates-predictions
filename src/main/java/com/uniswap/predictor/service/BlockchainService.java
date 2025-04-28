package com.uniswap.predictor.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.*;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.http.HttpService;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class BlockchainService {

    private Web3j web3j;

    @Value("${arbitrum.rpc:https://arb1.arbitrum.io/rpc}")
    private String arbitrumRpc;

    @Value("${arbitrum.rpc.backup1:}")
    private String backupRpc1;

    @Value("${arbitrum.rpc.backup2:}")
    private String backupRpc2;

    // Make TICK_SPACING configurable
    @Value("${uniswap.default.fee:500}")
    private int defaultFeeTier;

    private final Map<Integer, Integer> feeToTickSpacing = Map.of(
            100, 1,    // 0.01% fee tier
            500, 10,   // 0.05% fee tier
            3000, 60,  // 0.3% fee tier
            10000, 200 // 1% fee tier
    );

    private static final String ERC20_DECIMALS_FUNCTION = "decimals";
    private static final String ERC20_SYMBOL_FUNCTION = "symbol";
    private static final String ERC20_NAME_FUNCTION = "name";

    private final Map<String, Integer> tokenDecimalCache = new ConcurrentHashMap<>();
    private final Map<String, String> tokenSymbolCache = new ConcurrentHashMap<>();
    private final Map<String, String> token0Cache = new ConcurrentHashMap<>();
    private final Map<String, String> token1Cache = new ConcurrentHashMap<>();
    private final Map<String, Integer> poolFeeCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void initialize() {
        initializeWeb3j();
        log.info("BlockchainService initialized with primary RPC: {}", arbitrumRpc);
    }

    private void initializeWeb3j() {
        this.web3j = Web3j.build(new HttpService(arbitrumRpc));

        // Test connection
        try {
            web3j.ethBlockNumber().send();
            log.info("Successfully connected to primary RPC: {}", arbitrumRpc);
        } catch (Exception e) {
            log.warn("Failed to connect to primary RPC: {}. Error: {}", arbitrumRpc, e.getMessage());
            tryFallbackRpcs();
        }
    }

    private void tryFallbackRpcs() {
        // Try first backup if configured
        if (backupRpc1 != null && !backupRpc1.isEmpty()) {
            try {
                this.web3j = Web3j.build(new HttpService(backupRpc1));
                web3j.ethBlockNumber().send();
                log.info("Successfully connected to backup RPC 1: {}", backupRpc1);
                return;
            } catch (Exception e) {
                log.warn("Failed to connect to backup RPC 1: {}. Error: {}", backupRpc1, e.getMessage());
            }
        }

        // Try second backup if configured
        if (backupRpc2 != null && !backupRpc2.isEmpty()) {
            try {
                this.web3j = Web3j.build(new HttpService(backupRpc2));
                web3j.ethBlockNumber().send();
                log.info("Successfully connected to backup RPC 2: {}", backupRpc2);
                return;
            } catch (Exception e) {
                log.warn("Failed to connect to backup RPC 2: {}. Error: {}", backupRpc2, e.getMessage());
            }
        }

        // If all fails, fallback to primary (even if it doesn't work)
        this.web3j = Web3j.build(new HttpService(arbitrumRpc));
        log.error("All RPC connections failed, using primary RPC as fallback: {}", arbitrumRpc);
    }

    public PoolState getPoolState(String poolAddress) throws IOException {
        // Get current liquidity
        BigInteger liquidity = getPoolLiquidity(poolAddress);

        // Get slot0 data (contains sqrtPriceX96 and tick)
        Slot0Data slot0 = getSlot0Data(poolAddress);

        return new PoolState(liquidity, slot0.getSqrtPriceX96(), slot0.getTick());
    }

    private BigInteger getPoolLiquidity(String poolAddress) throws IOException {
        Function function = new Function(
                "liquidity",
                Collections.emptyList(),
                Collections.singletonList(new TypeReference<Uint128>() {})
        );

        String result = callContract(poolAddress, function);

        if (result != null && !result.isEmpty()) {
            return new BigInteger(result);
        }

        throw new IOException("Failed to get pool liquidity for pool: " + poolAddress);
    }

    private Slot0Data getSlot0Data(String poolAddress) throws IOException {
        Function function = new Function(
                "slot0",
                Collections.emptyList(),
                Arrays.asList(
                        new TypeReference<Uint160>() {}, // sqrtPriceX96
                        new TypeReference<Int24>() {},   // tick
                        // Other Slot0 parameters we don't need right now
                        new TypeReference<Uint16>() {},  // observationIndex
                        new TypeReference<Uint16>() {},  // observationCardinality
                        new TypeReference<Uint16>() {},  // observationCardinalityNext
                        new TypeReference<Uint8>() {},   // feeProtocol
                        new TypeReference<Bool>() {}     // unlocked
                )
        );

        String encodedFunction = FunctionEncoder.encode(function);
        EthCall ethCall = web3j.ethCall(
                org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                        "0x0000000000000000000000000000000000000000",
                        poolAddress,
                        encodedFunction
                ),
                DefaultBlockParameterName.LATEST
        ).send();

        List<Type> decoded = FunctionReturnDecoder.decode(
                ethCall.getValue(),
                function.getOutputParameters()
        );

        if (decoded != null && decoded.size() >= 2) {
            return new Slot0Data(
                    ((Uint160) decoded.get(0)).getValue(),
                    ((Int24) decoded.get(1)).getValue().intValue()
            );
        }

        throw new IOException("Failed to get slot0 data for pool: " + poolAddress);
    }

    public double calculatePrice(BigInteger sqrtPriceX96, String poolAddress, boolean token0ToToken1) {
        try {
            log.debug("Raw sqrtPriceX96: {}", sqrtPriceX96);
            // Handle null or zero price case
            if (sqrtPriceX96 == null || sqrtPriceX96.equals(BigInteger.ZERO)) {
                log.warn("Invalid sqrtPriceX96 value for pool {}: {}", poolAddress, sqrtPriceX96);
                return 0.0;
            }

            // Get token addresses
            String baseTokenAddress = getTokenAddress(poolAddress, token0ToToken1 ? "0" : "1");
            String quoteTokenAddress = getTokenAddress(poolAddress, token0ToToken1 ? "1" : "0");

            // Get decimals for both tokens - dynamically fetch from the blockchain
            int baseTokenDecimals = getTokenDecimals(baseTokenAddress);
            int quoteTokenDecimals = getTokenDecimals(quoteTokenAddress);

            log.debug("Token decimals - Base({}): {}, Quote({}): {}",
                    baseTokenAddress, baseTokenDecimals,
                    quoteTokenAddress, quoteTokenDecimals);

            // Calculate raw price from sqrtPriceX96
            BigDecimal q96 = new BigDecimal(BigInteger.ONE.shiftLeft(96));
            BigDecimal rawPrice = new BigDecimal(sqrtPriceX96).divide(q96, 38, RoundingMode.HALF_UP);
            BigDecimal price = rawPrice.multiply(rawPrice);

            // Handle zero price after calculation
            if (price.compareTo(BigDecimal.ZERO) == 0) {
                log.warn("Calculated zero price for pool {}", poolAddress);
                return 0.0;
            }



            // Adjust for decimal places
            int decimalAdjustment = Math.abs(quoteTokenDecimals - baseTokenDecimals);

            log.debug("Base token decimals: {}", baseTokenDecimals);
            log.debug("Quote token decimals: {}", quoteTokenDecimals);
            log.debug("Raw price (after sqrt): {}", price);
            log.debug("Decimal adjustment: {}", decimalAdjustment);

            if (decimalAdjustment != 0) {
                BigDecimal decimalFactor = BigDecimal.TEN.pow(Math.abs(decimalAdjustment));
                if (decimalAdjustment > 0) {
                    price = price.multiply(decimalFactor);
                } else {
                    price = price.divide(decimalFactor, 38, RoundingMode.HALF_UP);
                }
            }

            log.debug("Final adjusted price: {}", price);

            // For token1/token0 price, return as is
            if (token0ToToken1) {
                return price.doubleValue();
            } else {
                return BigDecimal.ONE.divide(price, 38, RoundingMode.HALF_UP).doubleValue();
            }


        } catch (IOException e) {
            log.error("Failed to calculate price for pool {}: {}", poolAddress, e.getMessage(), e);
            throw new RuntimeException("Failed to calculate price: " + e.getMessage(), e);
        }
    }

    public String getTokenAddress(String poolAddress, String token) throws IOException {
        Map<String, String> tokenCache = "0".equals(token) ? token0Cache : token1Cache;
        return tokenCache.computeIfAbsent(poolAddress, address -> {
            try {
                log.debug("Fetching token{} address for pool {}", token, address);
                Function tokenFunction = new Function(
                        "token" + token,
                        Collections.emptyList(),
                        Collections.singletonList(new TypeReference<Address>() {})
                );
                String result = callContract(address, tokenFunction);
                log.debug("Token{} address for pool {}: {}", token, address, result);
                return result;
            } catch (IOException e) {
                log.error("Failed to get token{} address for pool {}: {}", token, address, e.getMessage(), e);
                throw new RuntimeException("Failed to get token address: " + e.getMessage(), e);
            }
        });
    }

    // Get token symbols for UI/display purposes
    public String getTokenSymbol(String tokenAddress) {
        return tokenSymbolCache.computeIfAbsent(tokenAddress, address -> {
            try {
                // Try standard symbol function
                Function function = new Function(
                        ERC20_SYMBOL_FUNCTION,
                        Collections.emptyList(),
                        Collections.singletonList(new TypeReference<org.web3j.abi.datatypes.Utf8String>() {})
                );

                String result = callContract(address, function);
                if (result != null && !result.isEmpty()) {
                    return result;
                }

                // Fallback to first 6 chars of token address
                return address.substring(0, 6) + "...";
            } catch (Exception e) {
                log.warn("Failed to get symbol for token {}: {}", address, e.getMessage());
                // Return shortened address as fallback
                return address.substring(0, 6) + "...";
            }
        });
    }

    // Get the fee tier for a pool to determine the correct tick spacing
    private int getPoolFeeTier(String poolAddress) {
        return poolFeeCache.computeIfAbsent(poolAddress, address -> {
            try {
                Function function = new Function(
                        "fee",
                        Collections.emptyList(),
                        Collections.singletonList(new TypeReference<Uint24>() {})
                );

                String result = callContract(address, function);
                if (result != null && !result.isEmpty()) {
                    int fee = Integer.parseInt(result);
                    log.debug("Pool {} has fee tier: {}", address, fee);
                    return fee;
                }
            } catch (Exception e) {
                log.warn("Failed to get fee tier for pool {}: {}", address, e.getMessage());
            }

            // Default to the configured fee tier if we can't fetch it
            log.debug("Using default fee tier {} for pool {}", defaultFeeTier, address);
            return defaultFeeTier;
        });
    }

    // Get the tick spacing for a pool based on its fee tier
    private int getTickSpacing(String poolAddress) {
        int feeTier = getPoolFeeTier(poolAddress);
        return feeToTickSpacing.getOrDefault(feeTier, 10); // Default to 10 (0.05% fee tier) if unknown
    }

    public int[] calculateOptimalTicks(double lowerPrice, double upperPrice, String poolAddress) {
        try {
            // Get token addresses
            String token0Address = getTokenAddress(poolAddress, "0");
            String token1Address = getTokenAddress(poolAddress, "1");

            // Dynamically fetch token decimals from the blockchain
            int token0Decimals = getTokenDecimals(token0Address);
            int token1Decimals = getTokenDecimals(token1Address);

            log.debug("Token decimals for pool {}: token0({})={}, token1({})={}",
                    poolAddress, token0Address, token0Decimals, token1Address, token1Decimals);

            int decimalAdjustment = token1Decimals - token0Decimals;

            double adjustedLowerPrice = lowerPrice;
            double adjustedUpperPrice = upperPrice;

            if (decimalAdjustment != 0) {
                double decimalFactor = Math.pow(10, decimalAdjustment);
                adjustedLowerPrice *= decimalFactor;
                adjustedUpperPrice *= decimalFactor;
                log.debug("Adjusted prices for decimal difference {}: lower={}, upper={}",
                        decimalAdjustment, adjustedLowerPrice, adjustedUpperPrice);
            }

            // Get the correct tick spacing for this pool
            int tickSpacing = getTickSpacing(poolAddress);
            log.debug("Using tick spacing {} for pool {}", tickSpacing, poolAddress);

            // Convert adjusted prices to ticks
            int lowerTick = priceToTick(adjustedLowerPrice);
            int upperTick = priceToTick(adjustedUpperPrice);

            // Round to valid tick spacing
            lowerTick = Math.floorDiv(lowerTick, tickSpacing) * tickSpacing;
            upperTick = Math.floorDiv(upperTick, tickSpacing) * tickSpacing + tickSpacing;

            log.debug("Calculated optimal ticks for pool {}: lower={}, upper={}",
                    poolAddress, lowerTick, upperTick);

            return new int[]{lowerTick, upperTick};
        } catch (IOException e) {
            // Log the error
            log.error("Error calculating optimal ticks: " + e.getMessage(), e);

            // Fallback with basic calculation
            int tickSpacing = 10; // Default to 0.05% fee tier
            try {
                tickSpacing = getTickSpacing(poolAddress);
            } catch (Exception ex) {
                log.warn("Failed to get tick spacing for fallback calculation: {}", ex.getMessage());
            }

            // Fallback calculation without decimal adjustment
            int lowerTick = priceToTick(lowerPrice);
            int upperTick = priceToTick(upperPrice);

            lowerTick = Math.floorDiv(lowerTick, tickSpacing) * tickSpacing;
            upperTick = Math.floorDiv(upperTick, tickSpacing) * tickSpacing + tickSpacing;

            log.debug("Using fallback tick calculation for pool {}: lower={}, upper={}",
                    poolAddress, lowerTick, upperTick);

            return new int[]{lowerTick, upperTick};
        }
    }

    private int priceToTick(double price) {
        // Convert price to tick using the Uniswap V3 formula
        log.debug("Converting price {} to tick", price);
        int tick = (int) Math.floor(Math.log(price) / Math.log(1.0001));
        log.debug("Calculated tick: {}", tick);
        return tick;
    }

    private double tickToPrice(int tick) {
        // Convert tick to price using the Uniswap V3 formula
        return Math.pow(1.0001, tick);
    }

    public int getTokenDecimals(String tokenAddress) throws IOException {
        return tokenDecimalCache.computeIfAbsent(tokenAddress, address -> {
            try {
                log.debug("Fetching decimals for token: {}", address);

                // First try with Uint8 return type (most common ERC20 implementation)
                Function function = new Function(
                        ERC20_DECIMALS_FUNCTION,
                        Collections.emptyList(),
                        Collections.singletonList(new TypeReference<Uint8>() {})
                );

                String result = null;
                try {
                    result = callContract(address, function);
                } catch (IOException e) {
                    log.debug("Failed to get decimals with Uint8 type for {}: {}", address, e.getMessage());
                    // Silently fail to try the next method
                }

                if (result != null && !result.isEmpty()) {
                    int decimals = Integer.parseInt(result);
                    log.debug("Token {} has {} decimals (Uint8)", address, decimals);
                    return decimals;
                }

                // Try with Uint256 return type (alternative implementation)
                function = new Function(
                        ERC20_DECIMALS_FUNCTION,
                        Collections.emptyList(),
                        Collections.singletonList(new TypeReference<Uint256>() {})
                );

                try {
                    result = callContract(address, function);
                } catch (IOException e) {
                    log.debug("Failed to get decimals with Uint256 type for {}: {}", address, e.getMessage());
                    // Silently fail to try the fallback method
                }

                if (result != null && !result.isEmpty()) {
                    int decimals = Integer.parseInt(result);
                    log.debug("Token {} has {} decimals (Uint256)", address, decimals);
                    return decimals;
                }

                // Last attempt - try bytes32 return type (some non-standard tokens)
                function = new Function(
                        ERC20_DECIMALS_FUNCTION,
                        Collections.emptyList(),
                        Collections.singletonList(new TypeReference<Bytes32>() {})
                );

                try {
                    result = callContract(address, function);
                    if (result != null && !result.isEmpty()) {
                        // Convert from bytes32 to int
                        BigInteger bi = new BigInteger(result.substring(2), 16);
                        int decimals = bi.intValue();
                        log.debug("Token {} has {} decimals (Bytes32)", address, decimals);
                        return decimals;
                    }
                } catch (IOException e) {
                    log.warn("Failed to get decimals with Bytes32 type for {}: {}", address, e.getMessage());
                }

                // Fallback to common values based on known token addresses
                // This is a last resort when contract calls fail
                if (isStablecoin(address)) {
                    log.warn("Using fallback decimals (6) for suspected stablecoin: {}", address);
                    return 6; // Common for USDC, USDT, etc.
                } else {
                    log.warn("Using fallback decimals (18) for token: {}", address);
                    return 18; // Most common for ERC20 tokens (ETH, most ERC20s)
                }
            } catch (Exception e) {
                log.error("Failed to get decimals for token {}: {}", address, e.getMessage(), e);
                throw new RuntimeException("Failed to get decimals for token: " + address, e);
            }
        });
    }

    // Helper method to identify common stablecoins by address
    private boolean isStablecoin(String address) {
        // Convert to lowercase for case-insensitive comparison
        String lowerAddress = address.toLowerCase();

        // List of common stablecoins that typically have 6 decimals
        return lowerAddress.equals("0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48") || // USDC on Ethereum
                lowerAddress.equals("0xdac17f958d2ee523a2206206994597c13d831ec7") || // USDT on Ethereum
                lowerAddress.equals("0x2791bca1f2de4661ed88a30c99a7a9449aa84174") || // USDC on Polygon
                lowerAddress.equals("0xc2132d05d31c914a87c6611c10748aeb04b58e8f") || // USDT on Polygon
                lowerAddress.equals("0xff970a61a04b1ca14834a43f5de4533ebddb5cc8") || // USDC on Arbitrum
                lowerAddress.equals("0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9");   // USDT on Arbitrum
    }

    private String callContract(String address, Function function) throws IOException {
        String encodedFunction = FunctionEncoder.encode(function);

        // Add timeout and retry logic for more resilient RPC calls
        int maxRetries = 3;
        int retryCount = 0;
        long retryDelayMs = 1000; // 1 second initial delay

        while (true) {
            try {
                EthCall ethCall = web3j.ethCall(
                        org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                                "0x0000000000000000000000000000000000000000", // from address
                                address,
                                encodedFunction
                        ),
                        DefaultBlockParameterName.LATEST
                ).send();

                if (ethCall.hasError()) {
                    throw new IOException("Contract call failed: " +
                            (ethCall.getError() != null ? ethCall.getError().getMessage() : "Unknown error"));
                }

                String value = ethCall.getValue();
                if (value == null || value.equals("0x")) {
                    return null; // Return null for empty responses
                }

                List<Type> decoded = FunctionReturnDecoder.decode(
                        value,
                        function.getOutputParameters()
                );

                if (decoded != null && !decoded.isEmpty() && decoded.get(0) != null && decoded.get(0).getValue() != null) {
                    return decoded.get(0).getValue().toString();
                }

                return null;

            } catch (IOException e) {
                throw e; // Rethrow IO exceptions
            } catch (Exception e) {
                // Handle timeouts and other exceptions with retries
                retryCount++;
                if (retryCount >= maxRetries) {
                    throw new IOException("Failed to call contract after " + maxRetries + " attempts: " + e.getMessage(), e);
                }

                // Log the retry
                log.warn("Contract call failed, retrying ({}/{}): {}", retryCount, maxRetries, e.getMessage());

                // Exponential backoff
                try {
                    Thread.sleep(retryDelayMs * retryCount);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Thread interrupted during retry delay", ie);
                }
            }
        }
    }

    // Helper classes
    public static class PoolState {
        private final BigInteger liquidity;
        private final BigInteger sqrtPriceX96;
        private final int tick;

        public PoolState(BigInteger liquidity, BigInteger sqrtPriceX96, int tick) {
            this.liquidity = liquidity;
            this.sqrtPriceX96 = sqrtPriceX96;
            this.tick = tick;
        }

        public BigInteger getLiquidity() {
            return liquidity;
        }

        public BigInteger getSqrtPriceX96() {
            return sqrtPriceX96;
        }

        public int getTick() {
            return tick;
        }
    }

    public static class Slot0Data {
        private final BigInteger sqrtPriceX96;
        private final int tick;

        public Slot0Data(BigInteger sqrtPriceX96, int tick) {
            this.sqrtPriceX96 = sqrtPriceX96;
            this.tick = tick;
        }

        public BigInteger getSqrtPriceX96() {
            return sqrtPriceX96;
        }

        public int getTick() {
            return tick;
        }
    }
}