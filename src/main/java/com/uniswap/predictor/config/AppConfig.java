package com.uniswap.predictor.config;

import com.uniswap.predictor.model.BayesianPricePredictor;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.ComputationGraphConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.LSTM;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
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

    @Value("${model.sequence.length:24}")
    private int sequenceLength;

    @Value("${model.hidden.size:64}")
    private int hiddenLayerSize;

    @Value("${model.learning.rate:0.001}")
    private double learningRate;

    @Value("${model.dropout.rate:0.2}")
    private double dropoutRate;

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(25);
        executor.setThreadNamePrefix("UniswapPredictor-");
        executor.initialize();
        return executor;
    }

    @Bean
    @Scope("prototype")
    public BayesianPricePredictor bayesianPricePredictor() {
        // Use the no-args constructor which has default values
        return new BayesianPricePredictor();
    }

    @Bean
    @Scope("prototype")
    public ComputationGraphConfiguration nnConfiguration() {
        final int NUM_FEATURES = 6;
        final int NUM_OUTPUTS = 4;

        return new NeuralNetConfiguration.Builder()
                .seed(12345)
                .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                .updater(new Adam(learningRate))
                .weightInit(WeightInit.XAVIER)
                .graphBuilder()
                .addInputs("input")
                .addLayer("lstm1", new LSTM.Builder()
                        .nIn(NUM_FEATURES)
                        .nOut(hiddenLayerSize)
                        .activation(Activation.TANH)
                        .build(), "input")
                // Add RNN output layer to get last time step only
                .addLayer("rnn_output", new org.deeplearning4j.nn.conf.layers.RnnOutputLayer.Builder()
                        .nIn(hiddenLayerSize)
                        .nOut(hiddenLayerSize)
                        .activation(Activation.IDENTITY)
                        .build(), "lstm1")
                .addLayer("dropout1", new org.deeplearning4j.nn.conf.layers.DropoutLayer.Builder(dropoutRate)
                        .build(), "rnn_output")
                .addLayer("dense1", new DenseLayer.Builder()
                        .nIn(hiddenLayerSize)
                        .nOut(hiddenLayerSize)
                        .activation(Activation.RELU)
                        .build(), "dropout1")
                .addLayer("dropout2", new org.deeplearning4j.nn.conf.layers.DropoutLayer.Builder(dropoutRate)
                        .build(), "dense1")
                .addLayer("output", new OutputLayer.Builder(LossFunctions.LossFunction.MSE)
                        .nIn(hiddenLayerSize)
                        .nOut(NUM_OUTPUTS)
                        .activation(Activation.IDENTITY)
                        .build(), "dropout2")
                .setOutputs("output")
                .build();
    }
}