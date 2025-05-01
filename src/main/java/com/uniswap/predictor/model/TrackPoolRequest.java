package com.uniswap.predictor.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(
        description = "Request to track a new Uniswap V3 pool",
        name = "TrackPoolRequest"
)
public class TrackPoolRequest {
    @Schema(
            description = "Uniswap V3 pool address to track",
            example = "0x88e6a0c2ddd26feeb64f039a2c41296fcb3f5640",
            required = true
    )
    private String poolAddress;
}
