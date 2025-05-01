package com.uniswap.predictor.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(
        description = "Response containing detailed pool information",
        name = "PoolInfo"
)
public class PoolInfoResponse {
    @Schema(description = "Uniswap V3 pool address", example = "0x641C00A822e8b671738d32a431a4Fb6074E5c79d")
    private String poolAddress;

    @Schema(description = "Current token price", example = "1815.07")
    private double currentPrice;

    @Schema(description = "Current pool tick index", example = "-201760")
    private int currentTick;

    @Schema(description = "Total pool liquidity", example = "45786921345678")
    private String liquidity;

    @Schema(description = "24-hour trading volume in USD", example = "15670450.25")
    private double volume24h;

    @Schema(description = "24-hour accumulated fees in USD", example = "47011.35")
    private double fees24h;

    @Schema(description = "24-hour price volatility", example = "0.023")
    private double volatility24h;

    @Schema(description = "Timestamp of data retrieval (epoch millis)", example = "1746069135855")
    private long timestamp;
}

@Data
@Schema(
        description = "Error response",
        name = "ErrorResponse"
)
class ErrorResponse {
    @Schema(description = "Error message", example = "Invalid pool address or pool not accessible")
    private String message;

    @Schema(description = "Error code", example = "POOL_NOT_FOUND")
    private String code;

    @Schema(description = "Timestamp of error (epoch millis)", example = "1746069135855")
    private long timestamp = System.currentTimeMillis();
}