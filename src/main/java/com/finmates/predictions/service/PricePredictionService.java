package com.finmates.predictions.service;

import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.springframework.stereotype.Service;
import com.finmates.predictions.model.PriceData;
import com.finmates.predictions.model.PriceRange;
import java.util.List;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

@Service
public class PricePredictionService {
    private SimpleRegression regression;
    private DescriptiveStatistics statistics;

    public PricePredictionService() {
        this.regression = new SimpleRegression();
        this.statistics = new DescriptiveStatistics();
    }

    public void trainModel(List<PriceData> historicalData) {
        // Reset the regression and statistics
        this.regression = new SimpleRegression();
        this.statistics = new DescriptiveStatistics();

        // Add data points to regression and statistics
        for (int i = 0; i < historicalData.size(); i++) {
            PriceData data = historicalData.get(i);
            regression.addData(i, data.getPrice());
            statistics.addValue(data.getPrice());
        }
    }

    public PriceRange predictPriceRange(int hoursAhead) {
        // Get predicted price
        double predictedPrice = regression.predict(regression.getN() + hoursAhead);

        // Calculate confidence interval using standard deviation
        double standardDeviation = statistics.getStandardDeviation();
        double confidenceInterval = 1.96 * standardDeviation; // 95% confidence interval

        // Calculate volatility adjustment based on time horizon
        double volatilityAdjustment = Math.sqrt(hoursAhead / 24.0); // Scale with square root of time

        // Adjust confidence interval based on prediction horizon
        double adjustedInterval = confidenceInterval * volatilityAdjustment;

        return new PriceRange(
                Math.max(0, predictedPrice - adjustedInterval), // Ensure non-negative price
                predictedPrice + adjustedInterval
        );
    }

    // Additional helper methods for model evaluation
    public double getRSquared() {
        return regression.getRSquare();
    }

    public double getMeanPrice() {
        return statistics.getMean();
    }

    public double getVolatility() {
        return statistics.getStandardDeviation();
    }
}
