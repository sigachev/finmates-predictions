package com.uniswap.predictor.config;

import com.uniswap.predictor.model.BayesianPricePredictor;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.ComputationGraphConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.WorkspaceMode;
import org.deeplearning4j.nn.conf.graph.MergeVertex;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.layers.*;
import org.deeplearning4j.nn.weights.WeightInit;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@EnableScheduling
public class AppConfig {

    @Value("${model.sequence.length:48}")  // Increased to 48 for better historical context
    private int sequenceLength;

    @Value("${model.hidden.size:128}")     // Increased to 128 for more capacity
    private int hiddenLayerSize;

    @Value("${model.learning.rate:0.0005}") // Reduced for more stable training
    private double learningRate;

    @Value("${model.dropout.rate:0.3}")    // Increased for better regularization
    private double dropoutRate;

    @Value("${model.l2.regularization:0.00001}")
    private double l2Regularization;

    @Value("${model.mini.batch.size:32}")
    private int miniBatchSize;

    @Value("${executor.core.pool.size:5}")
    private int corePoolSize;

    @Value("${executor.max.pool.size:10}")
    private int maxPoolSize;

    @Value("${executor.queue.capacity:25}")
    private int queueCapacity;

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("UniswapPredictor-");
        executor.initialize();
        return executor;
    }

    @Bean
    @Scope("prototype")
    public BayesianPricePredictor bayesianPricePredictor() {
        return new BayesianPricePredictor(sequenceLength, hiddenLayerSize);
    }

    @Bean
    @Scope("prototype")
    public ComputationGraphConfiguration nnConfiguration() {
        final int NUM_FEATURES = 6;  // price, volume, liquidity, volatility, tick, timestamp
        final int NUM_OUTPUTS = 4;   // mean, std, lower_bound, upper_bound

        return new NeuralNetConfiguration.Builder()
                .seed(12345)
                .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                .updater(new Adam(learningRate, 0.9, 0.999, 1e-8))
                .weightInit(WeightInit.XAVIER)
                .l2(l2Regularization)
                .miniBatch(true)
                .trainingWorkspaceMode(WorkspaceMode.ENABLED)
                .inferenceWorkspaceMode(WorkspaceMode.ENABLED)
                .graphBuilder()
                .addInputs("input")
                .setInputTypes(InputType.recurrent(NUM_FEATURES, sequenceLength))

                // First LSTM layer
                .addLayer("lstm1", new GravesLSTM.Builder()
                        .nIn(NUM_FEATURES)
                        .nOut(hiddenLayerSize)
                        .activation(Activation.TANH)
                        .gateActivationFunction(Activation.SIGMOID)
                        .build(), "input")

                // Add dropout after first LSTM
                .addLayer("dropout1", new DropoutLayer.Builder(dropoutRate)
                        .build(), "lstm1")

                // Second LSTM layer with reduced size
                .addLayer("lstm2", new GravesLSTM.Builder()
                        .nIn(hiddenLayerSize)
                        .nOut(hiddenLayerSize/2)
                        .activation(Activation.TANH)
                        .gateActivationFunction(Activation.SIGMOID)
                        .build(), "dropout1")

                // Add dropout after second LSTM
                .addLayer("dropout2", new DropoutLayer.Builder(dropoutRate)
                        .build(), "lstm2")

                // Third LSTM layer for deeper feature extraction
                .addLayer("lstm3", new GravesLSTM.Builder()
                        .nIn(hiddenLayerSize/2)
                        .nOut(hiddenLayerSize/2)
                        .activation(Activation.TANH)
                        .gateActivationFunction(Activation.SIGMOID)
                        .build(), "dropout2")

                // Dense layer for feature combination
                .addLayer("dense1", new DenseLayer.Builder()
                        .nIn(hiddenLayerSize/2)
                        .nOut(hiddenLayerSize/4)
                        .activation(Activation.RELU)
                        .build(), "lstm3")

                // Batch normalization for training stability
                .addLayer("batchnorm1", new BatchNormalization.Builder()
                        .build(), "dense1")

                // Second dense layer
                .addLayer("dense2", new DenseLayer.Builder()
                        .nIn(hiddenLayerSize/4)
                        .nOut(hiddenLayerSize/4)
                        .activation(Activation.RELU)
                        .build(), "batchnorm1")

                // Final batch normalization
                .addLayer("batchnorm2", new BatchNormalization.Builder()
                        .build(), "dense2")

                // Output layer for predictions
                .addLayer("output", new RnnOutputLayer.Builder()
                        .nIn(hiddenLayerSize/4)
                        .nOut(NUM_OUTPUTS)
                        .activation(Activation.IDENTITY)
                        .lossFunction(LossFunctions.LossFunction.MSE)
                        .build(), "batchnorm2")

                .setOutputs("output")

                // Add skip connections using merge vertices
                .addVertex("merge1", new MergeVertex(), "lstm1", "lstm2")
                .addVertex("merge2", new MergeVertex(), "lstm2", "lstm3")

                .build();
    }


}