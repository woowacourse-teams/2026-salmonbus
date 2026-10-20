package com.gustler.backend.forecasting.configuration;

import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * 예보·정산·품질·통계 업무의 구성 요소를 Worker에서 사용할 모듈 경계로 등록한다.
 */
@Configuration
@ComponentScan(basePackages = "com.gustler.backend.forecasting", excludeFilters = {
    @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
    @Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
})
public class ForecastingConfiguration {
}
