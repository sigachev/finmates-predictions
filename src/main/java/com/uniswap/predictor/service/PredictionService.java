package com.uniswap.predictor.service;

import com.uniswap.predictor.dto.PoolDataPoint;
import com.uniswap.predictor.dto.PredictionResponse;
import com.uniswap.predictor.model.BayesianPricePredictor;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
public class PredictionService {
    // Add a class to store detailed training status
    public static class TrainingStatus {
        private final AtomicInteger progressPercentage = new AtomicInteger(0);
        private final AtomicBoolean inProgress = new AtomicBoolean(false);
        private final AtomicBoolean modelReady = new AtomicBoolean(false);
        private final AtomicInteger currentEpoch = new AtomicInteger(0);
        private final AtomicInteger totalEpochs = new AtomicInteger(0);
        private final AtomicReference<Double> latestScore = new AtomicReference<>(0.0);
        private final AtomicLong lastUpdateTime = new AtomicLong(0);

        public int getProgressPercentage() { return progressPercentage.get(); }
        public void setProgressPercentage(int value) { progressPercentage.set(value); }

        public boolean isInProgress() { return inProgress.get(); }
        public void setInProgress(boolean value) { inProgress.set(value); }

        public boolean isModelReady() { return modelReady.get(); }
        public void setModelReady(boolean value) { modelReady.set(value); }

        public int getCurrentEpoch() { return currentEpoch.get(); }
        public void setCurrentEpoch(int value) { currentEpoch.set(value); }

        public int getTotalEpochs() { return totalEpochs.get(); }
        public void setTotalEpochs(int value) { totalEpochs.set(value); }

        public double getLatestScore() { return latestScore.get(); }
        public void setLatestScore(double value) { latestScore.set(value); }

        public long getLastUpdateTime() { return lastUpdateTime.get(); }
        public void setLastUpdateTime(long value) { lastUpdateTime.set(value); }
    }

    // Replace the existing status maps with a single map of TrainingStatus objects
    private final Map<String, TrainingStatus> trainingStatusByPool = new ConcurrentHashMap<>();

    private final BlockchainService blockchainService;
    private final DataCollectionService dataCollectionService;

    @Value("${uniswap.default.pool:0x641C00A822e8b671738d32a431a4Fb6074E5c79d}")
    private String defaultPoolAddress;

    private final Map<String, BayesianPricePredictor> modelsByPool = new ConcurrentHashMap<>();

    @Autowired
    public PredictionService(BlockchainService blockchainService, DataCollectionService dataCollectionService) {
        this.blockchainService = blockchainService;
        this.dataCollectionService = dataCollectionService;
    }

    @PostConstruct
    public void initialize() {
        try {
            // Initialize default model
            getOrCreateModel(defaultPoolAddress);

            // Start training in a separate thread to avoid blocking app startup
            new Thread(() -> {
                try {
                    retrainModel();
                } catch (Exception e) {
                    System.err.println("Error during initial model training: " + e.getMessage());
                    e.printStackTrace();
                }
            }).start();
        } catch (Exception e) {
            // Log the error but don't prevent app from starting
            System.err.println("Error initializing prediction service: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Scheduled(fixedRate = 86400000) // Retrain every 24 hours
    public void scheduledModelUpdate() {
        retrainModel();
    }

    public void retrainModel() {
        retrainModel(defaultPoolAddress);
    }

    // Method to get training status
    private TrainingStatus getTrainingStatus(String poolAddress) {
        if (poolAddress == null || poolAddress.isEmpty()) {
            poolAddress = defaultPoolAddress;
        }

        return trainingStatusByPool.computeIfAbsent(poolAddress, k -> new TrainingStatus());
    }

    // New method to get training progress details
    public Map<String, Object> getTrainingProgressDetails(String poolAddress) {
        if (poolAddress == null || poolAddress.isEmpty()) {
            poolAddress = defaultPoolAddress;
        }

        TrainingStatus status = getTrainingStatus(poolAddress);

        Map<String, Object> result = new HashMap<>();
        result.put("poolAddress", poolAddress);
        result.put("progressPercentage", status.getProgressPercentage());
        result.put("inProgress", status.isInProgress());
        result.put("modelReady", status.isModelReady());
        result.put("currentEpoch", status.getCurrentEpoch());
        result.put("totalEpochs", status.getTotalEpochs());
        result.put("latestScore", status.getLatestScore());
        result.put("lastUpdateTime", status.getLastUpdateTime());

        return result;
    }

    public void retrainModel(String poolAddress) {
        if (poolAddress == null || poolAddress.isEmpty()) {
            poolAddress = defaultPoolAddress;
        }

        // Get the training status
        final TrainingStatus status = getTrainingStatus(poolAddress);

        // Check if training is already in progress
        if (status.isInProgress()) {
            return;
        }

        // Mark training as in progress
        status.setInProgress(true);
        status.setProgressPercentage(0);
        status.setModelReady(false);
        status.setLastUpdateTime(System.currentTimeMillis());

        final String finalPoolAddress = poolAddress;

        Thread trainingThread = new Thread(() -> {
            try {
                System.out.println("Starting model training for pool: " + finalPoolAddress);

                // Initialize - 0-10%
                status.setProgressPercentage(0);
                status.setLastUpdateTime(System.currentTimeMillis());
                System.out.println("Progress: 0% - Starting training for pool: " + finalPoolAddress);

                // Get or create the model
                BayesianPricePredictor pricePredictor = getOrCreateModel(finalPoolAddress);

                status.setProgressPercentage(10);
                status.setLastUpdateTime(System.currentTimeMillis());
                System.out.println("Progress: 10% - Model initialized for pool: " + finalPoolAddress);

                // Data collection - 10-30%
                status.setProgressPercentage(15);
                status.setLastUpdateTime(System.currentTimeMillis());
                System.out.println("Progress: 15% - Collecting historical data for pool: " + finalPoolAddress);

                List<PoolDataPoint> historicalData = dataCollectionService.collectHistoricalData(
                        finalPoolAddress,
                        Instant.now().minus(Duration.ofDays(30)),
                        Instant.now()
                );

                status.setProgressPercentage(30);
                status.setLastUpdateTime(System.currentTimeMillis());
                System.out.println("Progress: 30% - Historical data collected: " + historicalData.size() + " points");

                // Data preparation - 30-40%
                status.setProgressPercentage(35);
                status.setLastUpdateTime(System.currentTimeMillis());
                System.out.println("Progress: 35% - Preparing training data");

                // Train if enough data
                if (historicalData.size() > 24) {
                    status.setProgressPercentage(40);
                    status.setLastUpdateTime(System.currentTimeMillis());
                    System.out.println("Progress: 40% - Beginning model training");

                    // Store total epochs for reference
                    status.setTotalEpochs(pricePredictor.getNumEpochs());

                    // Create progress callback
                    BayesianPricePredictor.TrainingProgressCallback progressCallback =
                            (epoch, totalEpochs, score) -> {
                                // Update epoch and score info
                                status.setCurrentEpoch(epoch + 1); // 1-based for display
                                status.setLatestScore(score);

                                // Calculate progress (40-90% during training)
                                int progress = 40 + (int)((epoch + 1) * 50.0 / totalEpochs);
                                status.setProgressPercentage(progress);
                                status.setLastUpdateTime(System.currentTimeMillis());

                                // Log meaningful updates
                                if (epoch == 0 || (epoch + 1) == totalEpochs || (epoch + 1) % 10 == 0) {
                                    System.out.println("Progress: " + progress + "% - Training epoch "
                                            + (epoch + 1) + "/" + totalEpochs
                                            + " for pool: " + finalPoolAddress
                                            + " (score: " + String.format("%.4f", score) + ")");
                                }
                            };

                    // Train with progress tracking
                    pricePredictor.train(historicalData, progressCallback);

                    // Finalization - 90-100%
                    status.setProgressPercentage(90);
                    status.setLastUpdateTime(System.currentTimeMillis());
                    System.out.println("Progress: 90% - Core training completed");

                    // Finalize
                    status.setProgressPercentage(100);
                    status.setModelReady(true);
                    status.setLastUpdateTime(System.currentTimeMillis());
                    System.out.println("Progress: 100% - Model training completed for pool: " + finalPoolAddress);
                } else {
                    // Log insufficient data
                    System.err.println("Insufficient historical data for pool: " + finalPoolAddress);
                    status.setProgressPercentage(-1); // Use negative value to indicate error
                    status.setLastUpdateTime(System.currentTimeMillis());
                }
            } catch (Exception e) {
                System.err.println("Error training model for pool: " + finalPoolAddress);
                e.printStackTrace();
                status.setProgressPercentage(-1);
                status.setLastUpdateTime(System.currentTimeMillis());
            } finally {
                status.setInProgress(false);
            }
        });

        trainingThread.setDaemon(true);
        trainingThread.start();
    }

    private BayesianPricePredictor getOrCreateModel(String poolAddress) {
        return modelsByPool.computeIfAbsent(poolAddress, k -> {
            // Create new model
            return new BayesianPricePredictor();
        });
    }

    /**
     * Predicts price range for a given pool
     * @param poolAddress The Uniswap V3 pool address
     * @param confidenceLevel Confidence level (e.g., 0.95 for 95%)
     * @param timePeriodHours Prediction period in hours (1-168)
     * @return PredictionResponse containing price ranges and related metrics
     * @throws IllegalArgumentException if timePeriodHours is invalid
     */
    public PredictionResponse predictPriceRange(String poolAddress, double confidenceLevel, int timePeriodHours) {
        // Default to WETH/USDT pool if not specified
        if (poolAddress == null || poolAddress.isEmpty()) {
            poolAddress = defaultPoolAddress;
        }

        // Check if model exists and is ready
        TrainingStatus status = getTrainingStatus(poolAddress);

        if (!status.isModelReady()) {
            if (!status.isInProgress()) {
                // Start training if not already in progress
                retrainModel(poolAddress);
            }

            throw new IllegalStateException("Model for pool " + poolAddress +
                    " is not ready yet (" + status.getProgressPercentage() + "% complete). Please try again later.");
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

        Instant now = Instant.now();
        Instant endTime = now.plusSeconds(timePeriodHours * 3600);

        return PredictionResponse.builder()
                .lowerPriceRange(prediction.getLowerBound().doubleValue())
                .upperPriceRange(prediction.getUpperBound().doubleValue())
                .optimalLowerTick(optimalTicks[0])
                .optimalUpperTick(optimalTicks[1])
                .predictedFees(predictedFees)
                .confidenceLevel(confidenceLevel)
                .poolAddress(poolAddress)
                .timestamp(now.toEpochMilli())
                .currentPrice(currentData.getToken0Price().doubleValue())
                .predictedImpermanentLoss(impermanentLoss)
                .predictionPeriodHours(timePeriodHours)  // Add this
                .predictionEndTime(endTime)              // Add this
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

        TrainingStatus status = getTrainingStatus(poolAddress);

        if (status.isInProgress()) {
            return "Model training in progress for pool: " + poolAddress +
                    " (" + status.getProgressPercentage() + "% complete, " +
                    "epoch " + status.getCurrentEpoch() + "/" + status.getTotalEpochs() + ")";
        } else if (status.isModelReady()) {
            return "Model ready for predictions for pool: " + poolAddress;
        } else {
            return "Model not ready yet for pool: " + poolAddress;
        }
    }

    /**
     * Get a list of all pools that currently have trained models
     */
    public List<String> getTrainedPools() {
        return trainingStatusByPool.entrySet().stream()
                .filter(entry -> entry.getValue().isModelReady())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
}