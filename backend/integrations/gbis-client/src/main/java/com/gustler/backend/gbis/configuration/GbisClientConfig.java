package com.gustler.backend.gbis.configuration;

import com.gustler.backend.gbis.api.GbisClientOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.Builder;

/**
 * 설정된 GBIS 주소로 요청을 보내는 HTTP 클라이언트를 구성한다.
 * 이 구성 자체는 요청을 보내지 않는다.
 */
@Configuration
public class GbisClientConfig {
    @Bean
    public RestClient gbisRestClient(
        Builder builder,
        GbisClientOptions properties
    ) {
        return builder
            .baseUrl(properties.baseUrl())
            .build();
    }
}
