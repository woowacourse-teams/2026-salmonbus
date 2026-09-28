package com.gustler.backend.observations.configuration;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import com.gustler.backend.observations.application.CollectionInputService;

@Configuration
@ComponentScan(basePackages = "com.gustler.backend.observations.infrastructure.jpa", excludeFilters = {
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
})
@EntityScan("com.gustler.backend.observations.infrastructure.jpa")
@EnableJpaRepositories("com.gustler.backend.observations.infrastructure.jpa")
@Import(CollectionInputService.class)
public class ObservationPersistenceConfiguration {
}
