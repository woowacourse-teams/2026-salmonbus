package com.gustler.backend.api.salmon.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SalmonActionSettings.class)
public class SalmonActionConfiguration {
}
