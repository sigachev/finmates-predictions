package com.uniswap.predictor.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class PredictionResponse {
    private double lowerPriceRange;
    private double upperPriceRange;
    private double optimalLowerTick;
    private double optimalUpperTick;
    private double predictedFees;
    private double confidenceLevel;
    private String poolAddress;
    private long timestamp;
    private double currentPrice;
    private double predictedImpermanentLoss;
    private int predictionPeriodHours;
    private int predictionPeriodDays;  // New field for days
    private Instant predictionEndTime;
    private double estimatedProfit;    // New field for estimated profit
}