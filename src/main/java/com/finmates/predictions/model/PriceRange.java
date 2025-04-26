package com.finmates.predictions.model;

import lombok.Data;

@Data
public class PriceRange {
    private double lowerBound;
    private double upperBound;
    private double predictedPrice;
    private double confidence;
    private double volatility;

    public PriceRange(double lowerBound, double upperBound) {
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
        this.predictedPrice = (upperBound + lowerBound) / 2;
        this.confidence = 0.95; // 95% confidence interval
        this.volatility = (upperBound - lowerBound) / (2 * 1.96); // Back-calculated from confidence interval
    }
}
