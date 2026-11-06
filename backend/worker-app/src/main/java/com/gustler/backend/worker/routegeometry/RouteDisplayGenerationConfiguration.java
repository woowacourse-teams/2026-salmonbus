package com.gustler.backend.worker.routegeometry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Keep CPU-heavy geometry work off the collection and arrival scheduler. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "route-geometry.generation.enabled", havingValue = "true")
public class RouteDisplayGenerationConfiguration {
    @Bean(name = "routeDisplayGenerationScheduler")
    public ThreadPoolTaskScheduler generationScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("route-display-generation-");
        return scheduler;
    }
}
