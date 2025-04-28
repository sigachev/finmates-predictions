
package com.uniswap.predictor.dto;

import lombok.Builder;
import lombok.Data;

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
}

