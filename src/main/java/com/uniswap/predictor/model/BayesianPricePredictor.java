package com.uniswap.predictor.model;

import com.uniswap.predictor.dto.PoolDataPoint;
import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.ComputationGraphConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

@Slf4j
@Component
public class BayesianPricePredictor {
    private ComputationGraph network;
    private double[] featuresMin;
    private double[] featuresMax;
    private double[] labelsMin;
    private double[] labelsMax;
    private final int NUM_FEATURES = 6;       // Number of input features
    private final int NUM_OUTPUTS = 4;        // Mean & std for both lower and upper bounds
    private final int sequenceLength;         // Hours of data to use for prediction
    private final int hiddenLayerSize;        // Size of hidden layers
    private final int NUM_EPOCHS;             // Training epochs
    private final double LEARNING_RATE = 0.001;
    private final double MC_DROPOUT_RATE = 0.2; // Dropout rate for Monte Carlo sampling
    private final int MC_SAMPLES = 100;        // Number of Monte Carlo samples for uncertainty

    // Default constructor for backward compatibility
    public BayesianPricePredictor() {
        this(24, 64, 10); // Default values
    }

    // Constructor with configurable parameters
    public BayesianPricePredictor(int sequenceLength, int hiddenLayerSize) {
        this(sequenceLength, hiddenLayerSize, 10); // Default epochs
    }

    // Constructor with all configurable parameters
    public BayesianPricePredictor(int sequenceLength, int hiddenLayerSize, int epochs) {
        if (sequenceLength <= 0) {
            throw new IllegalArgumentException("Sequence length must be positive, got: " + sequenceLength);
        }
        if (hiddenLayerSize <= 0) {
            throw new IllegalArgumentException("Hidden layer size must be positive, got: " + hiddenLayerSize);
        }
        if (epochs <= 0) {
            throw new IllegalArgumentException("Number of epochs must be positive, got: " + epochs);
        }
        this.sequenceLength = sequenceLength;
        this.hiddenLayerSize = hiddenLayerSize;
        this.NUM_EPOCHS = epochs;
        buildModel();
    }

    public int getNumEpochs() {
        return NUM_EPOCHS;
    }

    public int getSequenceLength() {
        return sequenceLength;
    }

    public int getHiddenLayerSize() {
        return hiddenLayerSize;
    }

    public interface TrainingProgressCallback {
        void onProgress(int epoch, int totalEpochs, double score);
    }

    public void train(List<PoolDataPoint> historicalData, TrainingProgressCallback progressCallback) {
        if (historicalData.size() < sequenceLength) {
            throw new IllegalArgumentException("Not enough historical data for training");
        }

        // Prepare data for training
        List<DataSet> trainingSets = prepareTrainingData(historicalData);

        // Train the model
        for (int epoch = 0; epoch < NUM_EPOCHS; epoch++) {
            double epochScore = 0.0;
            int batchCount = 0;

            for (DataSet dataSet : trainingSets) {
                network.fit(dataSet);
                epochScore += network.score();
                batchCount++;
            }

            // Calculate average score for this epoch
            double avgScore = batchCount > 0 ? epochScore / batchCount : 0.0;

            // Report progress if callback is provided
            if (progressCallback != null) {
                progressCallback.onProgress(epoch, NUM_EPOCHS, avgScore);
            }
        }
    }

    private void buildModel() {
        ComputationGraphConfiguration conf = new NeuralNetConfiguration.Builder()
                .seed(12345)
                .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                .updater(new Adam(LEARNING_RATE))
                .weightInit(WeightInit.XAVIER)
                .graphBuilder()
                .addInputs("input")
                .addLayer("dense1", new DenseLayer.Builder()
                        .nIn(NUM_FEATURES)
                        .nOut(hiddenLayerSize)
                        .activation(Activation.RELU)
                        .build(), "input")
                .addLayer("dropout1", new org.deeplearning4j.nn.conf.layers.DropoutLayer.Builder(MC_DROPOUT_RATE)
                        .build(), "dense1")
                .addLayer("dense2", new DenseLayer.Builder()
                        .nIn(hiddenLayerSize)
                        .nOut(hiddenLayerSize)
                        .activation(Activation.RELU)
                        .build(), "dropout1")
                .addLayer("dropout2", new org.deeplearning4j.nn.conf.layers.DropoutLayer.Builder(MC_DROPOUT_RATE)
                        .build(), "dense2")
                .addLayer("output", new OutputLayer.Builder(LossFunctions.LossFunction.MSE)
                        .nIn(hiddenLayerSize)
                        .nOut(NUM_OUTPUTS)
                        .activation(Activation.IDENTITY)
                        .build(), "dropout2")
                .setOutputs("output")
                .build();

        network = new ComputationGraph(conf);
        network.init();
        network.setListeners(new ScoreIterationListener(10));
    }

    // Original train method for backward compatibility
    public void train(List<PoolDataPoint> historicalData) {
        train(historicalData, null);
    }

    private List<DataSet> prepareTrainingData(List<PoolDataPoint> historicalData) {
        List<DataSet> result = new ArrayList<>();

        // Extract features and normalize
        List<double[]> features = historicalData.stream()
                .map(this::extractFeatures)
                .collect(Collectors.toList());

        // Normalize features
        normalizeFeatures(features);

        // For a feed-forward network, we want to predict the next data point
        for (int i = 0; i < features.size() - sequenceLength; i++) {
            // Input features (current state)
            double[] input = features.get(i);

            // Target sequence (future values to predict)
            List<double[]> targetSequence = features.subList(i + 1, Math.min(i + sequenceLength, features.size()));

            // Create input array for a feed-forward network - shape [1, NUM_FEATURES]
            INDArray inputArray = Nd4j.create(1, NUM_FEATURES);
            for (int k = 0; k < NUM_FEATURES; k++) {
                inputArray.putScalar(new int[]{0, k}, input[k]);
            }

            // Create output array - shape [1, NUM_OUTPUTS]
            INDArray labels = Nd4j.create(1, NUM_OUTPUTS);

            // Find min and max prices in the target sequence
            double minPrice = Double.MAX_VALUE;
            double maxPrice = Double.MIN_VALUE;

            for (double[] target : targetSequence) {
                double price = denormalizeValue(target[0], 0); // Price is first feature
                minPrice = Math.min(minPrice, price);
                maxPrice = Math.max(maxPrice, price);
            }

            // Output: [lowerBoundMean, lowerBoundStd, upperBoundMean, upperBoundStd]
            double volatility = calculateVolatility(targetSequence);
            double lowerStd = volatility * 0.5;
            double upperStd = volatility * 0.5;

            labels.putScalar(new int[]{0, 0}, normalizeValue(minPrice, 0));
            labels.putScalar(new int[]{0, 1}, lowerStd);
            labels.putScalar(new int[]{0, 2}, normalizeValue(maxPrice, 0));
            labels.putScalar(new int[]{0, 3}, upperStd);

            result.add(new DataSet(inputArray, labels));
        }

        return result;
    }

    private double[] extractFeatures(PoolDataPoint dataPoint) {
        double[] features = new double[NUM_FEATURES];

        try {
            // Feature 1: Token0 price (e.g., ETH price in USDT)
            features[0] = safeGetBigDecimalValue(dataPoint.getToken0Price());

            // Feature 2: Pool liquidity
            features[1] = safeGetBigDecimalValue(dataPoint.getLiquidity());

            // Feature 3: Current tick
            features[2] = dataPoint.getTick();

            // Feature 4: 24h volume
            features[3] = safeGetBigDecimalValue(dataPoint.getVolume24h());

            // Feature 5: 24h fees
            features[4] = safeGetBigDecimalValue(dataPoint.getFees24h());

            // Feature 6: 24h volatility
            features[5] = safeGetBigDecimalValue(dataPoint.getVolatility24h());

        } catch (Exception e) {
            log.warn("Error extracting features from dataPoint: {}. Using default values. Error: {}",
                    dataPoint, e.getMessage());
        }

        return features;
    }

    // Helper method to safely handle null BigDecimal values
    private double safeGetBigDecimalValue(BigDecimal value) {
        return value != null ? value.doubleValue() : 0.0;
    }

    private void normalizeFeatures(List<double[]> features) {
        featuresMin = new double[NUM_FEATURES];
        featuresMax = new double[NUM_FEATURES];

        // Initialize with extreme values
        for (int i = 0; i < NUM_FEATURES; i++) {
            featuresMin[i] = Double.MAX_VALUE;
            featuresMax[i] = Double.MIN_VALUE;
        }

        // Find min/max for each feature
        for (double[] feature : features) {
            for (int i = 0; i < NUM_FEATURES; i++) {
                featuresMin[i] = Math.min(featuresMin[i], feature[i]);
                featuresMax[i] = Math.max(featuresMax[i], feature[i]);
            }
        }

        // Normalize all features to [0, 1] range
        for (double[] feature : features) {
            for (int i = 0; i < NUM_FEATURES; i++) {
                if (featuresMax[i] > featuresMin[i]) {
                    feature[i] = (feature[i] - featuresMin[i]) / (featuresMax[i] - featuresMin[i]);
                } else {
                    feature[i] = 0.5; // Default if all values are the same
                }
            }
        }

        // Store the same min/max for labels
        labelsMin = new double[2];
        labelsMax = new double[2];
        labelsMin[0] = featuresMin[0];
        labelsMax[0] = featuresMax[0];
        labelsMin[1] = 0.0;
        labelsMax[1] = 1.0;
    }

    private double normalizeValue(double value, int featureIndex) {
        if (featuresMax[featureIndex] > featuresMin[featureIndex]) {
            return (value - featuresMin[featureIndex]) / (featuresMax[featureIndex] - featuresMin[featureIndex]);
        }
        return 0.5;
    }

    private double denormalizeValue(double normalizedValue, int featureIndex) {
        return normalizedValue * (featuresMax[featureIndex] - featuresMin[featureIndex]) + featuresMin[featureIndex];
    }

    private double calculateVolatility(List<double[]> sequence) {
        if (sequence.size() < 2) {
            return 0.01; // Default low volatility
        }

        double[] prices = new double[sequence.size()];
        for (int i = 0; i < sequence.size(); i++) {
            prices[i] = denormalizeValue(sequence.get(i)[0], 0);
        }

        double[] returns = new double[prices.length - 1];
        for (int i = 0; i < returns.length; i++) {
            returns[i] = Math.log(prices[i + 1] / prices[i]);
        }

        double mean = 0.0;
        for (double ret : returns) {
            mean += ret;
        }
        mean /= returns.length;

        double variance = 0.0;
        for (double ret : returns) {
            variance += Math.pow(ret - mean, 2);
        }
        variance /= returns.length;

        return Math.min(1.0, Math.sqrt(variance));
    }

    /**
     * Enhanced prediction method for longer time periods
     */
    public PricePrediction predictPriceRange(PoolDataPoint currentData, double confidenceLevel, int timePeriodHours) {
        try {
            if (currentData == null || currentData.getToken0Price() == null) {
                throw new IllegalArgumentException("Current price data is null or invalid");
            }

            double currentPrice = currentData.getToken0Price().doubleValue();
            if (Double.isNaN(currentPrice) || Double.isInfinite(currentPrice) || currentPrice <= 0) {
                throw new IllegalArgumentException("Invalid current price: " + currentPrice);
            }

            // For longer time periods, adjust volatility scaling factor
            double volatilityScalingFactor = calculateVolatilityScalingFactor(timePeriodHours);

            double volatility = currentData.getVolatility24h() != null ?
                    currentData.getVolatility24h().doubleValue() : 0.1;

            // Scale volatility based on prediction horizon with square root of time
            double scaledVolatility = volatility * Math.sqrt(timePeriodHours / 24.0) * volatilityScalingFactor;
            double zScore = calculateZScore(confidenceLevel);

            // For long-term predictions, use MC samples for more accuracy
            if (timePeriodHours > 72) {
                return performMonteCarloSimulation(currentPrice, scaledVolatility, zScore, timePeriodHours, confidenceLevel);
            } else {
                // Standard calculation for shorter horizons
                double lowerBound = currentPrice * (1 - zScore * scaledVolatility);
                double upperBound = currentPrice * (1 + zScore * scaledVolatility);
                double median = currentPrice;

                if (Double.isNaN(lowerBound) || Double.isInfinite(lowerBound)) {
                    lowerBound = currentPrice * 0.9;
                }
                if (Double.isNaN(upperBound) || Double.isInfinite(upperBound)) {
                    upperBound = currentPrice * 1.1;
                }

                return new PricePrediction(
                        BigDecimal.valueOf(Math.max(0, lowerBound)),
                        BigDecimal.valueOf(Math.max(0, upperBound)),
                        BigDecimal.valueOf(median),
                        BigDecimal.valueOf(scaledVolatility)
                );
            }
        } catch (Exception e) {
            log.error("Error in price prediction: " + e.getMessage(), e);

            double fallbackLower = currentData.getToken0Price().doubleValue() * 0.9;
            double fallbackUpper = currentData.getToken0Price().doubleValue() * 1.1;

            return new PricePrediction(
                    BigDecimal.valueOf(fallbackLower),
                    BigDecimal.valueOf(fallbackUpper),
                    currentData.getToken0Price(),
                    BigDecimal.valueOf(0.1)
            );
        }
    }

    /**
     * Legacy method for backward compatibility
     */
    public PricePrediction predictPriceRange(PoolDataPoint currentData, double confidenceLevel, int timePeriodHours, boolean useLegacyMethod) {
        if (useLegacyMethod) {
            try {
                if (currentData == null || currentData.getToken0Price() == null) {
                    throw new IllegalArgumentException("Current price data is null or invalid");
                }

                double currentPrice = currentData.getToken0Price().doubleValue();
                if (Double.isNaN(currentPrice) || Double.isInfinite(currentPrice) || currentPrice <= 0) {
                    throw new IllegalArgumentException("Invalid current price: " + currentPrice);
                }

                double volatility = currentData.getVolatility24h() != null ?
                        currentData.getVolatility24h().doubleValue() : 0.1;

                double standardDeviation = volatility * Math.sqrt(timePeriodHours / 24.0);
                double zScore = calculateZScore(confidenceLevel);

                double lowerBound = currentPrice * (1 - zScore * standardDeviation);
                double upperBound = currentPrice * (1 + zScore * standardDeviation);
                double median = currentPrice;

                if (Double.isNaN(lowerBound) || Double.isInfinite(lowerBound)) {
                    lowerBound = currentPrice * 0.9;
                }
                if (Double.isNaN(upperBound) || Double.isInfinite(upperBound)) {
                    upperBound = currentPrice * 1.1;
                }

                return new PricePrediction(
                        BigDecimal.valueOf(Math.max(0, lowerBound)),
                        BigDecimal.valueOf(Math.max(0, upperBound)),
                        BigDecimal.valueOf(median),
                        BigDecimal.valueOf(standardDeviation)
                );
            } catch (Exception e) {
                log.error("Error in price prediction: " + e.getMessage() +
                        ", Current Data: " + currentData +
                        ", Confidence Level: " + confidenceLevel +
                        ", Time Period: " + timePeriodHours, e);

                double fallbackLower = currentData.getToken0Price().doubleValue() * 0.9;
                double fallbackUpper = currentData.getToken0Price().doubleValue() * 1.1;

                return new PricePrediction(
                        BigDecimal.valueOf(fallbackLower),
                        BigDecimal.valueOf(fallbackUpper),
                        currentData.getToken0Price(),
                        BigDecimal.valueOf(0.1)
                );
            }
        } else {
            return predictPriceRange(currentData, confidenceLevel, timePeriodHours);
        }
    }

    /**
     * Calculate volatility scaling factor for long-term predictions
     * Uses mean reversion factor for longer horizons
     */
    private double calculateVolatilityScalingFactor(int timePeriodHours) {
        // For periods > 7 days, apply mean reversion factor
        if (timePeriodHours > 168) {
            // Mean reversion factor (diminishing volatility growth over time)
            double daysParam = timePeriodHours / 24.0;
            // This formula reduces the rate of volatility growth for long periods
            return 0.8 + (0.6 * Math.tanh(daysParam / 30.0));
        }
        return 1.0;
    }

    /**
     * Perform Monte Carlo simulation for more accurate long-term predictions
     */
    private PricePrediction performMonteCarloSimulation(
            double currentPrice, double volatility, double zScore, int timePeriodHours, double confidenceLevel) {

        int numSimulations = MC_SAMPLES;
        double[] finalPrices = new double[numSimulations];

        // Generate random paths
        Random random = new Random();
        double dt = 1.0 / 24.0; // 1-hour steps
        double sqrtDt = Math.sqrt(dt);

        for (int i = 0; i < numSimulations; i++) {
            double price = currentPrice;

            // Simulate price path
            for (int hour = 0; hour < timePeriodHours; hour++) {
                // Use GBM (Geometric Brownian Motion) with mean reversion
                double drift = 0.0; // Assuming neutral drift
                double diffusion = volatility * sqrtDt * random.nextGaussian();

                // Apply mean reversion for longer simulations
                if (hour > 24) {
                    double meanReversionStrength = 0.02; // Strength of mean reversion
                    drift += meanReversionStrength * (currentPrice - price) * dt;
                }

                // Update price
                price *= (1 + drift + diffusion);

                // Floor at zero
                if (price <= 0) price = 0.00001;
            }

            finalPrices[i] = price;
        }

        // Sort results to find percentiles
        Arrays.sort(finalPrices);

        // Calculate bounds based on confidence level
        int lowerIndex = (int)(numSimulations * (1 - confidenceLevel) / 2);
        int upperIndex = (int)(numSimulations * (1 + confidenceLevel) / 2);
        int medianIndex = numSimulations / 2;

        // Ensure indices are within bounds
        lowerIndex = Math.max(0, lowerIndex);
        upperIndex = Math.min(numSimulations - 1, upperIndex);

        double lowerBound = finalPrices[lowerIndex];
        double upperBound = finalPrices[upperIndex];
        double median = finalPrices[medianIndex];

        // Calculate standard deviation
        double sum = 0.0;
        for (double price : finalPrices) {
            sum += Math.pow(price - median, 2);
        }
        double stdDev = Math.sqrt(sum / numSimulations);

        return new PricePrediction(
                BigDecimal.valueOf(Math.max(0, lowerBound)),
                BigDecimal.valueOf(Math.max(0, upperBound)),
                BigDecimal.valueOf(median),
                BigDecimal.valueOf(stdDev)
        );
    }

    private double calculateZScore(double confidenceLevel) {
        if (confidenceLevel >= 0.99) return 2.576;
        if (confidenceLevel >= 0.95) return 1.96;
        if (confidenceLevel >= 0.90) return 1.645;
        if (confidenceLevel >= 0.80) return 1.28;
        return 1.0;
    }

    @lombok.Value
    public static class PricePrediction {
        BigDecimal lowerBound;
        BigDecimal upperBound;
        BigDecimal median;
        BigDecimal standardDeviation;
    }
}