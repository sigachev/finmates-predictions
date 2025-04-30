package com.uniswap.predictor.controller;

import com.uniswap.predictor.dto.PredictionRequest;
import com.uniswap.predictor.dto.PredictionResponse;
import com.uniswap.predictor.service.PredictionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/prediction")
public class PredictionController {

    private final PredictionService predictionService;

    @Autowired
    public PredictionController(PredictionService predictionService) {
        this.predictionService = predictionService;
    }

    @PostMapping("/price-range")
    public ResponseEntity<PredictionResponse> predictPriceRange(@RequestBody PredictionRequest request) {
        // Check if user requested days or hours
        if (request.getDurationDays() != null) {
            PredictionResponse prediction = predictionService.predictPriceRangeForDays(
                    request.getPoolAddress(),
                    request.getConfidenceLevel(),
                    request.getDurationDays()
            );
            return ResponseEntity.ok(prediction);
        } else if (request.getTimePeriodHours() != null) {
            PredictionResponse prediction = predictionService.predictPriceRange(
                    request.getPoolAddress(),
                    request.getConfidenceLevel(),
                    request.getTimePeriodHours()
            );
            return ResponseEntity.ok(prediction);
        } else {
            // Default to 24 hours if neither specified
            PredictionResponse prediction = predictionService.predictPriceRange(
                    request.getPoolAddress(),
                    request.getConfidenceLevel(),
                    24
            );
            return ResponseEntity.ok(prediction);
        }
    }

    @GetMapping("/price-range/{poolAddress}")
    public ResponseEntity<PredictionResponse> predictPriceRangeGet(
            @PathVariable String poolAddress,
            @RequestParam(defaultValue = "0.95") double confidenceLevel,
            @RequestParam(required = false) Integer timePeriodHours,
            @RequestParam(required = false) Integer durationDays) {

        PredictionResponse prediction;

        if (durationDays != null) {
            prediction = predictionService.predictPriceRangeForDays(
                    poolAddress,
                    confidenceLevel,
                    durationDays
            );
        } else {
            prediction = predictionService.predictPriceRange(
                    poolAddress,
                    confidenceLevel,
                    timePeriodHours != null ? timePeriodHours : 24
            );
        }

        return ResponseEntity.ok(prediction);
    }

    @PostMapping("/position-profit")
    public ResponseEntity<Map<String, Object>> estimatePositionProfit(
            @RequestParam String poolAddress,
            @RequestParam int lowerTick,
            @RequestParam int upperTick,
            @RequestParam int days) {

        double estimatedProfit = predictionService.estimatePositionProfit(
                lowerTick,
                upperTick,
                poolAddress,
                days
        );

        Map<String, Object> response = Map.of(
                "poolAddress", poolAddress,
                "lowerTick", lowerTick,
                "upperTick", upperTick,
                "durationDays", days,
                "estimatedProfit", estimatedProfit
        );

        return ResponseEntity.ok(response);
    }

    @GetMapping("/status")
    public ResponseEntity<String> getModelStatus() {
        String status = predictionService.getModelStatus();
        return ResponseEntity.ok(status);
    }

    @GetMapping("/status/{poolAddress}")
    public ResponseEntity<String> getPoolModelStatus(@PathVariable String poolAddress) {
        String status = predictionService.getPoolModelStatus(poolAddress);
        return ResponseEntity.ok(status);
    }

    @PostMapping("/retrain")
    public ResponseEntity<String> retrainModel(@RequestParam(required = false) String poolAddress) {
        if (poolAddress != null && !poolAddress.isEmpty()) {
            predictionService.retrainModel(poolAddress);
            return ResponseEntity.ok("Model retraining started for pool: " + poolAddress);
        } else {
            predictionService.retrainModel();
            return ResponseEntity.ok("Model retraining started for default pool");
        }
    }

    @GetMapping("/progress/{poolAddress}")
    public ResponseEntity<Map<String, Object>> getTrainingProgressDetails(@PathVariable String poolAddress) {
        Map<String, Object> progressDetails = predictionService.getTrainingProgressDetails(poolAddress);
        return ResponseEntity.ok(progressDetails);
    }
}