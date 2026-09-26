package com.gustler.backend.observations.configuration;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** 예보와 품질 판정이 기존 관측을 읽고 입력을 확정할 때 사용하는 저장 구성. */
@Configuration
@ComponentScan(basePackages = "com.gustler.backend.observations.infrastructure.jpa", excludeFilters = {
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
})
@EntityScan("com.gustler.backend.observations.infrastructure.jpa")
@EnableJpaRepositories("com.gustler.backend.observations.infrastructure.jpa")
public class ObservationPersistenceConfiguration {
}
