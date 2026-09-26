package com.gustler.backend.gbis.configuration;

import com.gustler.backend.gbis.api.GbisClientOptions;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class GbisClientConfig {

    @Bean
    public RestClient gbisRestClient(
        RestClient.Builder builder,
        GbisClientOptions properties
    ) {
        return builder
            .baseUrl(properties.baseUrl())
            .build();
    }
}
