package com.uniswap.predictor.service;

import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.http.HttpService;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.Int24;
import org.web3j.abi.datatypes.generated.Uint160;
import org.web3j.abi.datatypes.generated.Uint128;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Service
public class BlockchainService {

    private Web3j web3j;
    private static final String ARBITRUM_RPC = "https://arb1.arbitrum.io/rpc";
    private static final int TICK_SPACING = 60; // For 0.3% fee tier in Uniswap V3

    @PostConstruct
    public void initialize() {
        this.web3j = Web3j.build(new HttpService(ARBITRUM_RPC));
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

    public double calculatePrice(BigInteger sqrtPriceX96, boolean token0ToToken1) {
        BigDecimal q96 = new BigDecimal(BigInteger.ONE.shiftLeft(96));
        BigDecimal rawPrice = new BigDecimal(sqrtPriceX96).divide(q96, 18, RoundingMode.HALF_UP);

        // Square the price
        BigDecimal price = rawPrice.multiply(rawPrice);

        // If we want token1/token0 price, return as is
        // If we want token0/token1 price, take the reciprocal
        return token0ToToken1 ? price.doubleValue() : BigDecimal.ONE.divide(price, 18, RoundingMode.HALF_UP).doubleValue();
    }

    public int[] calculateOptimalTicks(double lowerPrice, double upperPrice, String poolAddress) {
        try {
            // Get current tick
            Slot0Data slot0 = getSlot0Data(poolAddress);
            int currentTick = slot0.getTick();

            // Convert prices to ticks
            int lowerTick = priceToTick(lowerPrice);
            int upperTick = priceToTick(upperPrice);

            // Round to valid tick spacing
            lowerTick = Math.floorDiv(lowerTick, TICK_SPACING) * TICK_SPACING;
            upperTick = Math.floorDiv(upperTick, TICK_SPACING) * TICK_SPACING + TICK_SPACING;

            return new int[]{lowerTick, upperTick};
        } catch (IOException e) {
            // Fallback to a range around the predicted prices if we can't get data
            int lowerTick = priceToTick(lowerPrice);
            int upperTick = priceToTick(upperPrice);

            lowerTick = Math.floorDiv(lowerTick, TICK_SPACING) * TICK_SPACING;
            upperTick = Math.floorDiv(upperTick, TICK_SPACING) * TICK_SPACING + TICK_SPACING;

            return new int[]{lowerTick, upperTick};
        }
    }

    private int priceToTick(double price) {
        // Convert price to tick using the Uniswap formula
        return (int) Math.floor(Math.log(price) / Math.log(1.0001));
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