package com.uniswap.predictor.controller;

import com.uniswap.predictor.dto.PoolDataPoint;
import com.uniswap.predictor.model.PoolInfoResponse;
import com.uniswap.predictor.model.TrackPoolRequest;
import com.uniswap.predictor.service.BlockchainService;
import com.uniswap.predictor.service.DataCollectionService;
import com.uniswap.predictor.service.PredictionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/pools")
@Tag(name = "Pool Management", description = "Endpoints for managing and retrieving information about Uniswap V3 pools")
public class PoolController {

    private final PredictionService predictionService;
    private final BlockchainService blockchainService;
    private final DataCollectionService dataCollectionService;

    @Autowired
    public PoolController(PredictionService predictionService,
                          BlockchainService blockchainService,
                          DataCollectionService dataCollectionService) {
        this.predictionService = predictionService;
        this.blockchainService = blockchainService;
        this.dataCollectionService = dataCollectionService;
    }

    @GetMapping
    @Operation(
            summary = "Get all available pools",
            description = "Retrieves a list of all Uniswap V3 pools that have trained prediction models available. " +
                    "These pools can be used with the prediction endpoints without requiring model training.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "List of pool addresses with trained models",
                            content = @Content(schema = @Schema(implementation = List.class))
                    )
            }
    )
    public ResponseEntity<List<String>> getAvailablePools() {
        List<String> trainedPools = predictionService.getTrainedPools();
        return ResponseEntity.ok(trainedPools);
    }

    @GetMapping("/{poolAddress}/info")
    @Operation(
            summary = "Get detailed pool information",
            description = "Retrieves comprehensive information about a specific Uniswap V3 pool, including current price, " +
                    "liquidity, volume, fees, and volatility. This data is useful for analyzing pool conditions " +
                    "before making liquidity provision decisions.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Detailed pool information retrieved successfully",
                            content = @Content(schema = @Schema(implementation = PoolInfoResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Pool not found or not accessible",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Error retrieving pool data",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
    public ResponseEntity<PoolInfoResponse> getPoolInfo(
            @Parameter(
                    description = "Uniswap V3 pool address to get information for",
                    required = true,
                    example = "0x641C00A822e8b671738d32a431a4Fb6074E5c79d"
            )
            @PathVariable String poolAddress) {
        try {
            BlockchainService.PoolState poolState = blockchainService.getPoolState(poolAddress);
            PoolDataPoint currentData = dataCollectionService.getCurrentPoolData(poolAddress);

            PoolInfoResponse info = PoolInfoResponse.builder()
                    .poolAddress(poolAddress)
                    .currentPrice(currentData.getToken0Price().doubleValue())
                    .currentTick(poolState.getTick())
                    .liquidity(poolState.getLiquidity().toString())
                    .volume24h(currentData.getVolume24h().doubleValue())
                    .fees24h(currentData.getFees24h().doubleValue())
                    .volatility24h(currentData.getVolatility24h().doubleValue())
                    .timestamp(System.currentTimeMillis())
                    .build();

            return ResponseEntity.ok(info);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(null);
        }
    }

    @PostMapping("/track")
    @Operation(
            summary = "Start tracking a new pool",
            description = "Begins tracking a new Uniswap V3 pool and initiates model training for it. " +
                    "After successful tracking and training, the pool can be used with prediction endpoints. " +
                    "Training may take several minutes to complete, and progress can be monitored using the " +
                    "training progress endpoint.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Pool tracking started successfully",
                            content = @Content(schema = @Schema(implementation = String.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid pool address or pool not accessible",
                            content = @Content(schema = @Schema(implementation = String.class))
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Error starting pool tracking",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
    public ResponseEntity<String> trackNewPool(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Request containing the Uniswap V3 pool address to track",
                    required = true,
                    content = @Content(schema = @Schema(implementation = TrackPoolRequest.class))
            )
            @RequestBody TrackPoolRequest request) {
        String poolAddress = request.getPoolAddress();

        // Validate pool address
        try {
            blockchainService.getPoolState(poolAddress);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body("Invalid pool address or pool not accessible: " + poolAddress);
        }

        // Start training model for this pool
        predictionService.retrainModel(poolAddress);

        return ResponseEntity.ok("Started tracking pool: " + poolAddress);
    }

    @GetMapping("/search")
    @Operation(
            summary = "Search for pools by token symbols",
            description = "Searches for Uniswap V3 pools that contain the specified tokens. " +
                    "Results include pool addresses, fee tiers, and current liquidity.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "List of matching pools",
                            content = @Content(schema = @Schema(implementation = List.class))
                    )
            }
    )
    public ResponseEntity<List<Map<String, Object>>> searchPools(
            @Parameter(description = "First token symbol (e.g., 'ETH')")
            @RequestParam(required = false) String token0,

            @Parameter(description = "Second token symbol (e.g., 'USDC')")
            @RequestParam(required = false) String token1,

            @Parameter(description = "Fee tier in basis points (e.g., 500 for 0.05%)")
            @RequestParam(required = false) Integer feeTier) {

        // This is a placeholder implementation - you would need to implement
        // the actual search logic in your service layer
        List<Map<String, Object>> results = new ArrayList<>();

        // Example response data
        if ((token0 != null && token0.equalsIgnoreCase("ETH")) ||
                (token1 != null && token1.equalsIgnoreCase("ETH"))) {

            results.add(Map.of(
                    "poolAddress", "0x641C00A822e8b671738d32a431a4Fb6074E5c79d",
                    "token0", "ETH",
                    "token1", "USDC",
                    "feeTier", 500,
                    "liquidity", "$50,000,000"
            ));
        }

        return ResponseEntity.ok(results);
    }
}