package com.uniswap.predictor.config;

import com.uniswap.predictor.model.BayesianPricePredictor;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.ComputationGraphConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.WorkspaceMode;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.layers.GravesLSTM;
import org.deeplearning4j.nn.weights.WeightInit;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.learning.config.Adam;
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
                // Specify input type with sequence length
                .addInputs("input")
                .setInputTypes(InputType.recurrent(NUM_FEATURES, sequenceLength))

                // First LSTM layer with specified sequence length
                .addLayer("lstm1", new GravesLSTM.Builder()
                        .nIn(NUM_FEATURES)
                        .nOut(hiddenLayerSize)
                        .activation(Activation.TANH)
                        .gateActivationFunction(Activation.SIGMOID)
                        .build(), "input")

                // ... rest of the layers remain the same ...
                .build();
    }

}
