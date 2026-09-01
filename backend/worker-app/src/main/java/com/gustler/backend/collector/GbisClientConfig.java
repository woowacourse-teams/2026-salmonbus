package com.gustler.backend.collector;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(GbisProperties.class)
public class GbisClientConfig {

    @Bean
    public RestClient gbisRestClient(
        RestClient.Builder builder,
        GbisProperties properties
    ) {
        return builder
            .baseUrl(properties.baseUrl())
            .build();
    }
}
