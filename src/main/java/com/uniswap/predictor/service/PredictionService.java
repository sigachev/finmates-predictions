package com.uniswap.predictor.service;

import com.uniswap.predictor.dto.PoolDataPoint;
import com.uniswap.predictor.dto.PredictionResponse;
import com.uniswap.predictor.model.BayesianPricePredictor;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
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

@Slf4j
@Service
public class PredictionService {
    // Class to store detailed training status
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

    // Map of training status for each pool
    private final Map<String, TrainingStatus> trainingStatusByPool = new ConcurrentHashMap<>();

    private final BlockchainService blockchainService;
    private final DataCollectionService dataCollectionService;

    // Configuration values
    @Value("${uniswap.default.pool:0x641C00A822e8b671738d32a431a4Fb6074E5c79d}")
    private String defaultPoolAddress;

    @Value("${model.retraining.schedule.hours:24}")
    private int retrainingIntervalHours;

    @Value("${model.epochs:10}")
    private int trainingEpochs;

    @Value("${model.historical.days:30}")
    private int historicalDataDays;

    // UPDATED: New configuration for training data timeframe
    @Value("${model.training.days:30}")
    private int trainingDataDays;

    // Map of models for each pool
    private final Map<String, BayesianPricePredictor> modelsByPool = new ConcurrentHashMap<>();

    @Autowired
    public PredictionService(BlockchainService blockchainService, DataCollectionService dataCollectionService) {
        this.blockchainService = blockchainService;
        this.dataCollectionService = dataCollectionService;
    }

    @PostConstruct
    public void initialize() {
        try {
            log.info("Initializing prediction service with default pool: {}", defaultPoolAddress);

            // Initialize default model
            getOrCreateModel(defaultPoolAddress);

            // Start training in a separate thread to avoid blocking app startup
            new Thread(() -> {
                try {
                    retrainModel();
                } catch (Exception e) {
                    log.error("Error during initial model training: {}", e.getMessage(), e);
                }
            }).start();
        } catch (Exception e) {
            // Log the error but don't prevent app from starting
            log.error("Error initializing prediction service: {}", e.getMessage(), e);
        }
    }

    @Scheduled(fixedRateString = "${model.retraining.schedule.ms:86400000}") // Default: Retrain every 24 hours
    public void scheduledModelUpdate() {
        log.info("Scheduled model retraining triggered");
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

    // Method to get training progress details
    public Map<String, Object> getTrainingProgressDetails(String poolAddress) {
        if (poolAddress == null || poolAddress.isEmpty()) {
            poolAddress = defaultPoolAddress;
        }

        TrainingStatus status = getTrainingStatus(poolAddress);
        String token0Symbol = "Unknown";
        String token1Symbol = "Unknown";

        try {
            // Try to get token symbols for more user-friendly display
            String token0Address = blockchainService.getTokenAddress(poolAddress, "0");
            String token1Address = blockchainService.getTokenAddress(poolAddress, "1");
            token0Symbol = blockchainService.getTokenSymbol(token0Address);
            token1Symbol = blockchainService.getTokenSymbol(token1Address);
        } catch (Exception e) {
            log.warn("Could not retrieve token symbols for pool {}: {}", poolAddress, e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("poolAddress", poolAddress);
        result.put("token0Symbol", token0Symbol);
        result.put("token1Symbol", token1Symbol);
        result.put("progressPercentage", status.getProgressPercentage());
        result.put("inProgress", status.isInProgress());
        result.put("modelReady", status.isModelReady());
        result.put("currentEpoch", status.getCurrentEpoch());
        result.put("totalEpochs", status.getTotalEpochs());
        result.put("latestScore", status.getLatestScore());
        result.put("lastUpdateTime", status.getLastUpdateTime());
        result.put("trainingDataDays", trainingDataDays); // Add training days info

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
            log.info("Training already in progress for pool {}, skipping", poolAddress);
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
                log.info("Starting model training for pool: {}", finalPoolAddress);

                // Initialize - 0-10%
                status.setProgressPercentage(0);
                status.setLastUpdateTime(System.currentTimeMillis());
                log.info("Progress: 0% - Starting training for pool: {}", finalPoolAddress);

                // Get or create the model
                BayesianPricePredictor pricePredictor = getOrCreateModel(finalPoolAddress);

                status.setProgressPercentage(10);
                status.setLastUpdateTime(System.currentTimeMillis());
                log.info("Progress: 10% - Model initialized for pool: {}", finalPoolAddress);

                // Data collection - 10-30%
                status.setProgressPercentage(15);
                status.setLastUpdateTime(System.currentTimeMillis());
                log.info("Progress: 15% - Collecting historical data for pool: {}", finalPoolAddress);

                // UPDATED: Use trainingDataDays instead of hardcoded value
                List<PoolDataPoint> historicalData = dataCollectionService.collectHistoricalData(
                        finalPoolAddress,
                        Instant.now().minus(Duration.ofDays(trainingDataDays)),
                        Instant.now()
                );

                status.setProgressPercentage(30);
                status.setLastUpdateTime(System.currentTimeMillis());
                log.info("Progress: 30% - Historical data collected: {} points over {} days",
                        historicalData.size(), trainingDataDays);

                // Data preparation - 30-40%
                status.setProgressPercentage(35);
                status.setLastUpdateTime(System.currentTimeMillis());
                log.info("Progress: 35% - Preparing training data");

                // Train if enough data
                if (historicalData.size() > 24) {
                    status.setProgressPercentage(40);
                    status.setLastUpdateTime(System.currentTimeMillis());
                    log.info("Progress: 40% - Beginning model training");

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
                                    log.info("Progress: {}% - Training epoch {}/{} for pool: {} (score: {})",
                                            progress, (epoch + 1), totalEpochs, finalPoolAddress, score);
                                }
                            };

                    // Train with progress tracking
                    pricePredictor.train(historicalData, progressCallback);

                    // Finalization - 90-100%
                    status.setProgressPercentage(90);
                    status.setLastUpdateTime(System.currentTimeMillis());
                    log.info("Progress: 90% - Core training completed");

                    // Finalize
                    status.setProgressPercentage(100);
                    status.setModelReady(true);
                    status.setLastUpdateTime(System.currentTimeMillis());
                    log.info("Progress: 100% - Model training completed for pool: {}", finalPoolAddress);
                } else {
                    // Log insufficient data
                    log.error("Insufficient historical data for pool {}: {} points (need at least 24)",
                            finalPoolAddress, historicalData.size());
                    status.setProgressPercentage(-1); // Use negative value to indicate error
                    status.setLastUpdateTime(System.currentTimeMillis());
                }
            } catch (Exception e) {
                log.error("Error training model for pool {}: {}", finalPoolAddress, e.getMessage(), e);
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
     * Predicts price range for a given pool for X days
     * @param poolAddress The Uniswap V3 pool address
     * @param confidenceLevel Confidence level (e.g., 0.95 for 95%)
     * @param durationDays Prediction period in days (1-30)
     * @return PredictionResponse containing price ranges and related metrics
     * @throws IllegalArgumentException if durationDays is invalid
     */
    public PredictionResponse predictPriceRangeForDays(String poolAddress, double confidenceLevel, int durationDays) {
        // Validate inputs
        if (durationDays < 1 || durationDays > 30) {
            throw new IllegalArgumentException("Time period must be between 1 and 30 days");
        }

        // Convert days to hours for internal processing
        int timePeriodHours = durationDays * 24;

        return predictPriceRange(poolAddress, confidenceLevel, timePeriodHours, durationDays);
    }

    /**
     * Predicts price range for a given pool
     * @param poolAddress The Uniswap V3 pool address
     * @param confidenceLevel Confidence level (e.g., 0.95 for 95%)
     * @param timePeriodHours Prediction period in hours (1-720)
     * @param durationDays Optional parameter for days duration (for display)
     * @return PredictionResponse containing price ranges and related metrics
     * @throws IllegalArgumentException if timePeriodHours is invalid
     */
    public PredictionResponse predictPriceRange(String poolAddress, double confidenceLevel, int timePeriodHours, Integer durationDays) {
        // Validate inputs
        if (timePeriodHours < 1 || timePeriodHours > 720) { // Max 30 days
            throw new IllegalArgumentException("Time period must be between 1 and 720 hours (30 days)");
        }

        if (confidenceLevel < 0.5 || confidenceLevel > 0.99) {
            throw new IllegalArgumentException("Confidence level must be between 0.5 and 0.99");
        }

        // Default to the configured pool if not specified
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

        // Log the prediction
        log.debug("Price prediction for pool {}: current={}, range=[{}, {}], ticks=[{}, {}], duration={}h/{}d",
                poolAddress, currentData.getToken0Price(),
                prediction.getLowerBound(), prediction.getUpperBound(),
                optimalTicks[0], optimalTicks[1],
                timePeriodHours, durationDays != null ? durationDays : timePeriodHours / 24);

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
                .predictionPeriodHours(timePeriodHours)
                .predictionPeriodDays(durationDays != null ? durationDays : timePeriodHours / 24)
                .predictionEndTime(endTime)
                .build();
    }

    // For backward compatibility
    public PredictionResponse predictPriceRange(String poolAddress, double confidenceLevel, int timePeriodHours) {
        return predictPriceRange(poolAddress, confidenceLevel, timePeriodHours, null);
    }

    private double estimateFeesForRange(int lowerTick, int upperTick, String poolAddress, int timePeriodHours) {
        // Use historical data to estimate fees for the given range
        List<PoolDataPoint> recentData = dataCollectionService.collectHistoricalData(
                poolAddress,
                Instant.now().minus(Duration.ofDays(7)),
                Instant.now()
        );

        if (recentData.isEmpty()) {
            log.warn("No historical data available for fee estimation for pool: {}", poolAddress);
            return 0.0;
        }

        // Calculate fee estimate based on historical data
        double avgHourlyFees = recentData.stream()
                .mapToDouble(dp -> dp.getFees24h().doubleValue() / 24.0)
                .average()
                .orElse(0.0);

        // Apply an adjustment based on how well the tick range covers historical price movements
        double rangeUtilization = calculateRangeUtilization(lowerTick, upperTick, recentData);

        log.debug("Fee estimation for pool {}: avgHourlyFees={}, rangeUtilization={}, hours={}",
                poolAddress, avgHourlyFees, rangeUtilization, timePeriodHours);

        return avgHourlyFees * timePeriodHours * rangeUtilization;
    }

    /**
     * Calculate estimated profit from providing liquidity over a given period
     * @param lowerTick Lower tick of position
     * @param upperTick Upper tick of position
     * @param poolAddress Pool address
     * @param days Number of days for the position
     * @return Estimated profit in USD
     */
    public double estimatePositionProfit(int lowerTick, int upperTick, String poolAddress, int days) {
        int hours = days * 24;

        // Estimated fees
        double feesEarned = estimateFeesForRange(lowerTick, upperTick, poolAddress, hours);

        // Get current pool data
        PoolDataPoint currentData = dataCollectionService.getCurrentPoolData(poolAddress);

        // Predict future price for impermanent loss calculation
        BayesianPricePredictor pricePredictor = getOrCreateModel(poolAddress);
        BayesianPricePredictor.PricePrediction prediction =
                pricePredictor.predictPriceRange(currentData, 0.80, hours);

        // Calculate impermanent loss as percentage
        double impermanentLossPercent = estimateImpermanentLoss(
                currentData.getToken0Price().doubleValue(),
                prediction.getMedian().doubleValue(),
                prediction.getStandardDeviation().doubleValue()
        );

        // Assume a standard liquidity amount for calculation
        // This could be improved to take actual liquidity as input
        double estimatedLiquidity = 10000.0; // Example amount in USD

        // Calculate impermanent loss in USD
        double impermanentLossUSD = (impermanentLossPercent / 100.0) * estimatedLiquidity;

        // Calculate net profit
        double netProfit = feesEarned - impermanentLossUSD;

        log.debug("Profit estimation for pool {}, days={}: fees={}, IL={}, net={}",
                poolAddress, days, feesEarned, impermanentLossUSD, netProfit);

        return netProfit;
    }

    private double calculateRangeUtilization(int lowerTick, int upperTick, List<PoolDataPoint> historicalData) {
        // Calculate what percentage of time the price stayed within the given range
        long timeInRange = historicalData.stream()
                .filter(data -> data.getTick() >= lowerTick && data.getTick() <= upperTick)
                .count();

        return historicalData.isEmpty() ? 0.0 : (double) timeInRange / historicalData.size();
    }

    private double estimateImpermanentLoss(double currentPrice, double predictedPrice, double stdDev) {
        // Using a common impermanent loss formula
        double priceRatio = predictedPrice / currentPrice;

        // Prevent division by zero or negative values
        if (priceRatio <= 0) {
            return 0.0;
        }

        double impermanentLoss = 2 * Math.sqrt(priceRatio) / (1 + priceRatio) - 1;

        // Convert to percentage and adjust based on prediction uncertainty
        return Math.abs(impermanentLoss * 100) * (1 + stdDev / Math.max(0.0001, predictedPrice));
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


    /**
     * Estimate fees for a position based on liquidity amount
     */
    public double estimateFeesForPosition(int lowerTick, int upperTick, String poolAddress,
                                          int days, double liquidityAmount) {
        int hours = days * 24;

        // Calculate base fee estimation (as a percentage of total pool fees)
        double baseFees = estimateFeesForRange(lowerTick, upperTick, poolAddress, hours);

        // Get current pool data for total liquidity reference
        PoolDataPoint currentData = dataCollectionService.getCurrentPoolData(poolAddress);
        double totalPoolLiquidity = currentData.getLiquidity().doubleValue();

        // Avoid division by zero
        if (totalPoolLiquidity <= 0) {
            return baseFees; // Fall back to base estimation
        }

        // Calculate position's share of total fees based on its proportion of liquidity
        double liquidityShare = liquidityAmount / (totalPoolLiquidity + liquidityAmount);

        return baseFees * liquidityShare;
    }

    /**
     * Estimate impermanent loss for a position
     */
    public double estimateImpermanentLossForPosition(String poolAddress, int days, double liquidityAmount) {
        // Get current pool data
        PoolDataPoint currentData = dataCollectionService.getCurrentPoolData(poolAddress);
        double currentPrice = currentData.getToken0Price().doubleValue();

        // Predict future price
        BayesianPricePredictor pricePredictor = getOrCreateModel(poolAddress);
        BayesianPricePredictor.PricePrediction prediction =
                pricePredictor.predictPriceRange(currentData, 0.80, days * 24);

        // Calculate impermanent loss as percentage
        double ilPercent = estimateImpermanentLoss(
                currentPrice,
                prediction.getMedian().doubleValue(),
                prediction.getStandardDeviation().doubleValue()
        );

        // Convert percentage to USD amount
        return (ilPercent / 100.0) * liquidityAmount;
    }

    /**
     * Helper methods for token information
     */
    public String getTokenAddress(String poolAddress, String tokenIndex) throws IOException {
        return blockchainService.getTokenAddress(poolAddress, tokenIndex);
    }

    public String getTokenSymbol(String tokenAddress) {
        return blockchainService.getTokenSymbol(tokenAddress);
    }
}