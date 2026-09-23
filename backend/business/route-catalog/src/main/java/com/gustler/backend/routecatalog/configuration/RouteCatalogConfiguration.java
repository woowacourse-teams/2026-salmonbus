package com.gustler.backend.routecatalog.configuration;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@ComponentScan("com.gustler.backend.routecatalog")
@EntityScan("com.gustler.backend.routecatalog.infrastructure.jpa")
@EnableJpaRepositories("com.gustler.backend.routecatalog.infrastructure.jpa")
public class RouteCatalogConfiguration {
}
