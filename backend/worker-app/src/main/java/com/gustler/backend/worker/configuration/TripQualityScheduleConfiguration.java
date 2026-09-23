package com.gustler.backend.worker.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnExpression("${forecast.quality-enabled:${forecast.enabled:false} or ${collection.enabled:false}}")
public class TripQualityScheduleConfiguration {
}
