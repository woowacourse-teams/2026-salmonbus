package com.gustler.backend.observations.configuration;

import com.gustler.backend.observations.application.CollectionInputService;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 관측 저장에 필요한 매핑과 저장소 구성 요소를 업무 모듈에 연결한다.
 */
@Configuration
@ComponentScan(basePackages = "com.gustler.backend.observations.infrastructure.jpa", excludeFilters = {
    @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
    @Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
})
@EntityScan("com.gustler.backend.observations.infrastructure.jpa")
@EnableJpaRepositories("com.gustler.backend.observations.infrastructure.jpa")
@Import(CollectionInputService.class)
public class ObservationPersistenceConfiguration {
}
