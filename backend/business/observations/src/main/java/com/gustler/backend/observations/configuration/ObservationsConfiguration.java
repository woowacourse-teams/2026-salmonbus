package com.gustler.backend.observations.configuration;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import com.gustler.backend.observations.infrastructure.gbis.GbisObservationMapper;

@Configuration
@ComponentScan(basePackages = "com.gustler.backend.observations.application", excludeFilters = {
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
})
@Import({ObservationPersistenceConfiguration.class, GbisObservationMapper.class})
public class ObservationsConfiguration {
}
