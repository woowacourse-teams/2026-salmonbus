package com.gustler.backend.routecatalog.infrastructure.supplementary;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "route-geometry.multi-source.enabled", havingValue = "true")
class RouteGeometryEvidenceConfiguration {
    @Bean
    ThreadPoolTaskScheduler routeGeometryEvidenceScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("route-reference-");
        return scheduler;
    }
}
