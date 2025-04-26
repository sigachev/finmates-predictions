package com.finmates.predictions.model;

import lombok.Data;

@Data
public class PriceData {
    private final double price;
    private final double liquidity;
    private final long timestamp;
}
