package com.uniswap.predictor.dto;

import lombok.Data;

@Data
public class PredictionRequest {
    private String poolAddress;
    private double confidenceLevel; // e.g., 0.95 for 95% confidence
    private Integer timePeriodHours; // prediction window in hours (optional)
    private Integer durationDays;   // prediction window in days (optional)
    private Double liquidityAmount; // optional liquidity amount for profit calculation
}