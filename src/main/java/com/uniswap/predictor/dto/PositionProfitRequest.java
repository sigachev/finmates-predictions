package com.uniswap.predictor.dto;

import lombok.Data;

@Data
public class PositionProfitRequest {
    private String poolAddress;
    private int lowerTick;
    private int upperTick;
    private int durationDays;
    private double liquidityAmount;
}