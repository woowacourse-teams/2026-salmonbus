package com.gustler.backend.api.push.delivery;

import com.gustler.backend.api.push.application.NotificationDeliveryRepository;
import com.gustler.backend.api.push.application.NotificationDeliveryService;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "push.enabled", havingValue = "true")
@EnableConfigurationProperties(ApnsProperties.class)
@EnableScheduling
public class PushDeliveryConfiguration {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    static HttpClient apnsHttpClient() {
        return HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    }

    @Bean
    ApnsProviderToken apnsProviderToken(ApnsProperties properties, Clock clock) {
        return ApnsProviderToken.load(properties.requireComplete(), clock);
    }

    @Bean
    ApnsClient apnsClient(ApnsProperties properties, ApnsProviderToken providerToken, ObjectMapper objectMapper) {
        ApnsProperties complete = properties.requireComplete();
        return new ApnsClient(
            apnsHttpClient(),
            URI.create(complete.productionUri()),
            URI.create(complete.sandboxUri()),
            complete.topic(),
            providerToken,
            objectMapper,
            REQUEST_TIMEOUT
        );
    }

    @Bean
    NotificationDeliveryRunner notificationDeliveryRunner(
        NotificationDeliveryService service,
        ApnsSender sender
    ) {
        return new NotificationDeliveryRunner(service, sender);
    }

    @Bean
    NotificationDeliveryJob notificationDeliveryJob(
        NotificationDeliveryService service,
        NotificationDeliveryRunner runner
    ) {
        return new NotificationDeliveryJob(service, runner);
    }

    @Bean
    NotificationDeliveryPurge notificationDeliveryPurge(
        NotificationDeliveryRepository deliveries,
        Clock clock,
        @Value("${push.delivery.retention:7d}") Duration retention
    ) {
        return new NotificationDeliveryPurge(deliveries, clock, retention);
    }

    @Bean
    NotificationDeliverySchedule notificationDeliverySchedule(
        NotificationDeliveryJob job,
        @Value("${push.delivery.cron:0 * * * * *}") String cron
    ) {
        return new NotificationDeliverySchedule(job, cron);
    }
}
