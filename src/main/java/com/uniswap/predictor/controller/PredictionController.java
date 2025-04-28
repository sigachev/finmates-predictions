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
        PredictionResponse prediction = predictionService.predictPriceRange(
                request.getPoolAddress(),
                request.getConfidenceLevel(),
                request.getTimePeriodHours()
        );
        return ResponseEntity.ok(prediction);
    }

    @GetMapping("/price-range/{poolAddress}")
    public ResponseEntity<PredictionResponse> predictPriceRangeGet(
            @PathVariable String poolAddress,
            @RequestParam(defaultValue = "0.95") double confidenceLevel,
            @RequestParam(defaultValue = "24") int timePeriodHours) {
        PredictionResponse prediction = predictionService.predictPriceRange(
                poolAddress,
                confidenceLevel,
                timePeriodHours
        );
        return ResponseEntity.ok(prediction);
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
