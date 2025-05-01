package com.uniswap.predictor.controller;

import com.uniswap.predictor.dto.PredictionRequest;
import com.uniswap.predictor.dto.PredictionResponse;
import com.uniswap.predictor.service.PredictionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/prediction")
@Tag(name = "Price Prediction", description = "Endpoints for predicting price ranges and calculating profitability metrics")
public class PredictionController {

    private final PredictionService predictionService;

    @Autowired
    public PredictionController(PredictionService predictionService) {
        this.predictionService = predictionService;
    }

    @PostMapping("/price-range")
    @Operation(
            summary = "Predict price range with detailed metrics",
            description = "Predicts the price range a token will stay within for a specified time period, " +
                    "calculates optimal tick ranges for liquidity provision, and provides detailed " +
                    "metrics including estimated fees, impermanent loss, and profitability.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successful prediction with detailed metrics",
                            content = @Content(schema = @Schema(implementation = PredictionResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid input parameters",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Model not ready or internal server error",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
    public ResponseEntity<PredictionResponse> predictPriceRange(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Prediction request parameters including pool address, confidence level, duration, and liquidity amount",
                    required = true,
                    content = @Content(schema = @Schema(implementation = PredictionRequest.class))
            )
            @RequestBody PredictionRequest request) {

        // Validate inputs
        if (request.getDurationDays() == null || request.getDurationDays() < 1) {
            request.setDurationDays(1); // Default to 1 day if not specified
        }
        if (request.getLiquidityAmount() == null || request.getLiquidityAmount() <= 0) {
            request.setLiquidityAmount(1000.0); // Default to $1000 if not specified
        }

        PredictionResponse prediction = predictionService.predictPriceRangeForDays(
                request.getPoolAddress(),
                request.getConfidenceLevel(),
                request.getDurationDays(),
                request.getLiquidityAmount()
        );

        return ResponseEntity.ok(prediction);
    }

    @GetMapping("/price-range/{poolAddress}")
    @Operation(
            summary = "Predict price range for a specific pool",
            description = "Predicts the price range for a given Uniswap V3 pool address over a specified time period. " +
                    "Provides optimal tick ranges for liquidity provision and detailed metrics on expected " +
                    "returns and risks. Uses machine learning and historical data analysis to generate predictions.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successful prediction with detailed metrics",
                            content = @Content(schema = @Schema(implementation = PredictionResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid pool address or parameters",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Pool not found",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Model not ready or internal server error",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
    public ResponseEntity<PredictionResponse> predictPriceRangeGet(
            @Parameter(
                    description = "Uniswap V3 pool address (e.g., 0x641C00A822e8b671738d32a431a4Fb6074E5c79d for ETH/USDC)",
                    required = true,
                    example = "0x641C00A822e8b671738d32a431a4Fb6074E5c79d"
            )
            @PathVariable String poolAddress,

            @Parameter(
                    description = "Confidence level for the prediction (0.5-0.99). Higher values give wider ranges with greater confidence.",
                    example = "0.95"
            )
            @RequestParam(defaultValue = "0.95") double confidenceLevel,

            @Parameter(
                    description = "Prediction period in days (1-30). The duration for which the price range is predicted.",
                    example = "7"
            )
            @RequestParam(defaultValue = "1") int durationDays,

            @Parameter(
                    description = "Amount of liquidity to provide in USD. Used to calculate expected fees and returns.",
                    example = "5000.0"
            )
            @RequestParam(defaultValue = "1000.0") double liquidityAmount) {

        PredictionResponse prediction = predictionService.predictPriceRangeForDays(
                poolAddress,
                confidenceLevel,
                durationDays,
                liquidityAmount
        );

        return ResponseEntity.ok(prediction);
    }

    @GetMapping("/status")
    @Operation(
            summary = "Get model training status",
            description = "Retrieves the current status of the price prediction model for the default pool. " +
                    "Shows whether the model is ready, in training, or needs to be trained.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Status information retrieved successfully",
                            content = @Content(schema = @Schema(implementation = String.class))
                    )
            }
    )
    public ResponseEntity<String> getModelStatus() {
        String status = predictionService.getModelStatus();
        return ResponseEntity.ok(status);
    }

    @GetMapping("/status/{poolAddress}")
    @Operation(
            summary = "Get model status for specific pool",
            description = "Retrieves the current status of the price prediction model for a specified pool. " +
                    "Shows whether the model is ready, in training progress percentage, or needs to be trained.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Status information retrieved successfully",
                            content = @Content(schema = @Schema(implementation = String.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Pool not found",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
    public ResponseEntity<String> getPoolModelStatus(
            @Parameter(
                    description = "Uniswap V3 pool address to check model status for",
                    required = true,
                    example = "0x641C00A822e8b671738d32a431a4Fb6074E5c79d"
            )
            @PathVariable String poolAddress) {
        String status = predictionService.getPoolModelStatus(poolAddress);
        return ResponseEntity.ok(status);
    }

    @PostMapping("/retrain")
    @Operation(
            summary = "Retrain prediction model",
            description = "Triggers retraining of the price prediction model for a specified pool or the default pool. " +
                    "Uses the latest historical data to update the model for more accurate predictions.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Model retraining started successfully",
                            content = @Content(schema = @Schema(implementation = String.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid pool address",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
    public ResponseEntity<String> retrainModel(
            @Parameter(
                    description = "Uniswap V3 pool address to retrain model for (optional - uses default if not specified)",
                    example = "0x641C00A822e8b671738d32a431a4Fb6074E5c79d"
            )
            @RequestParam(required = false) String poolAddress) {
        if (poolAddress != null && !poolAddress.isEmpty()) {
            predictionService.retrainModel(poolAddress);
            return ResponseEntity.ok("Model retraining started for pool: " + poolAddress);
        } else {
            predictionService.retrainModel();
            return ResponseEntity.ok("Model retraining started for default pool");
        }
    }

    @GetMapping("/progress/{poolAddress}")
    @Operation(
            summary = "Get detailed training progress",
            description = "Retrieves detailed information about the training progress of the prediction model for a specified pool. " +
                    "Includes progress percentage, current epoch, total epochs, latest score, and last update time.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Training progress information retrieved successfully",
                            content = @Content(schema = @Schema(implementation = Map.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Pool not found",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
    public ResponseEntity<Map<String, Object>> getTrainingProgressDetails(
            @Parameter(
                    description = "Uniswap V3 pool address to get training progress for",
                    required = true,
                    example = "0x641C00A822e8b671738d32a431a4Fb6074E5c79d"
            )
            @PathVariable String poolAddress) {
        Map<String, Object> progressDetails = predictionService.getTrainingProgressDetails(poolAddress);
        return ResponseEntity.ok(progressDetails);
    }
}