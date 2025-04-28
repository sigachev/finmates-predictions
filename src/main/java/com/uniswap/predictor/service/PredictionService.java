package com.uniswap.predictor.service;

import com.uniswap.predictor.dto.PoolDataPoint;
import com.uniswap.predictor.dto.PredictionResponse;
import com.uniswap.predictor.model.BayesianPricePredictor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class PredictionService {

    private final BlockchainService blockchainService;
    private final DataCollectionService dataCollectionService;

    @Value("${uniswap.default.pool:0x641C00A822e8b671738d32a431a4Fb6074E5c79d}")
    private String defaultPoolAddress;

    private final Map<String, BayesianPricePredictor> modelsByPool = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> modelReadyStatus = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> trainingInProgressStatus = new ConcurrentHashMap<>();

    @Autowired
    public PredictionService(BlockchainService blockchainService, DataCollectionService dataCollectionService) {
        this.blockchainService = blockchainService;
        this.dataCollectionService = dataCollectionService;
    }

    @PostConstruct
    public void initialize() {
        // Initialize default model
        getOrCreateModel(defaultPoolAddress);
        // Perform initial data load and model training
        retrainModel();
    }

    @Scheduled(fixedRate = 86400000) // Retrain every 24 hours
    public void scheduledModelUpdate() {
        retrainModel();
    }

    public void retrainModel() {
        retrainModel(defaultPoolAddress);
    }

    public void retrainModel(String poolAddress) {
        // Initialize status tracking for this pool if needed
        AtomicBoolean trainingInProgress = trainingInProgressStatus.computeIfAbsent(
                poolAddress, k -> new AtomicBoolean(false));
        AtomicBoolean modelReady = modelReadyStatus.computeIfAbsent(
                poolAddress, k -> new AtomicBoolean(false));

        if (trainingInProgress.getAndSet(true)) {
            return; // Training already in progress
        }

        Thread trainingThread = new Thread(() -> {
            try {
                modelReady.set(false);

                // Get or create the model for this pool
                BayesianPricePredictor pricePredictor = getOrCreateModel(poolAddress);

                // Fetch historical data for the pool
                List<PoolDataPoint> historicalData = dataCollectionService.collectHistoricalData(
                        poolAddress,
                        Instant.now().minus(Duration.ofDays(30)),
                        Instant.now()
                );

                // Train the model if we have enough data
                if (historicalData.size() > 24) { // Need at least one day of data
                    pricePredictor.train(historicalData);
                    modelReady.set(true);
                } else {
                    // Log insufficient data
                    System.err.println("Insufficient historical data for pool: " + poolAddress);
                }
            } catch (Exception e) {
                System.err.println("Error training model for pool: " + poolAddress);
                e.printStackTrace();
            } finally {
                trainingInProgress.set(false);
            }
        });

        trainingThread.setDaemon(true);
        trainingThread.start();
    }

    private BayesianPricePredictor getOrCreateModel(String poolAddress) {
        return modelsByPool.computeIfAbsent(poolAddress, k -> {
            // Initialize status tracking for this pool
            modelReadyStatus.putIfAbsent(poolAddress, new AtomicBoolean(false));
            trainingInProgressStatus.putIfAbsent(poolAddress, new AtomicBoolean(false));

            // Create new model
            return new BayesianPricePredictor();
        });
    }

    public PredictionResponse predictPriceRange(String poolAddress, double confidenceLevel, int timePeriodHours) {
        // Default to WETH/USDT pool if not specified
        if (poolAddress == null || poolAddress.isEmpty()) {
            poolAddress = defaultPoolAddress;
        }

        // Check if model exists and is ready
        AtomicBoolean modelReady = modelReadyStatus.computeIfAbsent(
                poolAddress, k -> new AtomicBoolean(false));

        if (!modelReady.get()) {
            // Check if we're already training this model
            AtomicBoolean trainingInProgress = trainingInProgressStatus.computeIfAbsent(
                    poolAddress, k -> new AtomicBoolean(false));

            if (!trainingInProgress.get()) {
                // Start training if not already in progress
                retrainModel(poolAddress);
            }

            throw new IllegalStateException("Model for pool " + poolAddress + " is not ready yet. Please try again later.");
        }

        // Get the model for this pool
        BayesianPricePredictor pricePredictor = getOrCreateModel(poolAddress);

        // Get current pool data
        PoolDataPoint currentData = dataCollectionService.getCurrentPoolData(poolAddress);

        // Make prediction
        BayesianPricePredictor.PricePrediction prediction =
                pricePredictor.predictPriceRange(currentData, confidenceLevel, timePeriodHours);

        // Calculate optimal tick ranges for the predicted price range
        int[] optimalTicks = blockchainService.calculateOptimalTicks(
                prediction.getLowerBound().doubleValue(),
                prediction.getUpperBound().doubleValue(),
                poolAddress
        );

        // Estimate fees based on predicted range and historical data
        double predictedFees = estimateFeesForRange(
                optimalTicks[0],
                optimalTicks[1],
                poolAddress,
                timePeriodHours
        );

        // Estimate potential impermanent loss
        double impermanentLoss = estimateImpermanentLoss(
                currentData.getToken0Price().doubleValue(),
                prediction.getMedian().doubleValue(),
                prediction.getStandardDeviation().doubleValue()
        );

        return PredictionResponse.builder()
                .lowerPriceRange(prediction.getLowerBound().doubleValue())
                .upperPriceRange(prediction.getUpperBound().doubleValue())
                .optimalLowerTick(optimalTicks[0])
                .optimalUpperTick(optimalTicks[1])
                .predictedFees(predictedFees)
                .confidenceLevel(confidenceLevel)
                .poolAddress(poolAddress)
                .timestamp(System.currentTimeMillis())
                .currentPrice(currentData.getToken0Price().doubleValue())
                .predictedImpermanentLoss(impermanentLoss)
                .build();
    }

    private double estimateFeesForRange(int lowerTick, int upperTick, String poolAddress, int timePeriodHours) {
        // Use historical data to estimate fees for the given range
        List<PoolDataPoint> recentData = dataCollectionService.collectHistoricalData(
                poolAddress,
                Instant.now().minus(Duration.ofDays(7)),
                Instant.now()
        );

        // Calculate fee estimate based on historical data
        // This is a simplified approach - production system would need more sophistication
        double avgHourlyFees = recentData.stream()
                .mapToDouble(dp -> dp.getFees24h().doubleValue() / 24.0)
                .average()
                .orElse(0.0);

        // Apply an adjustment based on how well the tick range covers historical price movements
        double rangeUtilization = calculateRangeUtilization(lowerTick, upperTick, recentData);

        return avgHourlyFees * timePeriodHours * rangeUtilization;
    }

    private double calculateRangeUtilization(int lowerTick, int upperTick, List<PoolDataPoint> historicalData) {
        // Calculate what percentage of time the price stayed within the given range
        long timeInRange = historicalData.stream()
                .filter(data -> data.getTick() >= lowerTick && data.getTick() <= upperTick)
                .count();

        return (double) timeInRange / historicalData.size();
    }

    private double estimateImpermanentLoss(double currentPrice, double predictedPrice, double stdDev) {
        // Using a common impermanent loss formula
        double priceRatio = predictedPrice / currentPrice;
        double impermanentLoss = 2 * Math.sqrt(priceRatio) / (1 + priceRatio) - 1;

        // Convert to percentage and adjust based on prediction uncertainty
        return Math.abs(impermanentLoss * 100) * (1 + stdDev / predictedPrice);
    }

    public String getModelStatus() {
        return getPoolModelStatus(defaultPoolAddress);
    }

    public String getPoolModelStatus(String poolAddress) {
        if (poolAddress == null || poolAddress.isEmpty()) {
            poolAddress = defaultPoolAddress;
        }

        AtomicBoolean trainingInProgress = trainingInProgressStatus.computeIfAbsent(
                poolAddress, k -> new AtomicBoolean(false));
        AtomicBoolean modelReady = modelReadyStatus.computeIfAbsent(
                poolAddress, k -> new AtomicBoolean(false));

        if (trainingInProgress.get()) {
            return "Model training in progress for pool: " + poolAddress;
        } else if (modelReady.get()) {
            return "Model ready for predictions for pool: " + poolAddress;
        } else {
            return "Model not ready yet for pool: " + poolAddress;
        }
    }

    /**
     * Get a list of all pools that currently have trained models
     */
    public List<String> getTrainedPools() {
        return modelReadyStatus.entrySet().stream()
                .filter(entry -> entry.getValue().get())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
}
