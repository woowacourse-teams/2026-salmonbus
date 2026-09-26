package com.gustler.backend.worker.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@ConditionalOnProperty(prefix = "collection", name = "enabled", havingValue = "true")
@Import(CollectionRuntimeConfiguration.class)
public class EnabledCollectionConfiguration {
}
