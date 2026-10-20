package com.gustler.backend.routecatalog.configuration;

import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 노선 카탈로그의 조회·상류 해석·판본 저장 구성 요소를 업무 모듈로 등록한다.
 */
@Configuration
@ComponentScan(basePackages = "com.gustler.backend.routecatalog", excludeFilters = {
    @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
    @Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
})
@EntityScan("com.gustler.backend.routecatalog.infrastructure.jpa")
@EnableJpaRepositories("com.gustler.backend.routecatalog.infrastructure.jpa")
public class RouteCatalogConfiguration {
}
