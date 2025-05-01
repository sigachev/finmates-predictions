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

    @Autowired
    GraphQLService graphQLService;

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
     * Predicts price range for a given pool for X days with liquidity amount
     */
    public PredictionResponse predictPriceRangeForDays(String poolAddress, double confidenceLevel,
                                                       int durationDays, double liquidityAmount) {
        // Validate inputs
        if (durationDays < 1 || durationDays > 30) {
            throw new IllegalArgumentException("Time period must be between 1 and 30 days");
        }

        // Convert days to hours for internal processing
        int timePeriodHours = durationDays * 24;

        return predictPriceRange(poolAddress, confidenceLevel, timePeriodHours, liquidityAmount);
    }

    /**
     * Predicts price range with liquidity amount parameter
     */
    public PredictionResponse predictPriceRange(String poolAddress, double confidenceLevel,
                                                int timePeriodHours, double liquidityAmount) {
        // Validate inputs
        if (timePeriodHours < 1 || timePeriodHours > 720) {
            throw new IllegalArgumentException("Time period must be between 1 and 720 hours (30 days)");
        }

        if (confidenceLevel < 0.5 || confidenceLevel > 0.99) {
            throw new IllegalArgumentException("Confidence level must be between 0.5 and 0.99");
        }

        if (liquidityAmount <= 0) {
            liquidityAmount = 1000.0; // Default to $1000 if invalid
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
                timePeriodHours,
                liquidityAmount
        );

        // Calculate fee percentage and annualized rate
        double feesPercentage = (predictedFees / liquidityAmount) * 100;
        double daysCount = timePeriodHours / 24.0;
        double annualizedFeesPercentage = feesPercentage * (365.0 / daysCount);

        // Estimate potential impermanent loss
        double impermanentLoss = estimateImpermanentLoss(
                currentData.getToken0Price().doubleValue(),
                prediction.getMedian().doubleValue(),
                prediction.getStandardDeviation().doubleValue()
        );

        // Convert impermanent loss to USD amount based on liquidity
        double impermanentLossAmount = (impermanentLoss / 100.0) * liquidityAmount;
        double impermanentLossPercentage = impermanentLoss; // Already a percentage

        Instant now = Instant.now();
        Instant endTime = now.plusSeconds(timePeriodHours * 3600);

        // Calculate net profit (fees - IL)
        double netProfit = predictedFees - impermanentLossAmount;

        // Log the prediction
        log.debug("Price prediction for pool {}: current={}, range=[{}, {}], ticks=[{}, {}], " +
                        "fees={}$ ({}%), IL={}%, duration={}h/{}d",
                poolAddress, currentData.getToken0Price(),
                prediction.getLowerBound(), prediction.getUpperBound(),
                optimalTicks[0], optimalTicks[1],
                predictedFees, feesPercentage, impermanentLossPercentage,
                timePeriodHours, timePeriodHours / 24);

        return PredictionResponse.builder()
                .lowerPriceRange(prediction.getLowerBound().doubleValue())
                .upperPriceRange(prediction.getUpperBound().doubleValue())
                .optimalLowerTick(optimalTicks[0])
                .optimalUpperTick(optimalTicks[1])
                .predictedFees(predictedFees)
                .feesPercentage(feesPercentage)
                .annualizedFeesPercentage(annualizedFeesPercentage)
                .confidenceLevel(confidenceLevel)
                .poolAddress(poolAddress)
                .timestamp(now.toEpochMilli())
                .currentPrice(currentData.getToken0Price().doubleValue())
                .predictedImpermanentLoss(impermanentLossAmount)
                .impermanentLossPercentage(impermanentLossPercentage)
                .predictionPeriodHours(timePeriodHours)
                .predictionPeriodDays(timePeriodHours / 24)
                .predictionEndTime(endTime)
                .liquidityAmount(liquidityAmount)
                .estimatedProfit(netProfit)
                .build();
    }


    private double estimateFeesForRange(int lowerTick, int upperTick, String poolAddress,
                                        int timePeriodHours, double liquidityAmount) {
        try {
            log.info("Starting fee estimation for ticks {} to {}", lowerTick, upperTick);

            // Get current pool data
            PoolDataPoint currentData = dataCollectionService.getCurrentPoolData(poolAddress);

            // Get pool info from The Graph
            double volume24h = currentData.getVolume24h().doubleValue();
            double totalLiquidity = currentData.getLiquidity().doubleValue();
            double feeTier = getPoolFeeTier(poolAddress) / 1000000.0; // Convert from ppm

            // Log real data from The Graph
            log.info("Real data from The Graph:");
            log.info("  - 24h Volume: ${}", volume24h);
            log.info("  - Total Liquidity: ${}", totalLiquidity);
            log.info("  - Fee Tier: {}%", feeTier * 100);

            // If volume or liquidity data is unusable, log warning
            if (volume24h <= 0 || Double.isNaN(volume24h)) {
                log.warn("Invalid volume data: {}", volume24h);
                volume24h = 1000000; // Fallback but log the warning
            }

            if (totalLiquidity <= 0 || Double.isNaN(totalLiquidity)) {
                log.warn("Invalid liquidity data: {}", totalLiquidity);
                totalLiquidity = 10000000; // Fallback but log the warning
            }

            // Days in the prediction period
            double days = timePeriodHours / 24.0;

            // Get tick distribution data from The Graph
            Map<Integer, Double> liquidityByTick = graphQLService.getTickLiquidityDistribution(poolAddress);

            log.info("Retrieved tick liquidity distribution with {} data points",
                    liquidityByTick.size());

            // If we couldn't get distribution data, use current tick as reference
            if (liquidityByTick.isEmpty()) {
                log.warn("No liquidity distribution data available, using current tick");

                // Create synthetic distribution around current tick
                int currentTick = currentData.getTick();
                liquidityByTick.put(currentTick, totalLiquidity * 0.5);
                liquidityByTick.put(currentTick - 1000, totalLiquidity * 0.25);
                liquidityByTick.put(currentTick + 1000, totalLiquidity * 0.25);

                log.info("Created synthetic distribution around current tick: {}", currentTick);
            }

            // Calculate active liquidity in the specified tick range
            double activeLiquidityInRange = 0.0;
            for (Map.Entry<Integer, Double> entry : liquidityByTick.entrySet()) {
                int tick = entry.getKey();
                double liquidityAtTick = entry.getValue();

                if (tick >= lowerTick && tick <= upperTick) {
                    activeLiquidityInRange += liquidityAtTick;
                }
            }

            log.info("Active liquidity in range {}-{}: ${}",
                    lowerTick, upperTick, activeLiquidityInRange);

            // Calculate percentage of liquidity covered by the range
            double liquidityCoverageRatio = activeLiquidityInRange / totalLiquidity;

            // Apply realistic bounds to avoid extreme values
            liquidityCoverageRatio = Math.min(Math.max(liquidityCoverageRatio, 0.01), 0.99);

            log.info("Liquidity coverage ratio: {}%", liquidityCoverageRatio * 100);

            // Calculate total fees generated by the pool over the time period
            double totalPoolFeesForPeriod = volume24h * feeTier * days;

            log.info("Total pool fees for period: ${}", totalPoolFeesForPeriod);

            // Calculate fees captured by liquidity in the range
            double feesInRange = totalPoolFeesForPeriod * liquidityCoverageRatio;

            log.info("Fees captured by liquidity in range: ${}", feesInRange);

            // Calculate your portion of the fees based on your share of active liquidity
            double yourLiquidityShare = liquidityAmount / (activeLiquidityInRange + liquidityAmount);

            // Cap at a realistic maximum
            yourLiquidityShare = Math.min(yourLiquidityShare, 0.1);

            log.info("Your liquidity share: {}%", yourLiquidityShare * 100);

            // Calculate your fee portion
            double yourFees = feesInRange * yourLiquidityShare;

            log.info("Your estimated fees: ${}", yourFees);

            // Calculate metrics for reference
            double feePercentage = (yourFees / liquidityAmount) * 100;
            double annualizedFeePercentage = feePercentage * (365.0 / days);

            log.info("Fee metrics:");
            log.info("  - Fee percentage: {}%", feePercentage);
            log.info("  - Annualized fee percentage: {}% APR", annualizedFeePercentage);

            return yourFees;
        } catch (Exception e) {
            log.error("Error estimating fees: {}", e.getMessage(), e);
            // Use a conservative fallback based on typical returns
            double days = timePeriodHours / 24.0;
            double typicalDailyFeeRate = 0.0003; // 0.03% daily (~10% APR)
            double fallbackFees = liquidityAmount * typicalDailyFeeRate * days;
            log.warn("Using fallback fee estimation due to error: ${}", fallbackFees);
            return fallbackFees;
        }
    }













    /**
     * Get tick spacing based on fee tier
     */
    private int getTickSpacing(double feeTierPpm) {
        if (feeTierPpm == 10000) return 200; // 1% fee tier
        if (feeTierPpm == 3000) return 60;   // 0.3% fee tier
        if (feeTierPpm == 500) return 10;    // 0.05% fee tier
        if (feeTierPpm == 100) return 1;     // 0.01% fee tier
        return 60; // Default to 0.3% tier spacing
    }



    /**
     * Calculate a concentration bonus for narrower tick ranges
     */
    private double calculateConcentrationBonus(int lowerTick, int upperTick) {
        // Wider tick ranges earn proportionally less fees due to capital inefficiency
        int tickWidth = upperTick - lowerTick;

        // A full range position is typically around 887272 ticks for most pools
        int fullRangeWidth = 887272;

        // Calculate concentration factor
        double concentrationFactor = Math.sqrt((double)fullRangeWidth / Math.max(tickWidth, 1));

        // Cap at a reasonable maximum
        return Math.min(concentrationFactor, 10.0);
    }

    /**
     * Calculate range utilization based on historical data
     */
    private double calculateRangeUtilization(int lowerTick, int upperTick, String poolAddress) {
        try {
            // Get recent historical data
            List<PoolDataPoint> recentData = dataCollectionService.collectHistoricalData(
                    poolAddress,
                    Instant.now().minus(Duration.ofDays(7)),
                    Instant.now()
            );

            if (recentData.isEmpty()) {
                log.warn("No historical data available for range utilization calculation");
                return 0.5; // Default 50% utilization as fallback
            }

            // Calculate percentage of time price was in range
            long timeInRange = recentData.stream()
                    .filter(data -> data.getTick() >= lowerTick && data.getTick() <= upperTick)
                    .count();

            double utilization = (double) timeInRange / recentData.size();
            log.debug("Range utilization calculation: {} out of {} data points in range ({}%)",
                    timeInRange, recentData.size(), utilization * 100);

            return utilization;
        } catch (Exception e) {
            log.warn("Error calculating range utilization: {}", e.getMessage());
            return 0.5; // Default 50% utilization
        }
    }




    // Helper method to get fee tier (add to your service)
    private double getPoolFeeTier(String poolAddress) {
        try {
            // Try to get from blockchain service if available
            // This should be the fee in parts per million (e.g., 3000 for 0.3%)
            return blockchainService.getPoolFeeTier(poolAddress);
        } catch (Exception e) {
            // Return a reasonable default (3000 = 0.3%)
            return 3000;
        }
    }

    // For backward compatibility
    public PredictionResponse predictPriceRange(String poolAddress, double confidenceLevel, int timePeriodHours) {
        // Default to 1000.0 as the liquidity amount
        return predictPriceRange(poolAddress, confidenceLevel, timePeriodHours, 1000.0);
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
        // Calculate price ratio
        double priceRatio = predictedPrice / currentPrice;

        if (priceRatio <= 0) return 0.0;

        // Standard IL formula
        double sqrtRatio = Math.sqrt(priceRatio);
        double il = 2 * sqrtRatio / (1 + priceRatio) - 1;

        // Convert to percentage and adjust for uncertainty
        double ilPercentage = Math.abs(il * 100);

        // Add risk premium based on volatility
        double volatilityAdjustment = (stdDev / predictedPrice);
        ilPercentage *= (1 + volatilityAdjustment);

        // Cap at realistic maximum
        return Math.min(ilPercentage, 30.0); // Max 30% IL
    }


    private void validatePriceRange(double lowerPrice, double upperPrice, double currentPrice) {
        if (lowerPrice <= 0 || upperPrice <= 0) {
            throw new IllegalArgumentException("Price range must be positive");
        }

        if (upperPrice < lowerPrice) {
            throw new IllegalArgumentException("Upper price must be greater than lower price");
        }

        // Check if range is too narrow or too wide
        double priceRatio = upperPrice / lowerPrice;
        if (priceRatio < 1.001 || priceRatio > 5.0) {
            throw new IllegalArgumentException("Price range is unrealistic");
        }
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
        try {
            // Get current pool data
            PoolDataPoint currentData = dataCollectionService.getCurrentPoolData(poolAddress);
            double volume24h = currentData.getVolume24h().doubleValue();
            double feeTier = getPoolFeeTier(poolAddress) / 1000000.0; // Convert from ppm to decimal

            log.info("Raw data from pool:");
            log.info("  - 24h Volume: ${}", volume24h);
            log.info("  - Total Liquidity: ${}", currentData.getLiquidity());
            log.info("  - Fee Tier: {}%", feeTier * 100);

            // Normalize extremely high volume (likely a data error)
            if (volume24h > 1e10) {
                volume24h = volume24h / 1e6; // Apply realistic scaling
                log.info("Normalized 24h volume to: ${}", volume24h);
            }

            // Get pool TVL for market share calculation
            double poolTVL = currentData.getLiquidity().doubleValue();
            if (poolTVL > 1e12) {
                poolTVL = poolTVL / 1e12; // Normalize extremely high TVL
                log.info("Normalized pool TVL to: ${}", poolTVL);
            }

            // Calculate market share with realistic caps
            double marketShare = liquidityAmount / (poolTVL + liquidityAmount);
            marketShare = Math.min(marketShare, 0.01); // Cap at 1% market share

            // Calculate range utilization
            double rangeUtilization = calculateRangeUtilization(lowerTick, upperTick, currentData.getTick());

            // Calculate daily fees with realistic constraints
            double dailyVolume = volume24h;
            double dailyFees = dailyVolume * feeTier * marketShare * rangeUtilization;

            // Apply realistic daily fee caps
            double maxDailyReturnRate = 0.003; // Max 0.3% daily return
            double maxDailyFees = liquidityAmount * maxDailyReturnRate;
            dailyFees = Math.min(dailyFees, maxDailyFees);

            // Calculate total fees for the period
            double totalFees = dailyFees * days;

            // Apply time decay factor (longer periods have more uncertainty)
            double timeDecayFactor = Math.exp(-0.05 * days / 30.0); // 5% decay per month
            totalFees *= timeDecayFactor;

            // Log detailed calculations
            log.info("Fee calculation details:");
            log.info("  - Market share: {}%", marketShare * 100);
            log.info("  - Range utilization: {}%", rangeUtilization * 100);
            log.info("  - Daily fees: ${}", dailyFees);
            log.info("  - Time decay factor: {}", timeDecayFactor);
            log.info("  - Total fees: ${}", totalFees);

            // Calculate and log APR
            double feePercentage = (totalFees / liquidityAmount) * 100;
            double annualizedFeePercentage = feePercentage * (365.0 / days);

            // Cap annualized returns at realistic levels
            double maxAnnualizedReturn = 100.0; // 100% APR cap
            if (annualizedFeePercentage > maxAnnualizedReturn) {
                double scaleFactor = maxAnnualizedReturn / annualizedFeePercentage;
                totalFees *= scaleFactor;
                feePercentage *= scaleFactor;
                annualizedFeePercentage = maxAnnualizedReturn;
            }

            log.info("Return metrics:");
            log.info("  - Fee percentage: {}%", feePercentage);
            log.info("  - Annualized fee percentage: {}% APR", annualizedFeePercentage);

            return totalFees;

        } catch (Exception e) {
            log.error("Error calculating fees: {}", e.getMessage());
            return calculateConservativeFees(days, liquidityAmount);
        }
    }

    private double calculateRangeUtilization(int lowerTick, int upperTick, int currentTick) {
        // Calculate how centered the current tick is in the range
        int rangeWidth = upperTick - lowerTick;
        int distanceFromCenter = Math.abs(currentTick - (lowerTick + rangeWidth / 2));

        // Higher utilization if price is centered in range
        double centeredness = Math.max(0, 1 - (distanceFromCenter / (double)(rangeWidth / 2)));

        // Narrow ranges get higher utilization
        double rangeMultiplier = Math.min(1.0, 2000.0 / rangeWidth);

        return centeredness * rangeMultiplier;
    }

    private double calculateConservativeFees(int days, double liquidityAmount) {
        // Conservative estimate: 10% APR
        double dailyRate = 0.10 / 365.0;
        return liquidityAmount * dailyRate * days;
    }


    private double calculateRangeCoverage(int lowerTick, int upperTick, int currentTick) {
        // Calculate how well the range covers the likely price movement
        int rangeWidth = upperTick - lowerTick;
        int distanceFromCurrent = Math.max(
                Math.abs(currentTick - lowerTick),
                Math.abs(currentTick - upperTick)
        );

        // Normalize to a 0-1 range with exponential decay
        return Math.exp(-distanceFromCurrent / (double)rangeWidth);
    }

    private double calculateRiskAdjustment(int days) {
        // Longer time periods have higher uncertainty
        return Math.exp(-0.05 * days);
    }









    /**
     * Conservative fee estimation fallback
     */
    private double estimateConservativeFees(int days, double liquidityAmount) {
        // Assume conservative 5% APR
        double dailyRate = 0.05 / 365.0;
        return liquidityAmount * dailyRate * days;
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


    private double calculateFeeEstimate(String poolAddress, double volume24h, double feeTier,
                                        double liquidityShare, double rangeUtilization,
                                        int timePeriodHours) {
        try {
            // Get current pool data for volatility
            PoolDataPoint currentData = dataCollectionService.getCurrentPoolData(poolAddress);
            double volatility24h = currentData.getVolatility24h().doubleValue();

            // Add volatility adjustment - higher volatility typically means more trading and fees
            double volatilityMultiplier = calculateVolatilityMultiplier(volatility24h);

            // Add time decay factor - longer predictions are less certain
            double timeDecayFactor = Math.exp(-0.1 * timePeriodHours / 24.0);

            // Calculate base fee estimate
            double baseFeeEstimate = volume24h * (feeTier/1000000.0) * liquidityShare *
                    rangeUtilization * (timePeriodHours/24.0);

            // Apply adjustments
            double adjustedFeeEstimate = baseFeeEstimate * volatilityMultiplier * timeDecayFactor;

            log.debug("Fee estimation components for pool {}:", poolAddress);
            log.debug("- Base estimate: ${}", baseFeeEstimate);
            log.debug("- Volatility multiplier: {}", volatilityMultiplier);
            log.debug("- Time decay factor: {}", timeDecayFactor);
            log.debug("- Final adjusted estimate: ${}", adjustedFeeEstimate);

            return adjustedFeeEstimate;

        } catch (Exception e) {
            log.warn("Error in fee calculation, using conservative estimate: {}", e.getMessage());
            // Fallback to simple calculation
            return volume24h * (feeTier/1000000.0) * liquidityShare * (timePeriodHours/24.0);
        }
    }

    /**
     * Calculate volatility-based fee adjustment
     */
    private double calculateVolatilityMultiplier(double volatility24h) {
        // Base multiplier of 1.0
        // Add up to 50% more fees for high volatility
        // Subtract up to 25% for very low volatility

        // Normalize volatility (typical range 0-100%)
        double normalizedVolatility = Math.min(Math.max(volatility24h, 0), 1);

        // Calculate multiplier: range 0.75 to 1.5
        double multiplier = 1.0 + (normalizedVolatility * 0.5) - 0.25;

        log.debug("Calculated volatility multiplier: {} (from volatility: {})",
                multiplier, volatility24h);

        return multiplier;
    }


}