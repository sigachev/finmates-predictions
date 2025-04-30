package com.uniswap.predictor.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PositionProfitResponse {
    private String poolAddress;
    private int lowerTick;
    private int upperTick;
    private int durationDays;
    private double liquidityAmount;
    private double estimatedProfit;
    private double estimatedFees;
    private double estimatedImpermanentLoss;
    private double estimatedAPR;
}