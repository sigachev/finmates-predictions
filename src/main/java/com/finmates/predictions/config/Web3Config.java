package com.finmates.predictions.config;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class Web3Config {

    @Value("${ethereum.node.url:https://arb1.arbitrum.io/rpc}")
    private String ethereumNodeUrl;

    @Value("${web3j.http-timeout:10000}")
    private long httpTimeout;

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(httpTimeout, TimeUnit.MILLISECONDS)
                .readTimeout(httpTimeout, TimeUnit.MILLISECONDS)
                .writeTimeout(httpTimeout, TimeUnit.MILLISECONDS)
                .build();
    }

    @Bean
    public Web3j web3j(OkHttpClient okHttpClient) {
        return Web3j.build(new HttpService(ethereumNodeUrl, okHttpClient));
    }

    @Bean
    public HttpService httpService(OkHttpClient okHttpClient) {
        HttpService httpService = new HttpService(ethereumNodeUrl, okHttpClient);
        httpService.addHeader("Content-Type", "application/json");
        return httpService;
    }

}
