package com.uniswap.predictor.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
@Schema(description = "Price range prediction response")
public class PredictionResponse {
    @Schema(description = "Lower bound of predicted price range", example = "1730.55")
    private double lowerPriceRange;

    @Schema(description = "Upper bound of predicted price range", example = "1883.57")
    private double upperPriceRange;

    @Schema(description = "Optimal lower tick for liquidity position", example = "-201760.0")
    private double optimalLowerTick;

    @Schema(description = "Optimal upper tick for liquidity position", example = "-200910.0")
    private double optimalUpperTick;

    @Schema(description = "Predicted fees in USD", example = "15.75")
    private double predictedFees;

    @Schema(description = "Fees as percentage of provided liquidity", example = "0.315")
    private double feesPercentage;

    @Schema(description = "Annualized fee percentage (APR)", example = "16.43")
    private double annualizedFeesPercentage;

    @Schema(description = "Confidence level used for prediction", example = "0.95")
    private double confidenceLevel;

    @Schema(description = "Uniswap V3 pool address", example = "0x641C00A822e8b671738d32a431a4Fb6074E5c79d")
    private String poolAddress;

    @Schema(description = "Timestamp of prediction (epoch millis)", example = "1746069135855")
    private long timestamp;

    @Schema(description = "Current pool price", example = "1810.83")
    private double currentPrice;

    @Schema(description = "Predicted impermanent loss in USD", example = "4.80")
    private double predictedImpermanentLoss;

    @Schema(description = "Impermanent loss as percentage of liquidity", example = "0.096")
    private double impermanentLossPercentage;

    @Schema(description = "Prediction period in hours", example = "168")
    private int predictionPeriodHours;

    @Schema(description = "Prediction period in days", example = "7")
    private int predictionPeriodDays;

    @Schema(description = "Prediction end time", example = "2025-05-08T03:12:15.855016400Z")
    private Instant predictionEndTime;

    @Schema(description = "Liquidity amount in USD", example = "5000.0")
    private double liquidityAmount;

    @Schema(description = "Estimated net profit (fees - IL) in USD", example = "10.95")
    private double estimatedProfit;


}