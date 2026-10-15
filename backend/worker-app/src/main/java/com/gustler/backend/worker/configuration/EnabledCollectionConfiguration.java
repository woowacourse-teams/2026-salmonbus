package com.gustler.backend.worker.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@ConditionalOnExpression("${collection.enabled:false} or ${route-geometry.enabled:false}")
@EnableScheduling
@Import(CollectionRuntimeConfiguration.class)
public class EnabledCollectionConfiguration {
}
