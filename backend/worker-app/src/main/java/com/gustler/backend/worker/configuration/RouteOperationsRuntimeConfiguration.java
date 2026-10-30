package com.gustler.backend.worker.configuration;

import com.gustler.backend.routeoperations.configuration.RouteOperationsConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import(RouteOperationsConfiguration.class)
public class RouteOperationsRuntimeConfiguration {
}
