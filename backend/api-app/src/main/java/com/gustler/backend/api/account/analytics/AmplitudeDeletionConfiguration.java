package com.gustler.backend.api.account.analytics;

import com.gustler.backend.member.api.AnalyticsDeletions;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
@EnableConfigurationProperties(AmplitudeDeletionProperties.class)
public class AmplitudeDeletionConfiguration {
    @Bean
    @ConditionalOnProperty(name = "analytics.amplitude.enabled", havingValue = "true", matchIfMissing = true)
    AmplitudeDeletionClient amplitudeDeletionClient(AmplitudeDeletionProperties properties) {
        properties.requireComplete();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return new AmplitudeDeletionClient(RestClient.builder().requestFactory(factory).build(), properties);
    }

    @Bean
    AmplitudeDeletionProcessor amplitudeDeletionProcessor(
        AnalyticsDeletions deletions,
        ObjectProvider<AmplitudeDeletionClient> client
    ) {
        return new AmplitudeDeletionProcessor(deletions, client);
    }
}
