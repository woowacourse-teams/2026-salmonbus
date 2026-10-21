package com.gustler.backend.gbis.configuration;

import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * GBIS 통신과 프로토콜 해석 구성 요소를 모듈 경계에서 등록한다.
 */
@Configuration
@ComponentScan(basePackages = "com.gustler.backend.gbis", excludeFilters = {
    @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
    @Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
})
public class GbisConfiguration {
}
