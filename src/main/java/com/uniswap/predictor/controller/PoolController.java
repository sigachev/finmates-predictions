package com.uniswap.predictor.controller;

import com.uniswap.predictor.dto.PoolDataPoint;
import com.uniswap.predictor.service.BlockchainService;
import com.uniswap.predictor.service.DataCollectionService;
import com.uniswap.predictor.service.PredictionService;
import lombok.Builder;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/pools")
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
    public ResponseEntity<List<String>> getAvailablePools() {
        List<String> trainedPools = predictionService.getTrainedPools();
        return ResponseEntity.ok(trainedPools);
    }

    @GetMapping("/{poolAddress}/info")
    public ResponseEntity<PoolInfoResponse> getPoolInfo(@PathVariable String poolAddress) {
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
    public ResponseEntity<String> trackNewPool(@RequestBody TrackPoolRequest request) {
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
}

@Data
@Builder
class PoolInfoResponse {
    private String poolAddress;
    private double currentPrice;
    private int currentTick;
    private String liquidity;
    private double volume24h;
    private double fees24h;
    private double volatility24h;
    private long timestamp;
}

@Data
class TrackPoolRequest {
    private String poolAddress;
}