package com.finmates.predictions.service;

import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.springframework.stereotype.Service;
import com.finmates.predictions.model.PriceData;
import com.finmates.predictions.model.PriceRange;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

import java.util.HashMap;
import java.util.List;
import java.time.Instant;
import java.util.Map;

@Slf4j
@Service
public class PricePredictionService {
    private final SimpleRegression regression;
    private final DescriptiveStatistics statistics;
    private List<PriceData> lastTrainingData;
    private long lastTrainingTime;
    private static final long TRAINING_VALIDITY_PERIOD = 300; // 5 minutes in seconds

    public PricePredictionService() {
        this.regression = new SimpleRegression();
        this.statistics = new DescriptiveStatistics();
        this.lastTrainingTime = 0;
    }

    public void trainModel(List<PriceData> historicalData) {
        // Reset the regression and statistics
        regression.clear();
        statistics.clear();

        if (historicalData == null || historicalData.isEmpty()) {
            log.warn("No historical data provided for training");
            return;
        }

        // Sort data by timestamp to ensure proper ordering
        historicalData.sort((a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()));

        // Calculate time-based indices for regression
        long baseTimestamp = historicalData.get(0).getTimestamp();

        // Add data points to regression and statistics
        for (int i = 0; i < historicalData.size(); i++) {
            PriceData data = historicalData.get(i);
            double timeIndex = (data.getTimestamp() - baseTimestamp) / 3600.0; // Convert to hours
            regression.addData(timeIndex, data.getPrice());
            statistics.addValue(data.getPrice());
        }

        this.lastTrainingData = historicalData;
        this.lastTrainingTime = Instant.now().getEpochSecond();

        log.debug("Model trained with {} data points. R-squared: {}",
                historicalData.size(), regression.getRSquare());
    }

    public PriceRange predictPriceRange(int hoursAhead) {
        if (lastTrainingData == null || lastTrainingData.isEmpty()) {
            log.error("No training data available for prediction");
            return new PriceRange(0, 0);
        }

        // Get the last known price
        double lastPrice = lastTrainingData.get(lastTrainingData.size() - 1).getPrice();

        // Calculate the prediction
        double timeIndex = regression.getN() + hoursAhead;
        double predictedPrice = regression.predict(timeIndex);

        // Ensure prediction is reasonable
        if (predictedPrice <= 0) {
            predictedPrice = lastPrice;
        }

        // Calculate confidence interval
        double standardDeviation = statistics.getStandardDeviation();
        double volatilityAdjustment = Math.sqrt(hoursAhead / 24.0); // Scale with square root of time
        double confidenceInterval = 1.96 * standardDeviation * volatilityAdjustment;

        // Calculate trend-based adjustment
        double trendAdjustment = calculateTrendAdjustment(hoursAhead);

        // Apply adjustments to the range
        double lowerBound = Math.max(0, predictedPrice - confidenceInterval + trendAdjustment);
        double upperBound = predictedPrice + confidenceInterval + trendAdjustment;

        log.debug("Prediction for {} hours ahead: {} [{} - {}]",
                hoursAhead, predictedPrice, lowerBound, upperBound);

        return new PriceRange(lowerBound, upperBound);
    }

    private double calculateTrendAdjustment(int hoursAhead) {
        if (lastTrainingData.size() < 2) return 0;

        // Calculate recent trend
        int recentDataPoints = Math.min(24, lastTrainingData.size()); // Use last 24 hours or all available data
        List<PriceData> recentData = lastTrainingData.subList(
                lastTrainingData.size() - recentDataPoints,
                lastTrainingData.size()
        );

        double firstPrice = recentData.get(0).getPrice();
        double lastPrice = recentData.get(recentData.size() - 1).getPrice();
        double hourlyTrend = (lastPrice - firstPrice) / recentDataPoints;

        return hourlyTrend * hoursAhead * 0.5; // Dampen the trend effect by 50%
    }

    public boolean needsRetraining() {
        return Instant.now().getEpochSecond() - lastTrainingTime > TRAINING_VALIDITY_PERIOD;
    }


    public Map<String, Object> getModelMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        // Basic metrics
        double rSquared = getRSquared();
        double volatility = getVolatility();
        double meanPrice = getMeanPrice();

        metrics.put("rSquared", rSquared);
        metrics.put("volatility", volatility);
        metrics.put("dataPoints", lastTrainingData != null ? lastTrainingData.size() : 0);
        metrics.put("meanPrice", meanPrice);
        metrics.put("lastTrainingTime", lastTrainingTime);

        // Regression metrics
        if (regression != null) {
            metrics.put("slope", regression.getSlope());
            metrics.put("intercept", regression.getIntercept());
            metrics.put("standardError", calculateStandardError());
            metrics.put("predictionInterval", calculatePredictionInterval());
        }

        // Price statistics
        if (statistics != null) {
            metrics.put("minPrice", statistics.getMin());
            metrics.put("maxPrice", statistics.getMax());
            metrics.put("priceStdDev", statistics.getStandardDeviation());
        }

        // Format percentages
        metrics.put("modelQualityPercent", String.format("%.2f%%", rSquared * 100));
        metrics.put("volatilityPercent", String.format("%.2f%%", volatility * 100));

        return metrics;
    }

    private double calculateStandardError() {
        if (lastTrainingData == null || lastTrainingData.size() < 3) {
            return 0.0;
        }

        try {
            // Calculate residual sum of squares
            double rss = 0.0;
            long baseTimestamp = lastTrainingData.get(0).getTimestamp();
            double maxTimeIndex = (lastTrainingData.get(lastTrainingData.size() - 1).getTimestamp() - baseTimestamp) / 3600.0;

            for (PriceData data : lastTrainingData) {
                double timeIndex = (data.getTimestamp() - baseTimestamp) / 3600.0;
                double normalizedTimeIndex = timeIndex / maxTimeIndex;

                double predicted = regression.predict(normalizedTimeIndex);
                double residual = data.getPrice() - predicted;
                rss += residual * residual;
            }

            // Calculate standard error
            int degreesOfFreedom = lastTrainingData.size() - 2; // n-2 for simple linear regression
            double standardError = Math.sqrt(rss / degreesOfFreedom);

            return standardError;

        } catch (Exception e) {
            log.error("Error calculating standard error: {}", e.getMessage());
            return 0.0;
        }
    }

    private double calculatePredictionInterval() {
        if (lastTrainingData == null || lastTrainingData.size() < 3) {
            return 0.0;
        }

        try {
            double standardError = calculateStandardError();
            // 95% confidence interval (1.96 for normal distribution)
            return 1.96 * standardError;

        } catch (Exception e) {
            log.error("Error calculating prediction interval: {}", e.getMessage());
            return 0.0;
        }
    }

    // Add helper method to calculate mean squared error
    private double calculateMeanSquaredError() {
        if (lastTrainingData == null || lastTrainingData.isEmpty()) {
            return 0.0;
        }

        double sumSquaredErrors = 0.0;
        long baseTimestamp = lastTrainingData.get(0).getTimestamp();
        double maxTimeIndex = (lastTrainingData.get(lastTrainingData.size() - 1).getTimestamp() - baseTimestamp) / 3600.0;

        for (PriceData data : lastTrainingData) {
            double timeIndex = (data.getTimestamp() - baseTimestamp) / 3600.0;
            double normalizedTimeIndex = timeIndex / maxTimeIndex;

            double predicted = regression.predict(normalizedTimeIndex);
            double error = data.getPrice() - predicted;
            sumSquaredErrors += error * error;
        }

        return sumSquaredErrors / lastTrainingData.size();
    }

    // Add method to get prediction quality metrics
    public Map<String, Double> getPredictionQualityMetrics() {
        Map<String, Double> metrics = new HashMap<>();

        metrics.put("rSquared", getRSquared());
        metrics.put("mse", calculateMeanSquaredError());
        metrics.put("rmse", Math.sqrt(calculateMeanSquaredError()));
        metrics.put("standardError", calculateStandardError());
        metrics.put("predictionInterval", calculatePredictionInterval());

        return metrics;
    }


    // Getter methods for model evaluation
    public double getRSquared() {
        return regression.getRSquare();
    }

    public double getMeanPrice() {
        return statistics.getMean();
    }

    public double getVolatility() {
        return statistics.getStandardDeviation() / statistics.getMean(); // Return as percentage
    }

    public long getLastTrainingTime() {
        return lastTrainingTime;
    }
}
