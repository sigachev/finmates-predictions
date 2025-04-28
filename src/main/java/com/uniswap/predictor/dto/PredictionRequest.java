package com.uniswap.predictor.dto;

import lombok.Data;

@Data
public class PredictionRequest {
    private String poolAddress;
    private double confidenceLevel; // e.g., 0.95 for 95% confidence
    private int timePeriodHours; // prediction window
}