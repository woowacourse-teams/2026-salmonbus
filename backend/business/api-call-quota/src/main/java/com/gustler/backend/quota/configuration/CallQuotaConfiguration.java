package com.gustler.backend.quota.configuration;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = "com.gustler.backend.quota", excludeFilters = {
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
})
public class CallQuotaConfiguration {
}
