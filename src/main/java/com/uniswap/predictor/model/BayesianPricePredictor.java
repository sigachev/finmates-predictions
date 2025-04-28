package com.uniswap.predictor.model;

import com.uniswap.predictor.dto.PoolDataPoint;
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
import java.util.List;
import java.util.stream.Collectors;

@Component
public class BayesianPricePredictor {

    private ComputationGraph network;
    private double[] featuresMin;
    private double[] featuresMax;
    private double[] labelsMin;
    private double[] labelsMax;
    private final int NUM_FEATURES = 6;       // Number of input features
    private final int NUM_OUTPUTS = 4;        // Mean & std for both lower and upper bounds
    private final int SEQUENCE_LENGTH = 24;   // Hours of data to use for prediction
    private final int HIDDEN_LAYER_SIZE = 64; // Size of hidden layers
    private final int NUM_EPOCHS = 100;       // Training epochs
    private final double LEARNING_RATE = 0.001;
    private final double MC_DROPOUT_RATE = 0.2; // Dropout rate for Monte Carlo sampling
    private final int MC_SAMPLES = 100;        // Number of Monte Carlo samples for uncertainty

    // Add getter method
    public int getNumEpochs() {
        return NUM_EPOCHS;
    }

    // Add this to BayesianPricePredictor.java
    public interface TrainingProgressCallback {
        void onProgress(int epoch, int totalEpochs, double score);
    }

    // Then add this overloaded train method
    public void train(List<PoolDataPoint> historicalData, TrainingProgressCallback progressCallback) {
        if (historicalData.size() < SEQUENCE_LENGTH) {
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

    public BayesianPricePredictor() {
        buildModel();
    }

    private void buildModel() {
        // Create a simpler model without LSTM for initial testing
        ComputationGraphConfiguration conf = new NeuralNetConfiguration.Builder()
                .seed(12345)
                .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                .updater(new Adam(LEARNING_RATE))
                .weightInit(WeightInit.XAVIER)
                .graphBuilder()
                .addInputs("input")
                // Use a regular feed-forward network instead
                .addLayer("dense1", new DenseLayer.Builder()
                        .nIn(NUM_FEATURES)
                        .nOut(HIDDEN_LAYER_SIZE)
                        .activation(Activation.RELU)
                        .build(), "input")
                .addLayer("dropout1", new org.deeplearning4j.nn.conf.layers.DropoutLayer.Builder(MC_DROPOUT_RATE)
                        .build(), "dense1")
                .addLayer("dense2", new DenseLayer.Builder()
                        .nIn(HIDDEN_LAYER_SIZE)
                        .nOut(HIDDEN_LAYER_SIZE)
                        .activation(Activation.RELU)
                        .build(), "dropout1")
                .addLayer("dropout2", new org.deeplearning4j.nn.conf.layers.DropoutLayer.Builder(MC_DROPOUT_RATE)
                        .build(), "dense2")
                .addLayer("output", new OutputLayer.Builder(LossFunctions.LossFunction.MSE)
                        .nIn(HIDDEN_LAYER_SIZE)
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
        for (int i = 0; i < features.size() - SEQUENCE_LENGTH; i++) {
            // Input features (current state)
            double[] input = features.get(i);

            // Target sequence (future values to predict)
            List<double[]> targetSequence = features.subList(i + 1, Math.min(i + SEQUENCE_LENGTH, features.size()));

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

        // Feature 1: Token0 price (e.g., ETH price in USDT)
        features[0] = dataPoint.getToken0Price() != null ?
                dataPoint.getToken0Price().doubleValue() : 0.0;

        // Feature 2: Pool liquidity
        features[1] = dataPoint.getLiquidity() != null ?
                dataPoint.getLiquidity().doubleValue() : 0.0;

        // Feature 3: Current tick
        features[2] = dataPoint.getTick();

        // Feature 4: 24h volume
        features[3] = dataPoint.getVolume24h() != null ?
                dataPoint.getVolume24h().doubleValue() : 0.0;

        // Feature 5: 24h fees
        features[4] = dataPoint.getFees24h() != null ?
                dataPoint.getFees24h().doubleValue() : 0.0;

        // Feature 6: 24h volatility
        features[5] = dataPoint.getVolatility24h() != null ?
                dataPoint.getVolatility24h().doubleValue() : 0.0;

        return features;
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

        // Store the same min/max for labels (we'll use price min/max)
        labelsMin = new double[2];
        labelsMax = new double[2];

        labelsMin[0] = featuresMin[0]; // Price min
        labelsMax[0] = featuresMax[0]; // Price max

        // Volatility min/max
        labelsMin[1] = 0.0;
        labelsMax[1] = 1.0;
    }

    private double normalizeValue(double value, int featureIndex) {
        if (featuresMax[featureIndex] > featuresMin[featureIndex]) {
            return (value - featuresMin[featureIndex]) / (featuresMax[featureIndex] - featuresMin[featureIndex]);
        } else {
            return 0.5;
        }
    }

    private double denormalizeValue(double normalizedValue, int featureIndex) {
        return normalizedValue * (featuresMax[featureIndex] - featuresMin[featureIndex]) + featuresMin[featureIndex];
    }

    private double calculateVolatility(List<double[]> sequence) {
        if (sequence.size() < 2) {
            return 0.01; // Default low volatility
        }

        // Get price values
        double[] prices = new double[sequence.size()];
        for (int i = 0; i < sequence.size(); i++) {
            prices[i] = denormalizeValue(sequence.get(i)[0], 0);
        }

        // Calculate log returns
        double[] returns = new double[prices.length - 1];
        for (int i = 0; i < returns.length; i++) {
            returns[i] = Math.log(prices[i + 1] / prices[i]);
        }

        // Calculate variance
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

        // Return standard deviation as volatility (normalized)
        return Math.min(1.0, Math.sqrt(variance));
    }

    public PricePrediction predictPriceRange(PoolDataPoint currentData, double confidenceLevel, int timePeriodHours) {
        // Extract and normalize current features
        double[] features = extractFeatures(currentData);
        double[] normalizedFeatures = new double[NUM_FEATURES];

        for (int i = 0; i < NUM_FEATURES; i++) {
            normalizedFeatures[i] = (features[i] - featuresMin[i]) / (featuresMax[i] - featuresMin[i]);
        }

        // For feed-forward network, create a 2D input array [1, NUM_FEATURES]
        INDArray input = Nd4j.create(1, NUM_FEATURES);
        for (int k = 0; k < NUM_FEATURES; k++) {
            input.putScalar(new int[]{0, k}, normalizedFeatures[k]);
        }

        // Monte Carlo sampling with dropout for uncertainty estimation
        List<INDArray> mcSamples = new ArrayList<>();

        for (int i = 0; i < MC_SAMPLES; i++) {
            // Forward pass with dropout active
            INDArray output = network.output(input)[0];
            mcSamples.add(output);
        }

        // Rest of the method remains the same...
        // Calculate mean and variance from MC samples
        double[] means = new double[NUM_OUTPUTS];
        double[] variances = new double[NUM_OUTPUTS];

        // Initialize arrays
        for (int i = 0; i < NUM_OUTPUTS; i++) {
            means[i] = 0.0;
            variances[i] = 0.0;
        }

        // Calculate means
        for (INDArray sample : mcSamples) {
            for (int i = 0; i < NUM_OUTPUTS; i++) {
                means[i] += sample.getDouble(0, i);
            }
        }

        for (int i = 0; i < NUM_OUTPUTS; i++) {
            means[i] /= MC_SAMPLES;
        }

        // Calculate variances
        for (INDArray sample : mcSamples) {
            for (int i = 0; i < NUM_OUTPUTS; i++) {
                double diff = sample.getDouble(0, i) - means[i];
                variances[i] += diff * diff;
            }
        }

        for (int i = 0; i < NUM_OUTPUTS; i++) {
            variances[i] /= MC_SAMPLES;
        }

        // Extract predicted values
        double lowerBoundMean = denormalizeValue(means[0], 0);
        double lowerBoundStd = Math.sqrt(variances[0] + Math.pow(means[1], 2)); // Total uncertainty
        double upperBoundMean = denormalizeValue(means[2], 0);
        double upperBoundStd = Math.sqrt(variances[2] + Math.pow(means[3], 2)); // Total uncertainty

        // Apply confidence interval based on normal distribution
        double z = getZScore(confidenceLevel);

        // Calculate price range with confidence interval
        double lowerBound = lowerBoundMean - z * lowerBoundStd * (upperBoundMean - lowerBoundMean);
        double upperBound = upperBoundMean + z * upperBoundStd * (upperBoundMean - lowerBoundMean);

        // Adjust for time period (longer periods have wider ranges)
        double timeAdjustment = Math.sqrt(timePeriodHours / 24.0);
        double currentPrice = features[0];
        double adjustedLowerBound = currentPrice - (currentPrice - lowerBound) * timeAdjustment;
        double adjustedUpperBound = currentPrice + (upperBound - currentPrice) * timeAdjustment;

        // Make sure bounds are sensible (no negative prices for crypto)
        adjustedLowerBound = Math.max(0, adjustedLowerBound);
        adjustedUpperBound = Math.max(adjustedLowerBound * 1.001, adjustedUpperBound); // Ensure min range

        return new PricePrediction(
                BigDecimal.valueOf(adjustedLowerBound),
                BigDecimal.valueOf(adjustedUpperBound),
                BigDecimal.valueOf(currentPrice),
                BigDecimal.valueOf(lowerBoundStd),
                confidenceLevel
        );
    }

    private double getZScore(double confidenceLevel) {
        // Common z-scores
        if (confidenceLevel >= 0.99) return 2.576;
        if (confidenceLevel >= 0.98) return 2.326;
        if (confidenceLevel >= 0.95) return 1.96;
        if (confidenceLevel >= 0.90) return 1.645;
        if (confidenceLevel >= 0.80) return 1.282;
        return 1.0; // Default for lower confidence
    }

    public static class PricePrediction {
        private final BigDecimal lowerBound;
        private final BigDecimal upperBound;
        private final BigDecimal median;
        private final BigDecimal standardDeviation;
        private final double confidenceLevel;

        public PricePrediction(BigDecimal lowerBound, BigDecimal upperBound,
                               BigDecimal median, BigDecimal standardDeviation,
                               double confidenceLevel) {
            this.lowerBound = lowerBound;
            this.upperBound = upperBound;
            this.median = median;
            this.standardDeviation = standardDeviation;
            this.confidenceLevel = confidenceLevel;
        }

        public BigDecimal getLowerBound() {
            return lowerBound;
        }

        public BigDecimal getUpperBound() {
            return upperBound;
        }

        public BigDecimal getMedian() {
            return median;
        }

        public BigDecimal getStandardDeviation() {
            return standardDeviation;
        }

        public double getConfidenceLevel() {
            return confidenceLevel;
        }
    }



}