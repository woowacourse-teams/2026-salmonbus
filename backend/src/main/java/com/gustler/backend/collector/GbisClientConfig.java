package com.gustler.backend.collector;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(GbisProperties.class)
public class GbisClientConfig {

    private static final String WAF_BYPASS_USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    @Bean
    public RestClient gbisRestClient(
        final RestClient.Builder builder,
        final GbisProperties properties
    ) {
        return builder
            .baseUrl(properties.baseUrl())
            .defaultHeader(HttpHeaders.USER_AGENT, WAF_BYPASS_USER_AGENT)
            .build();
    }
}
