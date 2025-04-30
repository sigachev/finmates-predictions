package com.uniswap.predictor.controller;

import com.uniswap.predictor.dto.PositionProfitRequest;
import com.uniswap.predictor.dto.PositionProfitResponse;
import com.uniswap.predictor.service.PredictionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/position")
public class PositionController {

    private final PredictionService predictionService;

    @Autowired
    public PositionController(PredictionService predictionService) {
        this.predictionService = predictionService;
    }

    @PostMapping("/profit")
    public ResponseEntity<PositionProfitResponse> calculatePositionProfit(
            @RequestBody PositionProfitRequest request) {

        double estimatedProfit = predictionService.estimatePositionProfit(
                request.getLowerTick(),
                request.getUpperTick(),
                request.getPoolAddress(),
                request.getDurationDays()
        );

        double estimatedFees = predictionService.estimateFeesForPosition(
                request.getLowerTick(),
                request.getUpperTick(),
                request.getPoolAddress(),
                request.getDurationDays(),
                request.getLiquidityAmount()
        );

        double estimatedImpermanentLoss = predictionService.estimateImpermanentLossForPosition(
                request.getPoolAddress(),
                request.getDurationDays(),
                request.getLiquidityAmount()
        );

        PositionProfitResponse response = PositionProfitResponse.builder()
                .poolAddress(request.getPoolAddress())
                .lowerTick(request.getLowerTick())
                .upperTick(request.getUpperTick())
                .durationDays(request.getDurationDays())
                .liquidityAmount(request.getLiquidityAmount())
                .estimatedProfit(estimatedProfit)
                .estimatedFees(estimatedFees)
                .estimatedImpermanentLoss(estimatedImpermanentLoss)
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/profit-projection/{poolAddress}")
    public ResponseEntity<Map<String, Object>> getProfitProjection(
            @PathVariable String poolAddress,
            @RequestParam int lowerTick,
            @RequestParam int upperTick,
            @RequestParam(defaultValue = "1000") double liquidityAmount,
            @RequestParam(defaultValue = "30") int maxDays) {

        List<Map<String, Object>> projections = new ArrayList<>();

        // Generate profit projections for different time periods
        for (int days = 1; days <= maxDays; days++) {
            double profit = predictionService.estimatePositionProfit(
                    lowerTick, upperTick, poolAddress, days);

            double fees = predictionService.estimateFeesForPosition(
                    lowerTick, upperTick, poolAddress, days, liquidityAmount);

            double il = predictionService.estimateImpermanentLossForPosition(
                    poolAddress, days, liquidityAmount);

            projections.add(Map.of(
                    "days", days,
                    "profit", profit,
                    "fees", fees,
                    "impermanentLoss", il,
                    "apr", (profit / liquidityAmount) * (365.0 / days) * 100
            ));
        }

        // Get token symbols for the pool
        String token0Symbol = "Unknown";
        String token1Symbol = "Unknown";

        try {
            String token0Address = predictionService.getTokenAddress(poolAddress, "0");
            String token1Address = predictionService.getTokenAddress(poolAddress, "1");
            token0Symbol = predictionService.getTokenSymbol(token0Address);
            token1Symbol = predictionService.getTokenSymbol(token1Address);
        } catch (Exception e) {
            // Handle error silently
        }

        return ResponseEntity.ok(Map.of(
                "poolAddress", poolAddress,
                "token0Symbol", token0Symbol,
                "token1Symbol", token1Symbol,
                "lowerTick", lowerTick,
                "upperTick", upperTick,
                "liquidityAmount", liquidityAmount,
                "projections", projections
        ));
    }
}