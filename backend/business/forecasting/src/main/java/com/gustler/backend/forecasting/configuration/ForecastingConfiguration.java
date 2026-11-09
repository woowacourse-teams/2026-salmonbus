package com.gustler.backend.forecasting.configuration;

import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
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
    @Bean
    @DependsOnDatabaseInitialization
    SmartInitializingSingleton vehicleCapacityWarmUp(VehicleCapacityCache capacities) {
        return capacities::warmUp;
    }
}
