package com.finmates.predictions.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.finmates.predictions.service.UniswapDataService;
import com.finmates.predictions.service.PricePredictionService;
import com.finmates.predictions.model.PriceData;
import com.finmates.predictions.model.PriceRange;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Slf4j
@RestController
@RequestMapping("/api/prediction")
public class PredictionController {
    private final UniswapDataService uniswapDataService;
    private final PricePredictionService predictionService;

    public PredictionController(UniswapDataService uniswapDataService,
                                PricePredictionService predictionService) {
        this.uniswapDataService = uniswapDataService;
        this.predictionService = predictionService;
    }

    @GetMapping("/price-range")
    public ResponseEntity<Map<String, Object>> getPredictedPriceRange(
            @RequestParam(defaultValue = "24") int hoursAhead) {

        long startTime = System.currentTimeMillis();

        try {
            if (predictionService.needsRetraining()) {
                List<PriceData> historicalData = uniswapDataService.getHistoricalData(30);
                if (historicalData.isEmpty()) {
                    throw new RuntimeException("No historical data available");
                }
                predictionService.trainModel(historicalData);
            }

            PriceRange priceRange = predictionService.predictPriceRange(hoursAhead);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("dataSource", uniswapDataService.isUsingSimulatedData() ? "simulated" : "real");
            metadata.put("predictionHorizon", hoursAhead);
            metadata.put("timestamp", System.currentTimeMillis());
            metadata.put("processingTimeMs", System.currentTimeMillis() - startTime);
            metadata.put("lastTrainingTime",
                    Instant.ofEpochSecond(predictionService.getLastTrainingTime())
                            .atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

            Map<String, Object> prediction = new HashMap<>();
            prediction.put("priceRange", priceRange);
            prediction.putAll(predictionService.getModelMetrics());

            Map<String, Object> response = new HashMap<>();
            response.put("metadata", metadata);
            response.put("prediction", prediction);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error generating price prediction", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to generate price prediction");
            errorResponse.put("message", e.getMessage());
            errorResponse.put("timestamp", System.currentTimeMillis());
            errorResponse.put("processingTimeMs", System.currentTimeMillis() - startTime);
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }


    @GetMapping("/model-metrics")
    public ResponseEntity<Map<String, Object>> getModelMetrics() {
        return ResponseEntity.ok(predictionService.getModelMetrics());
    }

    @GetMapping("/prediction-quality")
    public ResponseEntity<Map<String, Double>> getPredictionQuality() {
        return ResponseEntity.ok(predictionService.getPredictionQualityMetrics());
    }

}
