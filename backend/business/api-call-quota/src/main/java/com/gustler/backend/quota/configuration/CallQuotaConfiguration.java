package com.gustler.backend.quota.configuration;

import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/** 위치정보·노선정보 호출 한도 모듈의 구성 요소를 등록한다. */
@Configuration
@ComponentScan(basePackages = "com.gustler.backend.quota", excludeFilters = {
    @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
    @Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
})
public class CallQuotaConfiguration {
}
