package com.uniswap.predictor.service;

import jakarta.annotation.PostConstruct;
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

@Service
public class BlockchainService {

    private Web3j web3j;
    @Value("${arbitrum.rpc:https://arb1.arbitrum.io/rpc}")
    private String arbitrumRpc;
    private static final int TICK_SPACING = 10; // For 0.05% fee tier in Uniswap V3
    private static final String ERC20_DECIMALS_FUNCTION = "decimals";
    private final Map<String, Integer> tokenDecimalCache = new ConcurrentHashMap<>();
    private final Map<String, String> token0Cache = new ConcurrentHashMap<>();
    private final Map<String, String> token1Cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void initialize() {
        this.web3j = Web3j.build(new HttpService(arbitrumRpc));
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

        String encodedFunction = FunctionEncoder.encode(function);
        EthCall ethCall = web3j.ethCall(
                org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                        "0x0000000000000000000000000000000000000000", // from address (not important for view calls)
                        poolAddress,
                        encodedFunction
                ),
                DefaultBlockParameterName.LATEST
        ).send();

        List<Type> decoded = FunctionReturnDecoder.decode(
                ethCall.getValue(),
                function.getOutputParameters()
        );

        if (decoded != null && !decoded.isEmpty()) {
            return ((Uint128) decoded.get(0)).getValue();
        }

        throw new IOException("Failed to get pool liquidity");
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

        throw new IOException("Failed to get slot0 data");
    }

    public double calculatePrice(BigInteger sqrtPriceX96, String poolAddress, boolean token0ToToken1) {
        try {
            // Handle null or zero price case
            if (sqrtPriceX96 == null || sqrtPriceX96.equals(BigInteger.ZERO)) {
                return 0.0;
            }

            // Get token addresses and decimals
            String baseTokenAddress = getTokenAddress(poolAddress, token0ToToken1 ? "0" : "1");
            String quoteTokenAddress = getTokenAddress(poolAddress, token0ToToken1 ? "1" : "0");

            // Get decimals for both tokens
            int baseTokenDecimals = getTokenDecimals(baseTokenAddress);
            int quoteTokenDecimals = getTokenDecimals(quoteTokenAddress);

            // Calculate raw price from sqrtPriceX96
            BigDecimal q96 = new BigDecimal(BigInteger.ONE.shiftLeft(96));
            BigDecimal rawPrice = new BigDecimal(sqrtPriceX96).divide(q96, 38, RoundingMode.HALF_UP);
            BigDecimal price = rawPrice.multiply(rawPrice);

            // Handle zero price after calculation
            if (price.compareTo(BigDecimal.ZERO) == 0) {
                return 0.0;
            }

            // Adjust for decimal places
            int decimalAdjustment = quoteTokenDecimals - baseTokenDecimals;
            if (decimalAdjustment != 0) {
                BigDecimal decimalFactor = BigDecimal.TEN.pow(Math.abs(decimalAdjustment));
                if (decimalAdjustment > 0) {
                    price = price.multiply(decimalFactor);
                } else {
                    price = price.divide(decimalFactor, 38, RoundingMode.HALF_UP);
                }
            }

            // For token1/token0 price, return as is
            if (token0ToToken1) {
                return price.doubleValue();
            }

            // For token0/token1 price, calculate reciprocal
            return BigDecimal.ONE.divide(price, 38, RoundingMode.HALF_UP).doubleValue();

        } catch (IOException e) {
            throw new RuntimeException("Failed to calculate price: " + e.getMessage(), e);
        }
    }

    private String getTokenAddress(String poolAddress, String token) throws IOException {
        Map<String, String> tokenCache = "0".equals(token) ? token0Cache : token1Cache;
        return tokenCache.computeIfAbsent(poolAddress, address -> {
            try {
                Function tokenFunction = new Function(
                    "token" + token,
                    Collections.emptyList(),
                    Collections.singletonList(new TypeReference<Address>() {})
                );
                return callContract(address, tokenFunction);
            } catch (IOException e) {
                throw new RuntimeException("Failed to get token address: " + e.getMessage(), e);
            }
        });
    }

    public int[] calculateOptimalTicks(double lowerPrice, double upperPrice, String poolAddress) {
        try {
            // Get current tick and token decimals
            Slot0Data slot0 = getSlot0Data(poolAddress);

            // Adjust prices for token decimals before converting to ticks
            int token0Decimals = 18; // Should be fetched from token contract
            int token1Decimals = 6;  // Should be fetched from token contract
            int decimalAdjustment = token1Decimals - token0Decimals;

            double adjustedLowerPrice = lowerPrice;
            double adjustedUpperPrice = upperPrice;

            if (decimalAdjustment != 0) {
                double decimalFactor = Math.pow(10, decimalAdjustment);
                adjustedLowerPrice *= decimalFactor;
                adjustedUpperPrice *= decimalFactor;
            }

            // Convert adjusted prices to ticks
            int lowerTick = priceToTick(adjustedLowerPrice);
            int upperTick = priceToTick(adjustedUpperPrice);

            // Round to valid tick spacing
            lowerTick = Math.floorDiv(lowerTick, TICK_SPACING) * TICK_SPACING;
            upperTick = Math.floorDiv(upperTick, TICK_SPACING) * TICK_SPACING + TICK_SPACING;

            return new int[]{lowerTick, upperTick};
        } catch (IOException e) {
            // Fallback with adjusted prices
            int lowerTick = priceToTick(lowerPrice);
            int upperTick = priceToTick(upperPrice);

            lowerTick = Math.floorDiv(lowerTick, TICK_SPACING) * TICK_SPACING;
            upperTick = Math.floorDiv(upperTick, TICK_SPACING) * TICK_SPACING + TICK_SPACING;

            return new int[]{lowerTick, upperTick};
        }
    }

    private int priceToTick(double price) {
        // Convert price to tick using the Uniswap V3 formula
        return (int) Math.floor(Math.log(price) / Math.log(1.0001));
    }

    private int getTokenDecimals(String tokenAddress) throws IOException {
        return tokenDecimalCache.computeIfAbsent(tokenAddress, address -> {
            try {
                Function function = new Function(
                        ERC20_DECIMALS_FUNCTION,
                        Collections.emptyList(),
                        Collections.singletonList(new TypeReference<Uint8>() {})
                );

                String result = callContract(address, function);
                if (result != null && !result.isEmpty()) {
                    return Integer.parseInt(result);
                }
                
                // If the first attempt fails, try alternative ABI encoding
                function = new Function(
                        ERC20_DECIMALS_FUNCTION,
                        Collections.emptyList(),
                        Collections.singletonList(new TypeReference<Uint256>() {})
                );
                
                result = callContract(address, function);
                if (result != null && !result.isEmpty()) {
                    return Integer.parseInt(result);
                }
                
                throw new IOException("Could not retrieve token decimals");
            } catch (Exception e) {
                throw new RuntimeException("Failed to get decimals for token: " + address, e);
            }
        });
    }

    private String callContract(String address, Function function) throws IOException {
        String encodedFunction = FunctionEncoder.encode(function);
        EthCall ethCall = web3j.ethCall(
                org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                        "0x0000000000000000000000000000000000000000",
                        address,
                        encodedFunction
                ),
                DefaultBlockParameterName.LATEST
        ).send();

        if (ethCall.hasError()) {
            throw new IOException("Contract call failed: " + ethCall.getError().getMessage());
        }

        List<Type> decoded = FunctionReturnDecoder.decode(
                ethCall.getValue(),
                function.getOutputParameters()
        );

        if (decoded != null && !decoded.isEmpty()) {
            return decoded.get(0).getValue().toString();
        }
        return null;
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