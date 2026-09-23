package com.gustler.backend.observations.configuration;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@ComponentScan("com.gustler.backend.observations")
@EntityScan("com.gustler.backend.observations.infrastructure.jpa")
@EnableJpaRepositories("com.gustler.backend.observations.infrastructure.jpa")
public class ObservationsConfiguration {
}
