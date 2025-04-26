package com.finmates.predictions.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import okhttp3.OkHttpClient;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class Web3Config {

    @Value("${ethereum.node.url:http://localhost:8545}")
    private String ethereumNodeUrl;

    @Bean
    @Primary
    public Web3j web3j() {
        OkHttpClient httpClient = createOkHttpClient();
        // Create HttpService with the OkHttpClient directly
        HttpService httpService = new HttpService(ethereumNodeUrl, httpClient, false);
        return Web3j.build(httpService);
    }

    private OkHttpClient createOkHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }
}
