package com.uniswap.predictor.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Request for price range prediction")
public class PredictionRequest {
    @Schema(description = "Uniswap V3 pool address", example = "0x641C00A822e8b671738d32a431a4Fb6074E5c79d", required = true)
    private String poolAddress;

    @Schema(description = "Confidence level (0.5-0.99)", example = "0.95", defaultValue = "0.95")
    private double confidenceLevel = 0.95;

    @Schema(description = "Prediction period in days (1-30)", example = "7")
    private Integer durationDays;

    @Schema(description = "Liquidity amount in USD", example = "5000.0", defaultValue = "1000.0")
    private Double liquidityAmount = 1000.0;
}